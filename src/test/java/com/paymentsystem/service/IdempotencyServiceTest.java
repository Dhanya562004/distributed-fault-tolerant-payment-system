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

        IdempotencyKeyRecord existing = new IdempotencyKeyRecord(key, "hash123");
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

        IdempotencyKeyRecord existing = new IdempotencyKeyRecord(key, "hash123");
        existing.setStatus(IdempotencyKeyRecord.Status.COMPLETED);
        existing.setResponseBody("{\"paymentId\":\"PAY_123\",\"status\":\"SUCCESS\"}");

        when(idempotencyKeyRepository.findByKeyValue(key)).thenReturn(Optional.of(existing));

        Optional<IdempotencyKeyRecord> result = idempotencyService.tryAcquireOrGet(key, request);

        assertTrue(result.isPresent());
        assertEquals(IdempotencyKeyRecord.Status.COMPLETED, result.get().getStatus());
        verify(metricsService, times(1)).recordIdempotencyHit();
    }
}
