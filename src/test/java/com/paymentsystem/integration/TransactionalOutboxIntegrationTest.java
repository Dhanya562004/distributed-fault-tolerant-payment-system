package com.paymentsystem.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsystem.dto.request.CreateUserRequest;
import com.paymentsystem.dto.request.InitiatePaymentRequest;
import com.paymentsystem.dto.request.ProcessRefundRequest;
import com.paymentsystem.dto.response.PaymentResponse;
import com.paymentsystem.dto.response.RefundResponse;
import com.paymentsystem.outbox.OutboxEvent;
import com.paymentsystem.outbox.OutboxStatus;
import com.paymentsystem.payment.PaymentStatus;
import com.paymentsystem.outbox.OutboxEventRepository;
import com.paymentsystem.payment.PaymentTransactionRepository;
import com.paymentsystem.outbox.WebhookDeliveryService;
import com.paymentsystem.outbox.WebhookHttpClient;
import com.paymentsystem.outbox.OutboxDeliveryWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
public class TransactionalOutboxIntegrationTest {

    private static final String API_KEY = "apexpay_admin_secret_key_2026";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private PaymentTransactionRepository paymentRepository;

    @Autowired
    private WebhookDeliveryService deliveryService;

    @Autowired
    private OutboxDeliveryWorker outboxWorker;

    @MockBean
    private WebhookHttpClient httpClient;

    @BeforeEach
    void setUp() throws Exception {
        outboxEventRepository.deleteAll();
        when(httpClient.send(any(), any(), any()))
                .thenReturn(new WebhookHttpClient.WebhookHttpResponse(200, "{\"ok\":true}"));
    }

    @Test
    @DisplayName("1. Successful payment atomically creates exactly one outbox event in same database transaction carrying requestId")
    void testSuccessfulPayment_CreatesOutboxEventAtomically() throws Exception {
        String userCode = "USR_OUTBOX_1_" + System.currentTimeMillis();
        String idempotencyKey = "IDEM_OUTBOX_1_" + System.currentTimeMillis();

        // Create user
        CreateUserRequest userReq = new CreateUserRequest(userCode, "Outbox User", userCode.toLowerCase() + "@test.com", 100000L);
        mockMvc.perform(post("/api/v1/users")
                .header("X-Api-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(userReq)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("X-Request-Id"));

        // Process payment
        InitiatePaymentRequest payReq = new InitiatePaymentRequest(idempotencyKey, userCode, 15000L, "USD", "CREDIT_CARD", "Outbox Test Payment");
        MvcResult payResult = mockMvc.perform(post("/api/v1/payments")
                .header("X-Api-Key", API_KEY)
                .header("X-Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payReq)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andReturn();

        PaymentResponse payment = objectMapper.readValue(payResult.getResponse().getContentAsString(), PaymentResponse.class);
        assertNotNull(payment.getPaymentId());

        // Verify exactly one outbox event was created in the database
        List<OutboxEvent> events = outboxEventRepository.findByAggregateId(payment.getPaymentId());
        assertEquals(1, events.size(), "A successful payment must create exactly one outbox event");

        OutboxEvent event = events.get(0);
        assertEquals("payment.succeeded", event.getEventType());
        assertEquals(payment.getPaymentId(), event.getAggregateId());
        assertEquals(OutboxStatus.PENDING, event.getStatus());
        assertEquals(0, event.getAttemptCount());
        assertNotNull(event.getRequestId(), "Outbox event must carry the requestId");
        assertTrue(event.getPayload().contains(payment.getPaymentId()));
        assertTrue(event.getPayload().contains("payment.succeeded"));
    }

    @Test
    @DisplayName("2. Cached idempotency replay creates NO duplicate outbox events")
    void testCachedPaymentReplay_ProducesNoDuplicateOutboxEvents() throws Exception {
        String userCode = "USR_OUTBOX_2_" + System.currentTimeMillis();
        String idempotencyKey = "IDEM_OUTBOX_2_" + System.currentTimeMillis();

        // Create user
        CreateUserRequest userReq = new CreateUserRequest(userCode, "Outbox User 2", userCode.toLowerCase() + "@test.com", 100000L);
        mockMvc.perform(post("/api/v1/users")
                .header("X-Api-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(userReq)))
                .andExpect(status().isCreated());

        // Initial payment
        InitiatePaymentRequest payReq = new InitiatePaymentRequest(idempotencyKey, userCode, 20000L, "USD", "CREDIT_CARD", "Initial payment");
        MvcResult payResult = mockMvc.perform(post("/api/v1/payments")
                .header("X-Api-Key", API_KEY)
                .header("X-Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payReq)))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andReturn();

        PaymentResponse payment = objectMapper.readValue(payResult.getResponse().getContentAsString(), PaymentResponse.class);

        // Cached replay
        mockMvc.perform(post("/api/v1/payments")
                .header("X-Api-Key", API_KEY)
                .header("X-Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payReq)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.paymentId").value(payment.getPaymentId()));

        // Outbox event count must remain exactly 1
        List<OutboxEvent> events = outboxEventRepository.findByAggregateId(payment.getPaymentId());
        assertEquals(1, events.size(), "Cached idempotency replay must not create duplicate outbox events");
    }

    @Test
    @DisplayName("3. Rolled-back payment produces NO committed outbox event")
    void testRolledBackPayment_ProducesNoCommittedOutboxEvent() throws Exception {
        String userCode = "USR_OUTBOX_3_" + System.currentTimeMillis();
        String idempotencyKey = "IDEM_OUTBOX_3_" + System.currentTimeMillis();

        // Submit payment with invalid negative amount -> 400 Bad Request
        InitiatePaymentRequest payReq = new InitiatePaymentRequest(idempotencyKey, userCode, -10000L, "USD", "CREDIT_CARD", "Negative");
        mockMvc.perform(post("/api/v1/payments")
                .header("X-Api-Key", API_KEY)
                .header("X-Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payReq)))
                .andExpect(status().isBadRequest());

        // Zero outbox events must exist
        assertEquals(0, outboxEventRepository.count(), "Failed/rolled-back payment requests must commit no outbox events");
    }

    @Test
    @DisplayName("4. Successful refund creates refund.succeeded outbox event atomically")
    void testSuccessfulRefund_CreatesRefundOutboxEvent() throws Exception {
        String userCode = "USR_OUTBOX_4_" + System.currentTimeMillis();
        String payIdemKey = "IDEM_PAY_4_" + System.currentTimeMillis();
        String refIdemKey = "IDEM_REF_4_" + System.currentTimeMillis();

        // Create user
        CreateUserRequest userReq = new CreateUserRequest(userCode, "Refund Customer", userCode.toLowerCase() + "@test.com", 50000L);
        mockMvc.perform(post("/api/v1/users")
                .header("X-Api-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(userReq)))
                .andExpect(status().isCreated());

        // Payment
        InitiatePaymentRequest payReq = new InitiatePaymentRequest(payIdemKey, userCode, 10000L, "USD", "CREDIT_CARD", "Payment to refund");
        MvcResult payResult = mockMvc.perform(post("/api/v1/payments")
                .header("X-Api-Key", API_KEY)
                .header("X-Idempotency-Key", payIdemKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payReq)))
                .andExpect(status().isCreated())
                .andReturn();

        PaymentResponse payment = objectMapper.readValue(payResult.getResponse().getContentAsString(), PaymentResponse.class);

        // Process refund
        ProcessRefundRequest refundReq = new ProcessRefundRequest(payment.getPaymentId(), refIdemKey, 5000L, "Item returned");
        MvcResult refundResult = mockMvc.perform(post("/api/v1/refunds")
                .header("X-Api-Key", API_KEY)
                .header("X-Idempotency-Key", refIdemKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(refundReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andReturn();

        RefundResponse refund = objectMapper.readValue(refundResult.getResponse().getContentAsString(), RefundResponse.class);
        assertNotNull(refund.getRefundId());

        // Check refund outbox event
        List<OutboxEvent> refundEvents = outboxEventRepository.findByAggregateId(refund.getRefundId());
        assertEquals(1, refundEvents.size(), "Committed refund must create exactly one refund.succeeded outbox event");
        assertEquals("refund.succeeded", refundEvents.get(0).getEventType());
        assertNotNull(refundEvents.get(0).getRequestId(), "Refund outbox event must carry the requestId");
        assertTrue(refundEvents.get(0).getPayload().contains(refund.getRefundId()));
    }

    @Test
    @DisplayName("5. Background delivery worker claims event and delivers successfully to endpoint")
    void testWorker_DispatchesAndMarksDelivered() throws Exception {
        when(httpClient.send(anyString(), any(), any()))
                .thenReturn(new WebhookHttpClient.WebhookHttpResponse(200, "{\"success\":true}"));

        // Setup pending outbox event
        OutboxEvent event = new OutboxEvent(
                "evt_dispatch_test", "payment.succeeded", "PAY_TEST_001", "{\"status\":\"SUCCESS\"}", 5, Instant.now()
        );
        event.setRequestId("req_dispatch_test_123");
        outboxEventRepository.saveAndFlush(event);

        deliveryService.setSigningSecret("whsec_secret_123");
        deliveryService.setDefaultDestinationUrl("https://merchant.example.com/hook");
        outboxWorker.setEnabled(true);

        int processed = outboxWorker.processPendingEvents();
        assertEquals(1, processed);

        OutboxEvent updated = outboxEventRepository.findByEventId("evt_dispatch_test").orElseThrow();
        assertEquals(OutboxStatus.DELIVERED, updated.getStatus());
        assertNotNull(updated.getDeliveredAt());
    }

    @Test
    @DisplayName("6. Webhook delivery failure does not impact underlying payment transaction status")
    void testWebhookFailure_DoesNotRollbackPayment() throws Exception {
        String userCode = "USR_OUTBOX_6_" + System.currentTimeMillis();
        String idempotencyKey = "IDEM_OUTBOX_6_" + System.currentTimeMillis();

        CreateUserRequest userReq = new CreateUserRequest(userCode, "Independent Payment", userCode.toLowerCase() + "@test.com", 50000L);
        mockMvc.perform(post("/api/v1/users")
                .header("X-Api-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(userReq)))
                .andExpect(status().isCreated());

        // Payment processes successfully
        InitiatePaymentRequest payReq = new InitiatePaymentRequest(idempotencyKey, userCode, 10000L, "USD", "CREDIT_CARD", "Payment");
        MvcResult payResult = mockMvc.perform(post("/api/v1/payments")
                .header("X-Api-Key", API_KEY)
                .header("X-Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andReturn();

        PaymentResponse payment = objectMapper.readValue(payResult.getResponse().getContentAsString(), PaymentResponse.class);

        // Simulate webhook server throwing HTTP 500 error
        when(httpClient.send(anyString(), any(), any()))
                .thenReturn(new WebhookHttpClient.WebhookHttpResponse(500, "Webhook server down"));

        deliveryService.setSigningSecret("whsec_secret_123");
        deliveryService.setDefaultDestinationUrl("https://merchant.example.com/hook");
        outboxWorker.setEnabled(true);
        outboxWorker.processPendingEvents();

        // Payment status in database MUST remain SUCCESS
        var persistedPayment = paymentRepository.findByPaymentId(payment.getPaymentId()).orElseThrow();
        assertEquals(PaymentStatus.SUCCESS, persistedPayment.getStatus(), "Payment state must never regress due to webhook network failures");
    }
}
