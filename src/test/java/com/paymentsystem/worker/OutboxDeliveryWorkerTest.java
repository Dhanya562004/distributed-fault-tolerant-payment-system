package com.paymentsystem.worker;

import com.paymentsystem.dto.DeliveryResult;
import com.paymentsystem.entity.OutboxEvent;
import com.paymentsystem.entity.OutboxStatus;
import com.paymentsystem.repository.OutboxEventRepository;
import com.paymentsystem.service.MetricsService;
import com.paymentsystem.service.WebhookDeliveryService;
import com.paymentsystem.service.WebhookHttpClient;
import com.paymentsystem.service.WebhookSecurityValidator;
import com.paymentsystem.service.WebhookSignatureService;
import com.paymentsystem.util.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxDeliveryWorkerTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private WebhookHttpClient httpClient;

    @Mock
    private MetricsService metricsService;

    private MutableClock clock;
    private WebhookSignatureService signatureService;
    private WebhookSecurityValidator securityValidator;
    private WebhookDeliveryService deliveryService;
    private OutboxDeliveryWorker worker;

    private static final String SECRET = "whsec_test_secret_delivery_worker_999";
    private static final String DEST_URL = "https://merchant.example.com/webhooks";

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-19T12:00:00Z"));
        signatureService = new WebhookSignatureService(SECRET, 300, clock);
        securityValidator = new WebhookSecurityValidator(true); // allow test destinations
        deliveryService = new WebhookDeliveryService(signatureService, securityValidator, httpClient, outboxEventRepository, clock);
        deliveryService.setEnabled(true);
        deliveryService.setDefaultDestinationUrl(DEST_URL);
        deliveryService.setSigningSecret(SECRET);

        worker = new OutboxDeliveryWorker(outboxEventRepository, deliveryService, metricsService, clock);
        worker.setEnabled(true);
        worker.setBaseDelayMs(1000);
        worker.setMaxDelayMs(300000);
        worker.setLeaseDurationSeconds(60);
    }

    private OutboxEvent createSampleEvent(Long id, String eventId, int attemptCount, int maxAttempts) {
        OutboxEvent event = new OutboxEvent(eventId, "payment.succeeded", "PAY_123", "{\"amount\":100}", maxAttempts, clock.instant());
        event.setId(id);
        event.setAttemptCount(attemptCount);
        event.setStatus(OutboxStatus.PENDING);
        return event;
    }

    @Test
    @DisplayName("Successful delivery marks event DELIVERED and records deliveredAt timestamp")
    void testDelivery_Success_MarksDelivered() throws Exception {
        OutboxEvent event = createSampleEvent(1L, "evt_success_1", 0, 5);

        when(httpClient.send(eq(DEST_URL), any(), eq(event.getPayload())))
                .thenReturn(new WebhookHttpClient.WebhookHttpResponse(200, "{\"received\":true}"));
        when(outboxEventRepository.claimEvent(eq(1L), any(), any(), any(), eq(OutboxStatus.PROCESSING))).thenReturn(1);
        when(outboxEventRepository.findById(1L)).thenReturn(Optional.of(event));

        boolean claimed = worker.claimAndDispatch(1L, clock.instant(), clock.instant().plusSeconds(60));
        assertTrue(claimed);

        assertEquals(OutboxStatus.DELIVERED, event.getStatus());
        assertNotNull(event.getDeliveredAt());
        assertNull(event.getLastError());
        assertNull(event.getLockedUntil());
        verify(outboxEventRepository, atLeastOnce()).save(event);
    }

    @Test
    @DisplayName("HTTP 500 schedules a retry with incremented attempt count and backoff timestamp")
    void testDelivery_Http500_SchedulesRetry() throws Exception {
        OutboxEvent event = createSampleEvent(2L, "evt_retry_500", 0, 5);

        when(httpClient.send(eq(DEST_URL), any(), eq(event.getPayload())))
                .thenReturn(new WebhookHttpClient.WebhookHttpResponse(500, "Internal Server Error"));
        when(outboxEventRepository.claimEvent(eq(2L), any(), any(), any(), eq(OutboxStatus.PROCESSING))).thenReturn(1);
        when(outboxEventRepository.findById(2L)).thenReturn(Optional.of(event));

        boolean claimed = worker.claimAndDispatch(2L, clock.instant(), clock.instant().plusSeconds(60));
        assertTrue(claimed);

        assertEquals(OutboxStatus.PENDING, event.getStatus());
        assertEquals(1, event.getAttemptCount());
        assertTrue(event.getNextAttemptAt().isAfter(clock.instant()));
        assertTrue(event.getLastError().contains("500"));
        assertNull(event.getLockedUntil());
        verify(outboxEventRepository, atLeastOnce()).save(event);
    }

    @Test
    @DisplayName("HTTP 429 Rate Limit schedules a retry")
    void testDelivery_Http429_SchedulesRetry() throws Exception {
        OutboxEvent event = createSampleEvent(3L, "evt_retry_429", 1, 5);

        when(httpClient.send(eq(DEST_URL), any(), eq(event.getPayload())))
                .thenReturn(new WebhookHttpClient.WebhookHttpResponse(429, "Too Many Requests"));
        when(outboxEventRepository.claimEvent(eq(3L), any(), any(), any(), eq(OutboxStatus.PROCESSING))).thenReturn(1);
        when(outboxEventRepository.findById(3L)).thenReturn(Optional.of(event));

        boolean claimed = worker.claimAndDispatch(3L, clock.instant(), clock.instant().plusSeconds(60));
        assertTrue(claimed);

        assertEquals(OutboxStatus.PENDING, event.getStatus());
        assertEquals(2, event.getAttemptCount());
        assertTrue(event.getNextAttemptAt().isAfter(clock.instant()));
        verify(outboxEventRepository, atLeastOnce()).save(event);
    }

    @Test
    @DisplayName("Connection/Read timeout schedules a retry")
    void testDelivery_Timeout_SchedulesRetry() throws Exception {
        OutboxEvent event = createSampleEvent(4L, "evt_retry_timeout", 0, 5);

        when(httpClient.send(eq(DEST_URL), any(), eq(event.getPayload())))
                .thenThrow(new SocketTimeoutException("Read timed out"));
        when(outboxEventRepository.claimEvent(eq(4L), any(), any(), any(), eq(OutboxStatus.PROCESSING))).thenReturn(1);
        when(outboxEventRepository.findById(4L)).thenReturn(Optional.of(event));

        boolean claimed = worker.claimAndDispatch(4L, clock.instant(), clock.instant().plusSeconds(60));
        assertTrue(claimed);

        assertEquals(OutboxStatus.PENDING, event.getStatus());
        assertEquals(1, event.getAttemptCount());
        assertTrue(event.getLastError().contains("timed out"));
    }

    @Test
    @DisplayName("Permanent HTTP 400 transitions directly to DEAD_LETTER without retrying")
    void testDelivery_Http400_TransitionsToDeadLetter() throws Exception {
        OutboxEvent event = createSampleEvent(5L, "evt_permanent_400", 0, 5);

        when(httpClient.send(eq(DEST_URL), any(), eq(event.getPayload())))
                .thenReturn(new WebhookHttpClient.WebhookHttpResponse(400, "Bad Request: Unknown schema"));
        when(outboxEventRepository.claimEvent(eq(5L), any(), any(), any(), eq(OutboxStatus.PROCESSING))).thenReturn(1);
        when(outboxEventRepository.findById(5L)).thenReturn(Optional.of(event));

        boolean claimed = worker.claimAndDispatch(5L, clock.instant(), clock.instant().plusSeconds(60));
        assertTrue(claimed);

        assertEquals(OutboxStatus.DEAD_LETTER, event.getStatus());
        assertEquals(1, event.getAttemptCount());
        assertTrue(event.getLastError().contains("400"));
        assertNull(event.getLockedUntil());
    }

    @Test
    @DisplayName("Retry exhaustion (attempt count reaching max attempts) transitions event to DEAD_LETTER")
    void testDelivery_Exhaustion_TransitionsToDeadLetter() throws Exception {
        // Event is on attempt 4 out of 5 max attempts
        OutboxEvent event = createSampleEvent(6L, "evt_exhaust_dlq", 4, 5);

        when(httpClient.send(eq(DEST_URL), any(), eq(event.getPayload())))
                .thenReturn(new WebhookHttpClient.WebhookHttpResponse(503, "Service Unavailable"));
        when(outboxEventRepository.claimEvent(eq(6L), any(), any(), any(), eq(OutboxStatus.PROCESSING))).thenReturn(1);
        when(outboxEventRepository.findById(6L)).thenReturn(Optional.of(event));

        boolean claimed = worker.claimAndDispatch(6L, clock.instant(), clock.instant().plusSeconds(60));
        assertTrue(claimed);

        assertEquals(OutboxStatus.DEAD_LETTER, event.getStatus());
        assertEquals(5, event.getAttemptCount());
        assertTrue(event.getLastError().contains("503"));
    }

    @Test
    @DisplayName("Dead-letter event remains persisted and can be queried")
    void testDeadLetter_PersistedAndQueryable() {
        OutboxEvent deadEvent = createSampleEvent(7L, "evt_dlq_persisted", 5, 5);
        deadEvent.setStatus(OutboxStatus.DEAD_LETTER);
        deadEvent.setLastError("HTTP 500: Server crashed");

        when(outboxEventRepository.findByStatus(OutboxStatus.DEAD_LETTER)).thenReturn(List.of(deadEvent));

        List<OutboxEvent> deadLetterEvents = deliveryService.getDeadLetterEvents();
        assertNotNull(deadLetterEvents);
        assertEquals(1, deadLetterEvents.size());
        assertEquals("evt_dlq_persisted", deadLetterEvents.get(0).getEventId());
        assertEquals(OutboxStatus.DEAD_LETTER, deadLetterEvents.get(0).getStatus());
    }

    @Test
    @DisplayName("Manual redelivery preserves original event ID and resets status to PENDING")
    void testManualRedelivery_PreservesEventId() {
        when(outboxEventRepository.resetDeadLetterEvent(eq("evt_dlq_manual"), any())).thenReturn(1);

        boolean success = deliveryService.redeliverDeadLetterEvent("evt_dlq_manual");
        assertTrue(success);

        verify(outboxEventRepository, times(1)).resetDeadLetterEvent(eq("evt_dlq_manual"), any());
    }

    @Test
    @DisplayName("Failed claim race condition prevents duplicate execution by concurrent workers")
    void testClaimEvent_RaceCondition_Skipped() {
        // Another worker claims event first -> returns 0
        when(outboxEventRepository.claimEvent(eq(8L), any(), any(), any(), eq(OutboxStatus.PROCESSING))).thenReturn(0);

        boolean claimed = worker.claimAndDispatch(8L, clock.instant(), clock.instant().plusSeconds(60));
        assertFalse(claimed, "Should not process event when atomic claim returns 0");
        verifyNoInteractions(httpClient);
    }

    @Test
    @DisplayName("Worker polling claims pending candidate events")
    void testProcessPendingEvents_PicksUpCandidates() throws Exception {
        OutboxEvent event = createSampleEvent(9L, "evt_pending_poll", 0, 5);

        when(outboxEventRepository.findCandidateEventIds(any(), any())).thenReturn(List.of(9L));
        when(outboxEventRepository.claimEvent(eq(9L), any(), any(), any(), eq(OutboxStatus.PROCESSING))).thenReturn(1);
        when(outboxEventRepository.findById(9L)).thenReturn(Optional.of(event));
        when(httpClient.send(eq(DEST_URL), any(), eq(event.getPayload())))
                .thenReturn(new WebhookHttpClient.WebhookHttpResponse(200, "OK"));

        int processed = worker.processPendingEvents();
        assertEquals(1, processed);
        assertEquals(OutboxStatus.DELIVERED, event.getStatus());
    }
}
