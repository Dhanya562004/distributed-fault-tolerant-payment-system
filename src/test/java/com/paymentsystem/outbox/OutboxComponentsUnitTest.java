package com.paymentsystem.outbox;

import com.paymentsystem.dto.DeliveryResult;
import com.paymentsystem.payment.PaymentProcessingService;
import com.paymentsystem.service.MetricsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Outbox Components Unit Tests")
class OutboxComponentsUnitTest {

    @Mock
    private PaymentProcessingService paymentProcessingService;

    @Mock
    private HttpClient mockHttpClient;

    @Mock
    private HttpResponse<String> mockHttpResponse;

    @Mock
    private OutboxEventRepository mockOutboxRepo;

    @Mock
    private MetricsService mockMetricsService;

    @Test
    @DisplayName("JdkWebhookHttpClient sends POST request with headers and payload")
    void testJdkWebhookHttpClient() throws Exception {
        when(mockHttpResponse.statusCode()).thenReturn(200);
        when(mockHttpResponse.body()).thenReturn("{\"ok\":true}");
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockHttpResponse);

        JdkWebhookHttpClient client = new JdkWebhookHttpClient(mockHttpClient, Duration.ofSeconds(3));
        WebhookHttpClient.WebhookHttpResponse res = client.send(
                "https://api.example.com/webhook",
                Map.of("X-Custom", "Val"),
                "{\"event\":\"payment.succeeded\"}"
        );

        assertNotNull(res);
        assertEquals(200, res.getStatusCode());
        assertEquals("{\"ok\":true}", res.getBody());

        // Test with null headers
        WebhookHttpClient.WebhookHttpResponse resNullHeaders = client.send(
                "https://api.example.com/webhook",
                null,
                "{}"
        );
        assertEquals(200, resNullHeaders.getStatusCode());

        // Standard constructor
        JdkWebhookHttpClient defaultClient = new JdkWebhookHttpClient(3000, 5000);
        assertNotNull(defaultClient);
    }

    @Test
    @DisplayName("DistributedQueueManagerImpl publish, poll, and DLQ tracking")
    void testDistributedQueueManagerImpl() {
        DistributedQueueManagerImpl qm = new DistributedQueueManagerImpl();
        assertEquals(4, qm.getPartitionCount());

        assertEquals(0, qm.calculatePartition(null));
        int p = qm.calculatePartition("USER_123");
        assertTrue(p >= 0 && p < 4);

        EventMessage msg = new EventMessage("EVT_1", "PAY_1", "IDEM_1", "USER_123", 5000L, "USD", "CARD", "desc");
        assertTrue(qm.publish("USER_123", msg));
        assertTrue(qm.getTotalQueueDepth() > 0);

        EventMessage polled = qm.pollPartition(p);
        assertNotNull(polled);
        assertEquals("PAY_1", polled.getPaymentId());

        // DLQ test
        qm.sendToDeadLetterQueue(msg, "Simulated network timeout");
        assertEquals(1, qm.getDlqCount());
        assertEquals(1, qm.getDlqMessages().size());
        assertEquals("Simulated network timeout", qm.getDlqReason("PAY_1"));
        assertEquals("Max retry attempts exhausted", qm.getDlqReason("UNKNOWN"));

        // DLQ with nulls
        EventMessage nullMsg = new EventMessage();
        qm.sendToDeadLetterQueue(nullMsg, null);
    }

    @Test
    @DisplayName("PartitionedWorkerPool lifecycle and processing")
    void testPartitionedWorkerPoolLifecycle() {
        DistributedQueueManager qm = new DistributedQueueManagerImpl();
        EventMessage msg = new EventMessage("EVT_1", "PAY_1", "IDEM_1", "USER_1", 100L, "USD", "CARD", "desc");
        qm.publish("USER_1", msg);

        when(paymentProcessingService.processWorkerMessage(any(), any())).thenReturn(true);

        PartitionedWorkerPool pool = new PartitionedWorkerPool(qm, paymentProcessingService);
        pool.startWorkers();

        try {
            Thread.sleep(300);
        } catch (InterruptedException ignored) {}

        pool.stopWorkers();
    }

    @Test
    @DisplayName("PartitionedWorkerPool retry and DLQ routing on processing failure")
    void testPartitionedWorkerPoolRetryAndDlq() {
        DistributedQueueManager qm = new DistributedQueueManagerImpl();
        EventMessage msg = new EventMessage("EVT_2", "PAY_2", "IDEM_2", "USER_2", 200L, "USD", "CARD", "desc");
        msg.setAttemptCount(2); // Next is attempt 3 (exceeded MAX_WORKER_RETRIES)
        qm.publish("USER_2", msg);

        when(paymentProcessingService.processWorkerMessage(any(), any())).thenReturn(false);

        PartitionedWorkerPool pool = new PartitionedWorkerPool(qm, paymentProcessingService);
        pool.startWorkers();

        try {
            Thread.sleep(300);
        } catch (InterruptedException ignored) {}

        pool.stopWorkers();
        assertTrue(qm.getDlqCount() > 0);
    }

    @Test
    @DisplayName("EventMessage entity constructors, getters, setters")
    void testEventMessageEntity() {
        EventMessage msg = new EventMessage("EVT_1", "PAY_1", "IDEM_1", "USR_1", 100L, "USD", "CARD", "desc");
        msg.setAttemptCount(1);
        msg.setNextRetryTimestamp(1000L);

        assertEquals("EVT_1", msg.getEventId());
        assertEquals("PAY_1", msg.getPaymentId());
        assertEquals("USR_1", msg.getUserId());
        assertEquals(100L, msg.getAmount());
        assertEquals("USD", msg.getCurrency());
        assertEquals(1, msg.getAttemptCount());
        assertEquals(1000L, msg.getNextRetryTimestamp());

        EventMessage emptyMsg = new EventMessage();
        emptyMsg.setEventId("EVT_2");
        emptyMsg.setPaymentId("PAY_2");
        emptyMsg.setUserId("USR_2");
        emptyMsg.setAmount(500L);
        emptyMsg.setCurrency("EUR");
        assertNotNull(emptyMsg.toString());
    }

    @Test
    @DisplayName("OutboxEvent entity methods and constructors")
    void testOutboxEventEntity() {
        Instant now = Instant.now();
        OutboxEvent event = new OutboxEvent("EVT_1", "payment.succeeded", "AGG_1", "PAYLOAD", 5, now);

        assertEquals("EVT_1", event.getEventId());
        assertEquals("payment.succeeded", event.getEventType());
        assertEquals("AGG_1", event.getAggregateId());
        assertEquals("PAYLOAD", event.getPayload());
        assertEquals(5, event.getMaxAttempts());
        assertEquals(OutboxStatus.PENDING, event.getStatus());

        event.setId(101L);
        event.setStatus(OutboxStatus.PROCESSING);
        event.setLeaseOwner("worker-1");
        event.setLockedUntil(now.plusSeconds(60));
        event.setLastError("Some error");
        event.setNextAttemptAt(now.plusSeconds(30));
        event.setDeliveredAt(now.plusSeconds(10));
        event.setRequestId("REQ-123");

        assertEquals(101L, event.getId());
        assertEquals(OutboxStatus.PROCESSING, event.getStatus());
        assertEquals("worker-1", event.getLeaseOwner());
        assertEquals("Some error", event.getLastError());
        assertEquals("REQ-123", event.getRequestId());
        assertNotNull(event.getDeliveredAt());
        assertNotNull(event.getLockedUntil());
        assertNotNull(event.getNextAttemptAt());

        // Test constructor with null timestamp
        OutboxEvent eventNullTime = new OutboxEvent("EVT_2", "payment.failed", "AGG_2", "{}", 3, null);
        assertNotNull(eventNullTime.getCreatedAt());
        assertNotNull(eventNullTime.getUpdatedAt());
        assertNotNull(eventNullTime.getNextAttemptAt());
    }

    @Test
    @DisplayName("WebhookRetryPolicy calculations, status code and exception classifications")
    void testWebhookRetryPolicy() {
        // baseDelay <= 0
        assertEquals(0, WebhookRetryPolicy.calculateBackoff(1, 0, 1000, 0.1));
        assertEquals(0, WebhookRetryPolicy.calculateBackoff(1, -500, 1000, 0.1));

        // Normal exponential backoff with deterministic jitter
        long delay1 = WebhookRetryPolicy.calculateBackoff(1, 1000, 60000, 0.0);
        assertEquals(1000, delay1);
        long delay2 = WebhookRetryPolicy.calculateBackoff(2, 1000, 60000, 0.0);
        assertEquals(2000, delay2);
        long delay3 = WebhookRetryPolicy.calculateBackoff(3, 1000, 60000, 0.0);
        assertEquals(4000, delay3);

        // Cap at maxDelay
        long delayCapped = WebhookRetryPolicy.calculateBackoff(10, 1000, 5000, 0.0);
        assertEquals(5000, delayCapped);

        // Overflow condition handling
        long delayOverflow = WebhookRetryPolicy.calculateBackoff(30, Long.MAX_VALUE / 2, 60000, 0.0);
        assertEquals(60000, delayOverflow);

        // Jitter clamping
        long delayWithJitter = WebhookRetryPolicy.calculateBackoff(1, 1000, 60000, 0.2);
        assertEquals(1200, delayWithJitter);

        // Random jitter overload
        long randomBackoff = WebhookRetryPolicy.calculateBackoff(2, 1000, 10000);
        assertTrue(randomBackoff >= 2000 && randomBackoff <= 10000);

        // Retryable HTTP status codes
        assertTrue(WebhookRetryPolicy.isRetryableStatusCode(429));
        assertTrue(WebhookRetryPolicy.isRetryableStatusCode(500));
        assertTrue(WebhookRetryPolicy.isRetryableStatusCode(502));
        assertTrue(WebhookRetryPolicy.isRetryableStatusCode(503));
        assertTrue(WebhookRetryPolicy.isRetryableStatusCode(504));
        assertFalse(WebhookRetryPolicy.isRetryableStatusCode(200));
        assertFalse(WebhookRetryPolicy.isRetryableStatusCode(204));
        assertFalse(WebhookRetryPolicy.isRetryableStatusCode(400));
        assertFalse(WebhookRetryPolicy.isRetryableStatusCode(401));
        assertFalse(WebhookRetryPolicy.isRetryableStatusCode(404));
        assertFalse(WebhookRetryPolicy.isRetryableStatusCode(422));
        assertFalse(WebhookRetryPolicy.isRetryableStatusCode(600));

        // Retryable exceptions
        assertFalse(WebhookRetryPolicy.isRetryableException(null));
        assertTrue(WebhookRetryPolicy.isRetryableException(new SocketTimeoutException("timeout")));
        assertTrue(WebhookRetryPolicy.isRetryableException(new ConnectException("refused")));
        assertTrue(WebhookRetryPolicy.isRetryableException(new IOException("broken pipe")));

        class DummyHttpTimeoutException extends Exception {}
        assertTrue(WebhookRetryPolicy.isRetryableException(new DummyHttpTimeoutException()));

        class DummyHttpConnectTimeoutException extends Exception {}
        assertTrue(WebhookRetryPolicy.isRetryableException(new DummyHttpConnectTimeoutException()));

        assertFalse(WebhookRetryPolicy.isRetryableException(new SecurityException("forbidden")));
        assertFalse(WebhookRetryPolicy.isRetryableException(new IllegalArgumentException("invalid")));
        assertFalse(WebhookRetryPolicy.isRetryableException(new RuntimeException("general")));
    }

    @Test
    @DisplayName("WebhookSignatureService signing, verification, and boundary tests")
    void testWebhookSignatureServiceComprehensive() {
        WebhookSignatureService sigService = new WebhookSignatureService("whsec_test_secret_12345", 300, Clock.systemUTC());
        assertTrue(sigService.isSecretConfigured());
        assertEquals("whsec_test_secret_12345", sigService.getSigningSecret());
        assertEquals(300, sigService.getSignatureToleranceSeconds());

        long now = Instant.now().getEpochSecond();
        String payload = "{\"event\":\"payment.succeeded\",\"amount\":1000}";

        String signature = sigService.sign(payload, now);
        assertNotNull(signature);
        assertTrue(signature.startsWith("v1="));

        // Verification success
        assertTrue(sigService.verifySignature(payload, signature, String.valueOf(now), "whsec_test_secret_12345"));
        assertTrue(sigService.verifySignature(payload, signature, String.valueOf(now), "whsec_test_secret_12345", 300));

        // Tampered payload
        assertFalse(sigService.verifySignature("{\"event\":\"tampered\"}", signature, String.valueOf(now), "whsec_test_secret_12345"));

        // Expired timestamp (> tolerance)
        assertFalse(sigService.verifySignature(payload, signature, String.valueOf(now - 500), "whsec_test_secret_12345", 300));

        // Future timestamp (> tolerance)
        assertFalse(sigService.verifySignature(payload, signature, String.valueOf(now + 500), "whsec_test_secret_12345", 300));

        // Null and empty checks in sign()
        assertThrows(IllegalArgumentException.class, () -> sigService.sign(null, now, "secret"));
        assertThrows(IllegalArgumentException.class, () -> sigService.sign("payload", now, null));
        assertThrows(IllegalArgumentException.class, () -> sigService.sign("payload", now, "   "));

        // Null and empty checks in verifySignature()
        assertFalse(sigService.verifySignature(null, signature, String.valueOf(now), "secret"));
        assertFalse(sigService.verifySignature(payload, null, String.valueOf(now), "secret"));
        assertFalse(sigService.verifySignature(payload, signature, null, "secret"));
        assertFalse(sigService.verifySignature(payload, signature, String.valueOf(now), null));

        assertFalse(sigService.verifySignature(payload, "   ", String.valueOf(now), "secret"));
        assertFalse(sigService.verifySignature(payload, signature, "   ", "secret"));
        assertFalse(sigService.verifySignature(payload, signature, String.valueOf(now), "   "));
        assertFalse(sigService.verifySignature(payload, signature, "not_a_number", "secret"));

        // Constructor with null clock and null secret
        WebhookSignatureService fallbackService = new WebhookSignatureService(null, 300, null);
        assertFalse(fallbackService.isSecretConfigured());
        assertEquals("", fallbackService.getSigningSecret());
        assertThrows(IllegalStateException.class, () -> fallbackService.sign("payload", now));
    }

    @Test
    @DisplayName("WebhookSecurityValidator comprehensive SSRF checks")
    void testWebhookSecurityValidatorSSRF() throws Exception {
        WebhookSecurityValidator validator = new WebhookSecurityValidator(false);
        assertFalse(validator.isAllowPrivateDestinations());

        WebhookSecurityValidator lenientValidator = new WebhookSecurityValidator(true);
        assertTrue(lenientValidator.isAllowPrivateDestinations());
        assertDoesNotThrow(() -> lenientValidator.validateUrl("http://127.0.0.1:8080/webhook"));

        // Schemes
        assertThrows(IllegalArgumentException.class, () -> validator.validateUrl("ftp://example.com/webhook"));
        assertThrows(IllegalArgumentException.class, () -> validator.validateUrl("gopher://example.com/webhook"));
        assertThrows(IllegalArgumentException.class, () -> validator.validateUrl("file:///etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> validator.validateUrl(null));
        assertThrows(IllegalArgumentException.class, () -> validator.validateUrl(""));
        assertThrows(IllegalArgumentException.class, () -> validator.validateUrl("://bad-uri"));

        // HTTPS requirement in production
        assertThrows(SecurityException.class, () -> validator.validateUrl("http://example.com/webhook"));

        // Internal host names
        assertThrows(SecurityException.class, () -> validator.validateUrl("https://localhost/webhook"));
        assertThrows(SecurityException.class, () -> validator.validateUrl("https://sub.localhost/webhook"));
        assertThrows(SecurityException.class, () -> validator.validateUrl("https://service.internal/webhook"));
        assertThrows(SecurityException.class, () -> validator.validateUrl("https://device.local/webhook"));

        // Missing host
        assertThrows(IllegalArgumentException.class, () -> validator.validateUrl("https:///no-host"));

        // Unknown host
        assertThrows(IllegalArgumentException.class, () -> validator.validateUrl("https://non-existent-domain-49823749872394872.org/webhook"));

        // Direct IP validations
        assertDoesNotThrow(() -> validator.validateAddress(null));

        // IPv4 ranges
        assertThrows(SecurityException.class, () -> validator.validateAddress(InetAddress.getByName("127.0.0.1")));
        assertThrows(SecurityException.class, () -> validator.validateAddress(InetAddress.getByName("10.0.0.1")));
        assertThrows(SecurityException.class, () -> validator.validateAddress(InetAddress.getByName("172.16.0.1")));
        assertThrows(SecurityException.class, () -> validator.validateAddress(InetAddress.getByName("192.168.1.1")));
        assertThrows(SecurityException.class, () -> validator.validateAddress(InetAddress.getByName("169.254.169.254")));
        assertThrows(SecurityException.class, () -> validator.validateAddress(InetAddress.getByName("169.254.10.10")));
        assertThrows(SecurityException.class, () -> validator.validateAddress(InetAddress.getByName("0.0.0.0")));
        assertThrows(SecurityException.class, () -> validator.validateAddress(InetAddress.getByName("100.64.0.1")));
        assertThrows(SecurityException.class, () -> validator.validateAddress(InetAddress.getByName("255.255.255.255")));
        assertThrows(SecurityException.class, () -> validator.validateAddress(InetAddress.getByName("224.0.0.1")));

        // IPv6 ranges
        assertThrows(SecurityException.class, () -> validator.validateAddress(InetAddress.getByName("::1")));
        assertThrows(SecurityException.class, () -> validator.validateAddress(InetAddress.getByName("fc00::1")));
        assertThrows(SecurityException.class, () -> validator.validateAddress(InetAddress.getByName("fe80::1")));
        assertThrows(SecurityException.class, () -> validator.validateAddress(InetAddress.getByName("::ffff:127.0.0.1")));

        // Public IP addresses
        assertDoesNotThrow(() -> validator.validateAddress(InetAddress.getByName("8.8.8.8")));
        assertDoesNotThrow(() -> validator.validateAddress(InetAddress.getByName("::ffff:8.8.8.8")));
        assertDoesNotThrow(() -> validator.validateAddress(InetAddress.getByName("2001:4860:4860::8888")));

        // IPv6 with zero prefix but non-ff byte 10/11
        byte[] customIpv6 = new byte[16];
        customIpv6[14] = 0x12;
        customIpv6[15] = 0x34;
        assertDoesNotThrow(() -> validator.validateAddress(InetAddress.getByAddress(customIpv6)));
    }

    @Test
    @DisplayName("WebhookDeliveryService full dispatch paths and redelivery lifecycle")
    void testWebhookDeliveryServiceComprehensive() throws Exception {
        WebhookSignatureService sigService = new WebhookSignatureService("whsec_test_secret", 300);
        WebhookSecurityValidator validator = new WebhookSecurityValidator(true); // Allow local for test
        WebhookHttpClient mockHttp = mock(WebhookHttpClient.class);

        WebhookDeliveryService deliveryService = new WebhookDeliveryService(
                sigService, validator, mockHttp, mockOutboxRepo, Clock.systemUTC()
        );

        deliveryService.setEnabled(true);
        assertTrue(deliveryService.isEnabled());
        deliveryService.setDefaultDestinationUrl("http://127.0.0.1:8080/wh");
        assertEquals("http://127.0.0.1:8080/wh", deliveryService.getDefaultDestinationUrl());
        deliveryService.setSigningSecret("whsec_test_secret");
        assertEquals("whsec_test_secret", deliveryService.getSigningSecret());

        OutboxEvent event = new OutboxEvent("EVT-1", "payment.succeeded", "AGG-1", "{\"amount\":100}", 3, Instant.now());
        event.setRequestId("REQ-001");

        // 1. null event
        DeliveryResult resNull = deliveryService.deliver(null);
        assertFalse(resNull.isSuccess());
        assertFalse(resNull.isRetryable());

        // 2. null / empty targetUrl
        DeliveryResult resNullUrl = deliveryService.deliver(event, null);
        assertFalse(resNullUrl.isSuccess());
        DeliveryResult resEmptyUrl = deliveryService.deliver(event, "   ");
        assertFalse(resEmptyUrl.isSuccess());

        // 3. missing signing secret
        deliveryService.setSigningSecret(null);
        DeliveryResult resNoSecret = deliveryService.deliver(event, "http://127.0.0.1:8080/wh");
        assertFalse(resNoSecret.isSuccess());
        deliveryService.setSigningSecret("whsec_test_secret");

        // 4. SSRF block in production mode
        WebhookSecurityValidator prodValidator = new WebhookSecurityValidator(false);
        WebhookDeliveryService prodDeliveryService = new WebhookDeliveryService(
                sigService, prodValidator, mockHttp, mockOutboxRepo, null // Tests null clock fallback
        );
        prodDeliveryService.setSigningSecret("whsec_test_secret");
        DeliveryResult resSsrf = prodDeliveryService.deliver(event, "http://127.0.0.1:8080/wh");
        assertFalse(resSsrf.isSuccess());
        assertFalse(resSsrf.isRetryable());

        // 5. Malformed URL
        DeliveryResult resBadUrl = prodDeliveryService.deliver(event, "not_a_valid_url");
        assertFalse(resBadUrl.isSuccess());

        // 6. Signature exception
        WebhookSignatureService throwingSigService = mock(WebhookSignatureService.class);
        when(throwingSigService.sign(anyString(), anyLong(), anyString())).thenThrow(new RuntimeException("Crypto error"));
        WebhookDeliveryService errSigService = new WebhookDeliveryService(throwingSigService, validator, mockHttp, mockOutboxRepo, Clock.systemUTC());
        errSigService.setSigningSecret("whsec_test_secret");
        DeliveryResult resSigErr = errSigService.deliver(event, "http://127.0.0.1:8080/wh");
        assertFalse(resSigErr.isSuccess());
        assertFalse(resSigErr.isRetryable());

        // 7. HTTP client returns null
        when(mockHttp.send(anyString(), anyMap(), anyString())).thenReturn(null);
        DeliveryResult resHttpNull = deliveryService.deliver(event);
        assertFalse(resHttpNull.isSuccess());
        assertFalse(resHttpNull.isRetryable());

        // 8. HTTP client returns 200 OK
        when(mockHttp.send(anyString(), anyMap(), anyString()))
                .thenReturn(new WebhookHttpClient.WebhookHttpResponse(200, "{\"ok\":true}"));
        DeliveryResult resHttp200 = deliveryService.deliver(event);
        assertTrue(resHttp200.isSuccess());
        assertEquals(200, resHttp200.getStatusCode());

        // 9. HTTP client returns 503 Service Unavailable (Retryable)
        when(mockHttp.send(anyString(), anyMap(), anyString()))
                .thenReturn(new WebhookHttpClient.WebhookHttpResponse(503, "Unavailable"));
        DeliveryResult resHttp503 = deliveryService.deliver(event);
        assertFalse(resHttp503.isSuccess());
        assertTrue(resHttp503.isRetryable());

        // 10. HTTP client returns 400 Bad Request (Permanent)
        when(mockHttp.send(anyString(), anyMap(), anyString()))
                .thenReturn(new WebhookHttpClient.WebhookHttpResponse(400, "Bad Request"));
        DeliveryResult resHttp400 = deliveryService.deliver(event);
        assertFalse(resHttp400.isSuccess());
        assertFalse(resHttp400.isRetryable());

        // 11. HTTP client throws SocketTimeoutException (Retryable exception)
        doThrow(new SocketTimeoutException("Read timed out"))
                .when(mockHttp).send(anyString(), anyMap(), anyString());
        DeliveryResult resTimeout = deliveryService.deliver(event);
        assertFalse(resTimeout.isSuccess());
        assertTrue(resTimeout.isRetryable());

        // 12. HTTP client throws RuntimeException (Permanent exception)
        doThrow(new RuntimeException("Unexpected internal crash"))
                .when(mockHttp).send(anyString(), anyMap(), anyString());
        DeliveryResult resCrash = deliveryService.deliver(event);
        assertFalse(resCrash.isSuccess());
        assertFalse(resCrash.isRetryable());

        // 13. Redeliver dead letter event
        assertFalse(deliveryService.redeliverDeadLetterEvent(null));
        assertFalse(deliveryService.redeliverDeadLetterEvent("   "));

        when(mockOutboxRepo.resetDeadLetterEvent(eq("EVT-1"), any(Instant.class))).thenReturn(1);
        assertTrue(deliveryService.redeliverDeadLetterEvent("EVT-1"));

        when(mockOutboxRepo.resetDeadLetterEvent(eq("EVT-NOT-FOUND"), any(Instant.class))).thenReturn(0);
        assertFalse(deliveryService.redeliverDeadLetterEvent("EVT-NOT-FOUND"));

        // 14. getDeadLetterEvents
        when(mockOutboxRepo.findByStatus(OutboxStatus.DEAD_LETTER)).thenReturn(List.of(event));
        List<OutboxEvent> dlqList = deliveryService.getDeadLetterEvents();
        assertEquals(1, dlqList.size());
    }

    @Test
    @DisplayName("OutboxDeliveryWorker polling, claiming, state transitions, and shutdown")
    void testOutboxDeliveryWorkerLifecycle() {
        WebhookDeliveryService mockDeliveryService = mock(WebhookDeliveryService.class);
        OutboxDeliveryWorker worker = new OutboxDeliveryWorker(
                mockOutboxRepo, mockDeliveryService, mockMetricsService, Clock.systemUTC()
        );

        worker.setEnabled(true);
        assertTrue(worker.isEnabled());
        assertNotNull(worker.getWorkerId());
        worker.setBatchSize(10);
        worker.setLeaseDurationSeconds(30);
        worker.setBaseDelayMs(500);
        worker.setMaxDelayMs(5000);

        // When disabled
        worker.setEnabled(false);
        assertEquals(0, worker.processPendingEvents());
        worker.setEnabled(true);

        // When candidate IDs empty
        when(mockOutboxRepo.findCandidateEventIds(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of());
        assertEquals(0, worker.processPendingEvents());

        // When candidates exist
        when(mockOutboxRepo.findCandidateEventIds(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(101L));
        when(mockOutboxRepo.claimEvent(eq(101L), anyString(), any(Instant.class), any(Instant.class), eq(OutboxStatus.PROCESSING)))
                .thenReturn(1);

        OutboxEvent event = new OutboxEvent("EVT-101", "payment.succeeded", "AGG-101", "{}", 3, Instant.now());
        event.setId(101L);
        event.setRequestId("REQ-TRACE");
        when(mockOutboxRepo.findById(101L)).thenReturn(Optional.of(event));
        when(mockDeliveryService.deliver(any(OutboxEvent.class)))
                .thenReturn(DeliveryResult.success(200, "OK"));

        int processed = worker.processPendingEvents();
        assertEquals(1, processed);
        verify(mockMetricsService, atLeastOnce()).incrementWebhookSuccess();

        // claimAndDispatch when claimEvent returns 0
        when(mockOutboxRepo.claimEvent(eq(102L), anyString(), any(Instant.class), any(Instant.class), eq(OutboxStatus.PROCESSING)))
                .thenReturn(0);
        assertFalse(worker.claimAndDispatch(102L, Instant.now(), Instant.now().plusSeconds(30)));

        // claimAndDispatch when findById returns empty
        when(mockOutboxRepo.claimEvent(eq(103L), anyString(), any(Instant.class), any(Instant.class), eq(OutboxStatus.PROCESSING)))
                .thenReturn(1);
        when(mockOutboxRepo.findById(103L)).thenReturn(Optional.empty());
        assertFalse(worker.claimAndDispatch(103L, Instant.now(), Instant.now().plusSeconds(30)));

        // recordDeliveryResult: event not found
        when(mockOutboxRepo.findById(999L)).thenReturn(Optional.empty());
        assertDoesNotThrow(() -> worker.recordDeliveryResult(999L, DeliveryResult.success(200, "OK")));

        // recordDeliveryResult: duplicate DELIVERED event guard
        OutboxEvent deliveredEvent = new OutboxEvent("EVT-DEL", "payment.succeeded", "AGG-1", "{}", 3, Instant.now());
        deliveredEvent.setStatus(OutboxStatus.DELIVERED);
        when(mockOutboxRepo.findById(201L)).thenReturn(Optional.of(deliveredEvent));
        worker.recordDeliveryResult(201L, DeliveryResult.retryableFailure(503, "Err"));
        assertEquals(OutboxStatus.DELIVERED, deliveredEvent.getStatus()); // Untouched

        // recordDeliveryResult: stale worker guard (lease owner belongs to someone else)
        OutboxEvent leasedEvent = new OutboxEvent("EVT-LEASE", "payment.succeeded", "AGG-1", "{}", 3, Instant.now());
        leasedEvent.setStatus(OutboxStatus.PROCESSING);
        leasedEvent.setLeaseOwner("other-worker-id");
        when(mockOutboxRepo.findById(202L)).thenReturn(Optional.of(leasedEvent));
        worker.recordDeliveryResult(202L, DeliveryResult.retryableFailure(503, "Err"));
        assertEquals(OutboxStatus.PROCESSING, leasedEvent.getStatus()); // Untouched

        // recordDeliveryResult: retryable failure with attempts remaining (< maxAttempts)
        OutboxEvent retryEvent = new OutboxEvent("EVT-RETRY", "payment.succeeded", "AGG-1", "{}", 3, Instant.now());
        retryEvent.setAttemptCount(1);
        when(mockOutboxRepo.findById(203L)).thenReturn(Optional.of(retryEvent));
        worker.recordDeliveryResult(203L, DeliveryResult.retryableFailure(503, "Server Error"));
        assertEquals(OutboxStatus.PENDING, retryEvent.getStatus());
        assertEquals(2, retryEvent.getAttemptCount());
        verify(mockMetricsService, atLeastOnce()).incrementWebhookFailure();

        // recordDeliveryResult: retryable failure exhausting max attempts (-> DEAD_LETTER)
        OutboxEvent exhaustedEvent = new OutboxEvent("EVT-EXHAUST", "payment.succeeded", "AGG-1", "{}", 3, Instant.now());
        exhaustedEvent.setAttemptCount(2); // Next will be 3 == maxAttempts
        when(mockOutboxRepo.findById(204L)).thenReturn(Optional.of(exhaustedEvent));
        worker.recordDeliveryResult(204L, DeliveryResult.retryableFailure(503, "Server Error"));
        assertEquals(OutboxStatus.DEAD_LETTER, exhaustedEvent.getStatus());
        assertEquals(3, exhaustedEvent.getAttemptCount());
        verify(mockMetricsService, atLeastOnce()).incrementWebhookDlq();

        // recordDeliveryResult: permanent failure (-> DEAD_LETTER immediately)
        OutboxEvent permEvent = new OutboxEvent("EVT-PERM", "payment.succeeded", "AGG-1", "{}", 3, Instant.now());
        permEvent.setAttemptCount(0);
        when(mockOutboxRepo.findById(205L)).thenReturn(Optional.of(permEvent));
        worker.recordDeliveryResult(205L, DeliveryResult.permanentFailure(400, "Bad Request"));
        assertEquals(OutboxStatus.DEAD_LETTER, permEvent.getStatus());
        assertEquals(1, permEvent.getAttemptCount());

        // Constructor with null clock fallback
        OutboxDeliveryWorker fallbackWorker = new OutboxDeliveryWorker(mockOutboxRepo, mockDeliveryService, null, null);
        assertNotNull(fallbackWorker);

        // Graceful shutdown
        worker.shutdown();
    }
}
