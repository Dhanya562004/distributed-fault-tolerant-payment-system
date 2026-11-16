package com.paymentsystem.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsystem.dto.request.CreateUserRequest;
import com.paymentsystem.dto.request.InitiatePaymentRequest;
import com.paymentsystem.dto.request.ProcessRefundRequest;
import com.paymentsystem.dto.request.SimulationConfigRequest;
import com.paymentsystem.dto.response.PaymentResponse;
import com.paymentsystem.dto.response.RefundResponse;
import com.paymentsystem.dto.response.UserResponse;
import com.paymentsystem.entity.PaymentStatus;
import com.paymentsystem.entity.PaymentTransaction;
import com.paymentsystem.repository.PaymentTransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
public class PaymentLifecycleIntegrationTest {

    private static final String API_KEY = "apexpay_admin_secret_key_2026";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PaymentTransactionRepository paymentRepository;

    @Test
    @DisplayName("End-to-End Banking Lifecycle: User Creation -> Idempotent Payment -> Payload Conflict -> Validation -> Refund")
    void testCompleteBankingLifecycle() throws Exception {
        String userCode = "USR_E2E_" + System.currentTimeMillis();
        String idempotencyKey = "IDEM_E2E_" + System.currentTimeMillis();

        // 1. Create User
        CreateUserRequest userReq = new CreateUserRequest(userCode, "E2E Banking Customer", userCode.toLowerCase() + "@bank.com", 150000L);
        mockMvc.perform(post("/api/v1/users")
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(userReq)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.userCode").value(userCode));

        // 2. Initiate Payment
        InitiatePaymentRequest payReq = new InitiatePaymentRequest(
                idempotencyKey, userCode, 25000L, "USD", "CREDIT_CARD", "E2E Payment Test"
        );

        MvcResult payResult = mockMvc.perform(post("/api/v1/payments")
                        .header("X-Api-Key", API_KEY)
                        .header("X-Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payReq)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.isCachedIdempotentResponse").value(false))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andReturn();

        PaymentResponse payment = objectMapper.readValue(payResult.getResponse().getContentAsString(), PaymentResponse.class);
        assertNotNull(payment.getPaymentId());

        // 3. Repeat Payment with Same Idempotency Key & Same Payload -> Returns Cached Response
        mockMvc.perform(post("/api/v1/payments")
                        .header("X-Api-Key", API_KEY)
                        .header("X-Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payReq)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.paymentId").value(payment.getPaymentId()))
                .andExpect(jsonPath("$.isCachedIdempotentResponse").value(true));

        // 4. Repeat Payment with Same Idempotency Key & Different Payload -> 409 Conflict
        InitiatePaymentRequest conflictingPayReq = new InitiatePaymentRequest(
                idempotencyKey, userCode, 88800L, "USD", "CREDIT_CARD", "Conflicting E2E Request"
        );
        mockMvc.perform(post("/api/v1/payments")
                        .header("X-Api-Key", API_KEY)
                        .header("X-Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(conflictingPayReq)))
                .andExpect(status().isConflict())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_PAYLOAD_MISMATCH"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());

        // 5. Submit Invalid Payment Request with Negative Amount -> 400 Bad Request
        InitiatePaymentRequest invalidReq = new InitiatePaymentRequest(
                "IDEM_INVALID_" + System.currentTimeMillis(), userCode, -5000L, "USD", "CREDIT_CARD", "Negative amount"
        );
        mockMvc.perform(post("/api/v1/payments")
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidReq)))
                .andExpect(status().isBadRequest())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());

        // 6. Query Payment by ID
        mockMvc.perform(get("/api/v1/payments/" + payment.getPaymentId())
                        .header("X-Api-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.paymentId").value(payment.getPaymentId()))
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        // 7. Process Valid Refund
        String refundIdemKey = "IDEM_REF_E2E_" + System.currentTimeMillis();
        ProcessRefundRequest refundReq = new ProcessRefundRequest(
                payment.getPaymentId(), refundIdemKey, 10000L, "Partial refund for goods"
        );

        MvcResult refundResult = mockMvc.perform(post("/api/v1/refunds")
                        .header("X-Api-Key", API_KEY)
                        .header("X-Idempotency-Key", refundIdemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refundReq)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andReturn();

        RefundResponse refund = objectMapper.readValue(refundResult.getResponse().getContentAsString(), RefundResponse.class);
        assertNotNull(refund.getRefundId());

        // Verify that parent payment remains in SUCCESS state after partial refund
        PaymentTransaction paymentAfterPartial = paymentRepository.findByPaymentId(payment.getPaymentId()).orElseThrow();
        assertEquals(PaymentStatus.SUCCESS, paymentAfterPartial.getStatus(), "Partial refund must leave payment in SUCCESS state");

        // 8. Submit Excessive Refund Exceeding Remaining Payment Balance -> 422 Unprocessable Entity
        ProcessRefundRequest excessiveRefundReq = new ProcessRefundRequest(
                payment.getPaymentId(), "IDEM_REF_OVERFLOW_" + System.currentTimeMillis(), 20000L, "Exceeds remaining 150 balance"
        );
        mockMvc.perform(post("/api/v1/refunds")
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(excessiveRefundReq)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.code").value("CUMULATIVE_REFUND_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());

        // 9. Query Audit Logs (paginated list endpoint)
        mockMvc.perform(get("/api/v1/audit/logs")
                        .header("X-Api-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.pagination.limit").exists());
    }

    @Test
    @DisplayName("Transaction Rollback: Gateway failure rolls back balance deduction completely")
    void testFailedPayment_RollsBackBalanceDeduction() throws Exception {
        String userCode = "USR_ROLLBACK_" + System.currentTimeMillis();
        String idempotencyKey = "IDEM_ROLLBACK_" + System.currentTimeMillis();

        // 1. Create User with 500.00 balance
        CreateUserRequest userReq = new CreateUserRequest(userCode, "Rollback Customer", userCode.toLowerCase() + "@test.com", 50000L);
        mockMvc.perform(post("/api/v1/users")
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(userReq)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("X-Request-Id"));

        // 2. Enable 100% gateway failures
        SimulationConfigRequest simConfig = new SimulationConfigRequest();
        simConfig.setEnableGatewayFailures(true);
        simConfig.setFailureProbability(1.0);
        mockMvc.perform(post("/api/v1/simulation/config")
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(simConfig)))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-Id"));

        try {
            // 3. Initiate payment of 100.00 (which will fail all retry attempts)
            InitiatePaymentRequest payReq = new InitiatePaymentRequest(
                    idempotencyKey, userCode, 10000L, "USD", "CREDIT_CARD", "Payment doomed to fail"
            );
            mockMvc.perform(post("/api/v1/payments")
                            .header("X-Api-Key", API_KEY)
                            .header("X-Idempotency-Key", idempotencyKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(payReq)))
                    .andExpect(status().isCreated())
                    .andExpect(header().exists("X-Request-Id"))
                    .andExpect(jsonPath("$.status").value("FAILED"));

            // 4. Verify user balance was NOT deducted or corrupted by failed retries
            MvcResult userResult = mockMvc.perform(get("/api/v1/users/" + userCode)
                            .header("X-Api-Key", API_KEY))
                    .andExpect(status().isOk())
                    .andExpect(header().exists("X-Request-Id"))
                    .andReturn();
            UserResponse userResp = objectMapper.readValue(userResult.getResponse().getContentAsString(), UserResponse.class);
            assertEquals(50000L, userResp.getBalance(),
                    "User balance must remain 50000 after failed payment attempts (no partial commits)");
        } finally {
            // Reset simulation config
            mockMvc.perform(post("/api/v1/simulation/reset")
                            .header("X-Api-Key", API_KEY))
                    .andExpect(status().isOk())
                    .andExpect(header().exists("X-Request-Id"));
        }
    }
}
