package com.paymentsystem.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsystem.exception.DuplicateRequestException;
import com.paymentsystem.exception.PayloadMismatchException;
import com.paymentsystem.payment.PaymentStatus;
import com.paymentsystem.payment.PaymentTransaction;
import com.paymentsystem.payment.PaymentTransactionRepository;
import com.paymentsystem.service.MetricsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("IdempotencyService Unit Tests")
class IdempotencyServiceUnitTest {

    @Mock
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Mock
    private MetricsService metricsService;

    @Mock
    private PaymentTransactionRepository paymentRepository;

    private ObjectMapper objectMapper;
    private Clock fixedClock;
    private IdempotencyServiceImpl idempotencyService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        fixedClock = Clock.fixed(Instant.parse("2026-11-16T12:00:00Z"), ZoneId.of("UTC"));
        idempotencyService = new IdempotencyServiceImpl(
                idempotencyKeyRepository,
                objectMapper,
                metricsService,
                fixedClock,
                paymentRepository
        );
    }

    @Test
    @DisplayName("computePayloadHash falls back to hash code on serialization exception")
    void testComputePayloadHash_ExceptionFallback() {
        ObjectMapper throwingMapper = mock(ObjectMapper.class);
        try {
            when(throwingMapper.writeValueAsString(any())).thenThrow(new RuntimeException("Simulated json error"));
        } catch (JsonProcessingException ignored) {}

        IdempotencyServiceImpl service = new IdempotencyServiceImpl(
                idempotencyKeyRepository,
                throwingMapper,
                metricsService,
                fixedClock,
                paymentRepository
        );

        String payload = "test payload";
        String hash = service.computePayloadHash(payload);
        assertNotNull(hash);
        assertEquals(Integer.toHexString(payload.hashCode()), hash);
    }

    @Test
    @DisplayName("tryAcquireOrGet returns empty when keyValue is null or blank")
    void testTryAcquireOrGet_NullOrBlank() {
        assertTrue(idempotencyService.tryAcquireOrGet(null, "payload").isEmpty());
        assertTrue(idempotencyService.tryAcquireOrGet("", "payload").isEmpty());
        assertTrue(idempotencyService.tryAcquireOrGet("apiKey", "   ", "payload").isEmpty());
    }

    @Test
    @DisplayName("tryAcquireOrGet saves new key when not found")
    void testTryAcquireOrGet_BrandNewKey() {
        when(idempotencyKeyRepository.findByApiKeyAndKeyValue("key1", "IDEM_1")).thenReturn(Optional.empty());

        Optional<IdempotencyKeyRecord> result = idempotencyService.tryAcquireOrGet("key1", "IDEM_1", Map.of("amount", 100));
        assertTrue(result.isEmpty());
        verify(idempotencyKeyRepository).saveAndFlush(any(IdempotencyKeyRecord.class));
    }

    @Test
    @DisplayName("tryAcquireOrGet throws DuplicateRequestException on concurrent race condition insert")
    void testTryAcquireOrGet_RaceConditionOnInsert() {
        when(idempotencyKeyRepository.findByApiKeyAndKeyValue("key1", "IDEM_1")).thenReturn(Optional.empty());
        doThrow(new DataIntegrityViolationException("Duplicate key")).when(idempotencyKeyRepository).saveAndFlush(any());

        assertThrows(DuplicateRequestException.class, () ->
                idempotencyService.tryAcquireOrGet("key1", "IDEM_1", Map.of("amount", 100)));
        verify(metricsService).recordIdempotencyHit();
    }

    @Test
    @DisplayName("tryAcquireOrGet returns cached response for completed unexpired key")
    void testTryAcquireOrGet_CompletedCached() {
        Instant now = Instant.now(fixedClock);
        String hash = idempotencyService.computePayloadHash(Map.of("amount", 100));
        IdempotencyKeyRecord record = new IdempotencyKeyRecord("key1", "IDEM_1", hash, now.minusSeconds(60), now.plusSeconds(3600));
        record.setStatus(IdempotencyKeyRecord.Status.COMPLETED);
        record.setResponseBody("{\"status\":\"SUCCESS\"}");
        record.setHttpStatusCode(201);

        when(idempotencyKeyRepository.findByApiKeyAndKeyValue("key1", "IDEM_1")).thenReturn(Optional.of(record));

        Optional<IdempotencyKeyRecord> result = idempotencyService.tryAcquireOrGet("key1", "IDEM_1", Map.of("amount", 100));
        assertTrue(result.isPresent());
        assertEquals("{\"status\":\"SUCCESS\"}", result.get().getResponseBody());
        verify(metricsService).recordIdempotencyHit();
    }

    @Test
    @DisplayName("tryAcquireOrGet throws PayloadMismatchException when unexpired payload hash differs")
    void testTryAcquireOrGet_PayloadMismatch() {
        Instant now = Instant.now(fixedClock);
        IdempotencyKeyRecord record = new IdempotencyKeyRecord("key1", "IDEM_1", "different_hash", now.minusSeconds(60), now.plusSeconds(3600));

        when(idempotencyKeyRepository.findByApiKeyAndKeyValue("key1", "IDEM_1")).thenReturn(Optional.of(record));

        assertThrows(PayloadMismatchException.class, () ->
                idempotencyService.tryAcquireOrGet("key1", "IDEM_1", Map.of("amount", 100)));
        verify(metricsService).recordIdempotencyHit();
    }

    @Test
    @DisplayName("tryAcquireOrGet throws DuplicateRequestException when unexpired key is IN_PROGRESS")
    void testTryAcquireOrGet_InProgress() {
        Instant now = Instant.now(fixedClock);
        String hash = idempotencyService.computePayloadHash(Map.of("amount", 100));
        IdempotencyKeyRecord record = new IdempotencyKeyRecord("key1", "IDEM_1", hash, now.minusSeconds(60), now.plusSeconds(3600));

        when(idempotencyKeyRepository.findByApiKeyAndKeyValue("key1", "IDEM_1")).thenReturn(Optional.of(record));

        assertThrows(DuplicateRequestException.class, () ->
                idempotencyService.tryAcquireOrGet("key1", "IDEM_1", Map.of("amount", 100)));
        verify(metricsService).recordIdempotencyHit();
    }

    @Test
    @DisplayName("tryAcquireOrGet reuses expired key when atomic update succeeds")
    void testTryAcquireOrGet_ExpiredKeyReused() {
        Instant now = Instant.now(fixedClock);
        String hash = idempotencyService.computePayloadHash(Map.of("amount", 100));
        IdempotencyKeyRecord expiredRecord = new IdempotencyKeyRecord("key1", "IDEM_1", hash, now.minusSeconds(7200), now.minusSeconds(3600));

        when(idempotencyKeyRepository.findByApiKeyAndKeyValue("key1", "IDEM_1")).thenReturn(Optional.of(expiredRecord));
        when(idempotencyKeyRepository.updateIfExpiredScoped(eq("key1"), eq("IDEM_1"), any(), eq(now), any(), eq(IdempotencyKeyRecord.Status.IN_PROGRESS)))
                .thenReturn(1);

        Optional<IdempotencyKeyRecord> result = idempotencyService.tryAcquireOrGet("key1", "IDEM_1", Map.of("amount", 100));
        assertTrue(result.isEmpty()); // newly acquired
    }

    @Test
    @DisplayName("tryAcquireOrGet falls back to default updateIfExpired when apiKey is default")
    void testTryAcquireOrGet_ExpiredDefaultApiKey() {
        Instant now = Instant.now(fixedClock);
        String hash = idempotencyService.computePayloadHash(Map.of("amount", 100));
        IdempotencyKeyRecord expiredRecord = new IdempotencyKeyRecord("default", "IDEM_1", hash, now.minusSeconds(7200), now.minusSeconds(3600));

        when(idempotencyKeyRepository.findByApiKeyAndKeyValue("default", "IDEM_1")).thenReturn(Optional.of(expiredRecord));
        when(idempotencyKeyRepository.updateIfExpiredScoped(eq("default"), eq("IDEM_1"), any(), eq(now), any(), eq(IdempotencyKeyRecord.Status.IN_PROGRESS)))
                .thenReturn(0);
        when(idempotencyKeyRepository.updateIfExpired(eq("IDEM_1"), any(), eq(now), any(), eq(IdempotencyKeyRecord.Status.IN_PROGRESS)))
                .thenReturn(1);

        Optional<IdempotencyKeyRecord> result = idempotencyService.tryAcquireOrGet(null, "IDEM_1", Map.of("amount", 100));
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("tryAcquireOrGet handles lost race on expired key update")
    void testTryAcquireOrGet_ExpiredKeyLostRace() {
        Instant now = Instant.now(fixedClock);
        String hash = idempotencyService.computePayloadHash(Map.of("amount", 100));
        IdempotencyKeyRecord expiredRecord = new IdempotencyKeyRecord("key1", "IDEM_1", hash, now.minusSeconds(7200), now.minusSeconds(3600));

        when(idempotencyKeyRepository.findByApiKeyAndKeyValue("key1", "IDEM_1")).thenReturn(Optional.of(expiredRecord));
        when(idempotencyKeyRepository.updateIfExpiredScoped(eq("key1"), eq("IDEM_1"), any(), eq(now), any(), eq(IdempotencyKeyRecord.Status.IN_PROGRESS)))
                .thenReturn(0);

        // Another thread won and changed payload
        IdempotencyKeyRecord activeMismatch = new IdempotencyKeyRecord("key1", "IDEM_1", "mismatch_hash", now, now.plusSeconds(3600));
        when(idempotencyKeyRepository.findByApiKeyAndKeyValue("key1", "IDEM_1")).thenReturn(Optional.of(expiredRecord), Optional.of(activeMismatch));

        assertThrows(PayloadMismatchException.class, () ->
                idempotencyService.tryAcquireOrGet("key1", "IDEM_1", Map.of("amount", 100)));

        // Another thread won and is in progress
        IdempotencyKeyRecord activeInProgress = new IdempotencyKeyRecord("key1", "IDEM_1", hash, now, now.plusSeconds(3600));
        when(idempotencyKeyRepository.findByApiKeyAndKeyValue("key1", "IDEM_1")).thenReturn(Optional.of(expiredRecord), Optional.of(activeInProgress));

        assertThrows(DuplicateRequestException.class, () ->
                idempotencyService.tryAcquireOrGet("key1", "IDEM_1", Map.of("amount", 100)));

        // Another thread won and completed
        IdempotencyKeyRecord activeCompleted = new IdempotencyKeyRecord("key1", "IDEM_1", hash, now, now.plusSeconds(3600));
        activeCompleted.setStatus(IdempotencyKeyRecord.Status.COMPLETED);
        activeCompleted.setResponseBody("{\"cached\":true}");
        activeCompleted.setHttpStatusCode(200);
        when(idempotencyKeyRepository.findByApiKeyAndKeyValue("key1", "IDEM_1")).thenReturn(Optional.of(expiredRecord), Optional.of(activeCompleted));

        Optional<IdempotencyKeyRecord> cached = idempotencyService.tryAcquireOrGet("key1", "IDEM_1", Map.of("amount", 100));
        assertTrue(cached.isPresent());
    }

    @Test
    @DisplayName("markCompleted and markFailed operations")
    void testMarkCompletedAndFailed() {
        IdempotencyKeyRecord record = new IdempotencyKeyRecord("key1", "IDEM_1", "hash", Instant.now(), Instant.now().plusSeconds(3600));
        when(idempotencyKeyRepository.findByApiKeyAndKeyValue("key1", "IDEM_1")).thenReturn(Optional.of(record));

        idempotencyService.markCompleted("key1", "IDEM_1", Map.of("status", "OK"), 200);
        assertEquals(IdempotencyKeyRecord.Status.COMPLETED, record.getStatus());
        assertEquals("{\"status\":\"OK\"}", record.getResponseBody());
        assertEquals(200, record.getHttpStatusCode());

        // Object payload
        idempotencyService.markCompleted("key1", "IDEM_1", Map.of("key", "val"), 201);
        assertEquals(201, record.getHttpStatusCode());

        // Fail
        idempotencyService.markFailed("key1", "IDEM_1", "Network timeout");
        assertEquals(IdempotencyKeyRecord.Status.FAILED, record.getStatus());

        // Single-arg overloads
        when(idempotencyKeyRepository.findByApiKeyAndKeyValue("default", "IDEM_2")).thenReturn(Optional.of(record));
        idempotencyService.markCompleted("IDEM_2", "body", 200);
        idempotencyService.markFailed("IDEM_2", "fail");
    }

    @Test
    @DisplayName("cleanupExpiredKeys calls repository deletion")
    void testCleanupExpiredKeys() {
        when(idempotencyKeyRepository.deleteExpiredKeysExcludingStatus(any(), eq(IdempotencyKeyRecord.Status.IN_PROGRESS)))
                .thenReturn(5);

        int deleted = idempotencyService.cleanupExpiredKeys();
        assertEquals(5, deleted);
    }

    @Test
    @DisplayName("recoverStuckKeys when payments are SUCCESS, FAILED, and PENDING")
    void testRecoverStuckKeys_AllPaymentOutcomes() {
        Instant now = Instant.now(fixedClock);
        IdempotencyKeyRecord stuck1 = new IdempotencyKeyRecord("key1", "IDEM_STUCK_1", "hash1", now.minusSeconds(300), now.plusSeconds(3600));
        IdempotencyKeyRecord stuck2 = new IdempotencyKeyRecord("key1", "IDEM_STUCK_2", "hash2", now.minusSeconds(300), now.plusSeconds(3600));
        IdempotencyKeyRecord stuck3 = new IdempotencyKeyRecord("key1", "IDEM_STUCK_3", "hash3", now.minusSeconds(300), now.plusSeconds(3600));

        when(idempotencyKeyRepository.findStuckKeys(eq(IdempotencyKeyRecord.Status.IN_PROGRESS), any()))
                .thenReturn(List.of(stuck1, stuck2, stuck3));
        when(idempotencyKeyRepository.updateStuckKeyConditionally(any(), any(), any(), any(), anyInt(), any(), any()))
                .thenReturn(1);

        // Payment 1: SUCCESS
        PaymentTransaction pay1 = new PaymentTransaction("PAY_1", "IDEM_STUCK_1", "USR_1", 1000L, "USD", "CARD", "desc");
        pay1.transitionTo(PaymentStatus.PROCESSING);
        pay1.transitionTo(PaymentStatus.SUCCESS);
        when(paymentRepository.findByIdempotencyKey("IDEM_STUCK_1")).thenReturn(Optional.of(pay1));

        // Payment 2: FAILED
        PaymentTransaction pay2 = new PaymentTransaction("PAY_2", "IDEM_STUCK_2", "USR_1", 2000L, "USD", "CARD", "desc");
        pay2.transitionTo(PaymentStatus.FAILED);
        when(paymentRepository.findByIdempotencyKey("IDEM_STUCK_2")).thenReturn(Optional.of(pay2));

        // Payment 3: Not found
        when(paymentRepository.findByIdempotencyKey("IDEM_STUCK_3")).thenReturn(Optional.empty());

        int recovered = idempotencyService.recoverStuckKeys();
        assertEquals(3, recovered);

        verify(idempotencyKeyRepository).updateStuckKeyConditionally(
                eq("IDEM_STUCK_1"), eq(IdempotencyKeyRecord.Status.IN_PROGRESS), eq(IdempotencyKeyRecord.Status.COMPLETED), any(), eq(200), any(), any()
        );
        verify(idempotencyKeyRepository).updateStuckKeyConditionally(
                eq("IDEM_STUCK_2"), eq(IdempotencyKeyRecord.Status.IN_PROGRESS), eq(IdempotencyKeyRecord.Status.FAILED), any(), eq(500), any(), any()
        );
        verify(idempotencyKeyRepository).updateStuckKeyConditionally(
                eq("IDEM_STUCK_3"), eq(IdempotencyKeyRecord.Status.IN_PROGRESS), eq(IdempotencyKeyRecord.Status.FAILED), any(), eq(500), any(), any()
        );

        // Stuck payments sweep
        PaymentTransaction stuckPay1 = new PaymentTransaction("PAY_STUCK_1", "IDEM_ORPHAN_1", "USR_1", 500L, "USD", "CARD", "desc");
        stuckPay1.setCreatedAt(now.minusSeconds(600));
        PaymentTransaction stuckPay2 = new PaymentTransaction("PAY_STUCK_2", null, "USR_1", 500L, "USD", "CARD", "desc");
        stuckPay2.setCreatedAt(now.minusSeconds(600));
        when(paymentRepository.findStuckPayments(any(), any())).thenReturn(List.of(stuckPay1, stuckPay2));
        when(paymentRepository.findByPaymentIdForUpdate("PAY_STUCK_1")).thenReturn(Optional.of(stuckPay1));
        when(paymentRepository.findByPaymentIdForUpdate("PAY_STUCK_2")).thenReturn(Optional.of(stuckPay2));

        int totalSweep = idempotencyService.recoverStuckKeys();
        assertTrue(totalSweep >= 3);
        assertEquals(PaymentStatus.FAILED, stuckPay1.getStatus());
        assertEquals(PaymentStatus.FAILED, stuckPay2.getStatus());
    }

    @Test
    @DisplayName("markCompleted and markFailed handle null or blank keyValues and apiKeys")
    void testMarkCompletedAndFailed_Boundaries() {
        // null or blank keyValue returns early without exception
        idempotencyService.markCompleted(null, "body", 200);
        idempotencyService.markCompleted("", "body", 200);
        idempotencyService.markCompleted("   ", "body", 200);
        idempotencyService.markCompleted("key", null, "body", 200);
        idempotencyService.markCompleted("key", "  ", "body", 200);

        idempotencyService.markFailed(null, "error");
        idempotencyService.markFailed("", "error");
        idempotencyService.markFailed("   ", "error");
        idempotencyService.markFailed("key", null, "error");
        idempotencyService.markFailed("key", "  ", "error");

        // Blank apiKey defaults to "default"
        IdempotencyKeyRecord record = new IdempotencyKeyRecord("default", "IDEM_DEF", "h", Instant.now(), Instant.now().plusSeconds(3600));
        when(idempotencyKeyRepository.findByApiKeyAndKeyValue("default", "IDEM_DEF")).thenReturn(Optional.of(record));
        idempotencyService.markCompleted("", "IDEM_DEF", "{\"ok\":true}", 200);
        idempotencyService.markFailed("   ", "IDEM_DEF", "failed");
    }

    @Test
    @DisplayName("retentionHours getter and inProgressTimeout")
    void testRetentionHours() {
        assertEquals(24, idempotencyService.getRetentionHours());
        assertEquals(120, idempotencyService.getInProgressTimeoutSeconds());
    }

    @Test
    @DisplayName("IdempotencyKeyRecord comprehensive entity test covering all branches")
    void testIdempotencyKeyRecordEntity() {
        Instant now = Instant.now();
        IdempotencyKeyRecord record = new IdempotencyKeyRecord("key1", "IDEM_TEST", "hash", now, now.plusSeconds(3600));

        assertFalse(record.isExpired(now));
        assertTrue(record.isExpired(now.plusSeconds(7200)));

        IdempotencyKeyRecord recordNoExpiry = new IdempotencyKeyRecord("key1", "IDEM_TEST", "hash", now, null);
        assertFalse(recordNoExpiry.isExpired(now.plusSeconds(7200)));

        record.setStatus(IdempotencyKeyRecord.Status.FAILED);
        assertEquals(IdempotencyKeyRecord.Status.FAILED, record.getStatus());

        // Constructors: null, blank, custom apiKey
        IdempotencyKeyRecord r1 = new IdempotencyKeyRecord("KEY_V1", "HASH_1");
        assertEquals("default", r1.getApiKey());
        assertEquals("KEY_V1", r1.getKeyValue());

        IdempotencyKeyRecord r2 = new IdempotencyKeyRecord("KEY_V2", "HASH_2", now, now.plusSeconds(3600));
        assertEquals("default", r2.getApiKey());

        IdempotencyKeyRecord r3 = new IdempotencyKeyRecord(null, "KEY_V3", "HASH_3", now, now.plusSeconds(3600));
        assertEquals("default", r3.getApiKey());

        IdempotencyKeyRecord r4 = new IdempotencyKeyRecord("   ", "KEY_V4", "HASH_4", now, now.plusSeconds(3600));
        assertEquals("default", r4.getApiKey());

        // setApiKey branches
        r1.setApiKey(null);
        assertEquals("default", r1.getApiKey());
        r1.setApiKey("   ");
        assertEquals("default", r1.getApiKey());
        r1.setApiKey("tenant_123");
        assertEquals("tenant_123", r1.getApiKey());

        // Lifecycle annotations
        IdempotencyKeyRecord emptyRec = new IdempotencyKeyRecord();
        emptyRec.onCreate();
        assertNotNull(emptyRec.getCreatedAt());
        assertNotNull(emptyRec.getUpdatedAt());
        assertNotNull(emptyRec.getExpiresAt());

        emptyRec.onUpdate();
        assertNotNull(emptyRec.getUpdatedAt());

        // Getters and Setters
        emptyRec.setId(99L);
        assertEquals(99L, emptyRec.getId());
        emptyRec.setKeyValue("K");
        assertEquals("K", emptyRec.getKeyValue());
        emptyRec.setRequestHash("H");
        assertEquals("H", emptyRec.getRequestHash());
        emptyRec.setResponseBody("B");
        assertEquals("B", emptyRec.getResponseBody());
        emptyRec.setHttpStatusCode(201);
        assertEquals(201, emptyRec.getHttpStatusCode());
        emptyRec.setCreatedAt(now);
        assertEquals(now, emptyRec.getCreatedAt());
        emptyRec.setUpdatedAt(now);
        assertEquals(now, emptyRec.getUpdatedAt());
        emptyRec.setExpiresAt(now);
        assertEquals(now, emptyRec.getExpiresAt());
    }
}
