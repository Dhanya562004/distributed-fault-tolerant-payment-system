package com.paymentsystem.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsystem.dto.request.InitiatePaymentRequest;
import com.paymentsystem.entity.IdempotencyKeyRecord;
import com.paymentsystem.exception.DuplicateRequestException;
import com.paymentsystem.repository.IdempotencyKeyRepository;
import com.paymentsystem.service.impl.IdempotencyServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    @Mock
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Mock
    private MetricsService metricsService;

    private ObjectMapper objectMapper = new ObjectMapper();

    private IdempotencyService idempotencyService;

    @BeforeEach
    void setUp() {
        idempotencyService = new IdempotencyServiceImpl(idempotencyKeyRepository, objectMapper, metricsService);
    }

    @Test
    @DisplayName("Should successfully acquire lock for new idempotency key")
    void testTryAcquire_NewKey_Success() {
        String key = "IDEM_KEY_1001";
        InitiatePaymentRequest request = new InitiatePaymentRequest(key, "USR_ALICE", new BigDecimal("150.00"), "USD", "CREDIT_CARD", "Test");

        when(idempotencyKeyRepository.findByKeyValue(key)).thenReturn(Optional.empty());
        when(idempotencyKeyRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Optional<IdempotencyKeyRecord> result = idempotencyService.tryAcquireOrGet(key, request);

        assertTrue(result.isEmpty());
        verify(idempotencyKeyRepository, times(1)).saveAndFlush(any());
    }

    @Test
    @DisplayName("Should throw DuplicateRequestException when request with same key is IN_PROGRESS")
    void testTryAcquire_InProgress_ThrowsException() {
        String key = "IDEM_KEY_1002";
        InitiatePaymentRequest request = new InitiatePaymentRequest(key, "USR_ALICE", new BigDecimal("150.00"), "USD", "CREDIT_CARD", "Test");

        IdempotencyKeyRecord existing = new IdempotencyKeyRecord(key, idempotencyService.computePayloadHash(request));
        existing.setStatus(IdempotencyKeyRecord.Status.IN_PROGRESS);

        when(idempotencyKeyRepository.findByKeyValue(key)).thenReturn(Optional.of(existing));

        assertThrows(DuplicateRequestException.class, () -> {
            idempotencyService.tryAcquireOrGet(key, request);
        });

        verify(metricsService, times(1)).recordIdempotencyHit();
    }

    @Test
    @DisplayName("Should return cached completed record on duplicate request")
    void testTryAcquire_Completed_ReturnsCachedRecord() {
        String key = "IDEM_KEY_1003";
        InitiatePaymentRequest request = new InitiatePaymentRequest(key, "USR_ALICE", new BigDecimal("150.00"), "USD", "CREDIT_CARD", "Test");

        IdempotencyKeyRecord existing = new IdempotencyKeyRecord(key, idempotencyService.computePayloadHash(request));
        existing.setStatus(IdempotencyKeyRecord.Status.COMPLETED);
        existing.setResponseBody("{\"paymentId\":\"PAY_123\",\"status\":\"SUCCESS\"}");

        when(idempotencyKeyRepository.findByKeyValue(key)).thenReturn(Optional.of(existing));

        Optional<IdempotencyKeyRecord> result = idempotencyService.tryAcquireOrGet(key, request);

        assertTrue(result.isPresent());
        assertEquals(IdempotencyKeyRecord.Status.COMPLETED, result.get().getStatus());
        verify(metricsService, times(1)).recordIdempotencyHit();
    }

    @Test
    @DisplayName("Should throw PayloadMismatchException when same key is used with different payload")
    void testTryAcquire_PayloadMismatch_ThrowsException() {
        String key = "IDEM_KEY_MISMATCH";
        InitiatePaymentRequest originalRequest = new InitiatePaymentRequest(key, "USR_ALICE", new BigDecimal("150.00"), "USD", "CREDIT_CARD", "Test");
        InitiatePaymentRequest conflictingRequest = new InitiatePaymentRequest(key, "USR_ALICE", new BigDecimal("999.00"), "USD", "CREDIT_CARD", "Test Conflicting Payload");

        String originalHash = idempotencyService.computePayloadHash(originalRequest);

        IdempotencyKeyRecord existing = new IdempotencyKeyRecord(key, originalHash);
        existing.setStatus(IdempotencyKeyRecord.Status.COMPLETED);
        existing.setResponseBody("{\"paymentId\":\"PAY_123\",\"status\":\"SUCCESS\"}");

        when(idempotencyKeyRepository.findByKeyValue(key)).thenReturn(Optional.of(existing));

        assertThrows(com.paymentsystem.exception.PayloadMismatchException.class, () -> {
            idempotencyService.tryAcquireOrGet(key, conflictingRequest);
        });

        verify(metricsService, times(1)).recordIdempotencyHit();
    }

    @Test
    @DisplayName("Should atomically acquire expired key when updateIfExpired returns 1")
    void testTryAcquire_ExpiredKey_AtomicUpdate_Success() {
        String key = "IDEM_KEY_EXPIRED";
        InitiatePaymentRequest request = new InitiatePaymentRequest(key, "USR_ALICE", new BigDecimal("200.00"), "USD", "CREDIT_CARD", "Reused expired key");

        IdempotencyKeyRecord expiredRecord = new IdempotencyKeyRecord(key, "old_hash");
        expiredRecord.setStatus(IdempotencyKeyRecord.Status.COMPLETED);
        expiredRecord.setExpiresAt(java.time.Instant.now().minus(java.time.Duration.ofHours(2)));

        when(idempotencyKeyRepository.findByKeyValue(key)).thenReturn(Optional.of(expiredRecord));
        when(idempotencyKeyRepository.updateIfExpired(eq(key), anyString(), any(), any(), eq(IdempotencyKeyRecord.Status.IN_PROGRESS)))
                .thenReturn(1);

        Optional<IdempotencyKeyRecord> result = idempotencyService.tryAcquireOrGet(key, request);

        assertTrue(result.isEmpty(), "Successful atomic acquisition must return empty to permit execution");
        verify(idempotencyKeyRepository, times(1)).updateIfExpired(eq(key), anyString(), any(), any(), eq(IdempotencyKeyRecord.Status.IN_PROGRESS));
    }

    @Test
    @DisplayName("Should throw DuplicateRequestException when expired key update race is lost and key is IN_PROGRESS")
    void testTryAcquire_ExpiredKey_UpdateLost_ThrowsDuplicateException() {
        String key = "IDEM_KEY_EXPIRED_RACE";
        InitiatePaymentRequest request = new InitiatePaymentRequest(key, "USR_ALICE", new BigDecimal("200.00"), "USD", "CREDIT_CARD", "Race on expired key");
        String hash = idempotencyService.computePayloadHash(request);

        IdempotencyKeyRecord expiredRecord = new IdempotencyKeyRecord(key, hash);
        expiredRecord.setStatus(IdempotencyKeyRecord.Status.COMPLETED);
        expiredRecord.setExpiresAt(java.time.Instant.now().minus(java.time.Duration.ofHours(2)));

        IdempotencyKeyRecord inProgressByWinner = new IdempotencyKeyRecord(key, hash);
        inProgressByWinner.setStatus(IdempotencyKeyRecord.Status.IN_PROGRESS);

        when(idempotencyKeyRepository.findByKeyValue(key))
                .thenReturn(Optional.of(expiredRecord))
                .thenReturn(Optional.of(inProgressByWinner));
        when(idempotencyKeyRepository.updateIfExpired(eq(key), anyString(), any(), any(), eq(IdempotencyKeyRecord.Status.IN_PROGRESS)))
                .thenReturn(0);

        assertThrows(DuplicateRequestException.class, () -> {
            idempotencyService.tryAcquireOrGet(key, request);
        });

        verify(metricsService, times(1)).recordIdempotencyHit();
    }

    @Test
    @DisplayName("Should call repository to delete expired keys excluding IN_PROGRESS status")
    void testCleanupExpiredKeys() {
        java.time.Instant now = java.time.Instant.now();
        when(idempotencyKeyRepository.deleteExpiredKeysExcludingStatus(any(), eq(IdempotencyKeyRecord.Status.IN_PROGRESS)))
                .thenReturn(5);

        int deleted = idempotencyService.cleanupExpiredKeys(now);

        assertEquals(5, deleted);
        verify(idempotencyKeyRepository, times(1)).deleteExpiredKeysExcludingStatus(now, IdempotencyKeyRecord.Status.IN_PROGRESS);
    }
}
