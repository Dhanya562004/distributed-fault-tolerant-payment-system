package com.paymentsystem.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsystem.dto.request.CreateUserRequest;
import com.paymentsystem.dto.request.InitiatePaymentRequest;
import com.paymentsystem.dto.request.ProcessRefundRequest;
import com.paymentsystem.dto.response.PaymentResponse;
import com.paymentsystem.dto.response.RefundResponse;
import com.paymentsystem.entity.PaymentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
public class PaymentLifecycleIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("End-to-End Banking Lifecycle: User Creation -> Idempotent Payment -> Payload Conflict -> Validation -> Refund")
    void testCompleteBankingLifecycle() throws Exception {
        String userCode = "USR_E2E_" + System.currentTimeMillis();
        String idempotencyKey = "IDEM_E2E_" + System.currentTimeMillis();

        // 1. Create User
        CreateUserRequest userReq = new CreateUserRequest(userCode, "E2E Banking Customer", userCode.toLowerCase() + "@bank.com", new BigDecimal("1500.00"));
        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(userReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userCode").value(userCode));

        // 2. Initiate Payment
        InitiatePaymentRequest payReq = new InitiatePaymentRequest(
                idempotencyKey, userCode, new BigDecimal("250.00"), "USD", "CREDIT_CARD", "E2E Payment Test"
        );

        MvcResult payResult = mockMvc.perform(post("/api/v1/payments")
                        .header("X-Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.isCachedIdempotentResponse").value(false))
                .andReturn();

        PaymentResponse payment = objectMapper.readValue(payResult.getResponse().getContentAsString(), PaymentResponse.class);
        assertNotNull(payment.getPaymentId());

        // 3. Repeat Payment with Same Idempotency Key & Same Payload -> Returns Cached Response
        mockMvc.perform(post("/api/v1/payments")
                        .header("X-Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentId").value(payment.getPaymentId()))
                .andExpect(jsonPath("$.isCachedIdempotentResponse").value(true));

        // 4. Repeat Payment with Same Idempotency Key & Different Payload -> 409 Conflict
        InitiatePaymentRequest conflictingPayReq = new InitiatePaymentRequest(
                idempotencyKey, userCode, new BigDecimal("888.00"), "USD", "CREDIT_CARD", "Conflicting E2E Request"
        );
        mockMvc.perform(post("/api/v1/payments")
                        .header("X-Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(conflictingPayReq)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("IDEMPOTENCY_PAYLOAD_MISMATCH"));

        // 5. Submit Invalid Payment Request with Negative Amount -> 400 Bad Request
        InitiatePaymentRequest invalidReq = new InitiatePaymentRequest(
                "IDEM_INVALID_" + System.currentTimeMillis(), userCode, new BigDecimal("-50.00"), "USD", "CREDIT_CARD", "Negative amount"
        );
        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));

        // 6. Query Payment by ID
        mockMvc.perform(get("/api/v1/payments/" + payment.getPaymentId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(payment.getPaymentId()))
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        // 7. Process Valid Refund
        String refundIdemKey = "IDEM_REF_E2E_" + System.currentTimeMillis();
        ProcessRefundRequest refundReq = new ProcessRefundRequest(
                payment.getPaymentId(), refundIdemKey, new BigDecimal("100.00"), "Partial refund for goods"
        );

        MvcResult refundResult = mockMvc.perform(post("/api/v1/refunds")
                        .header("X-Idempotency-Key", refundIdemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refundReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andReturn();

        RefundResponse refund = objectMapper.readValue(refundResult.getResponse().getContentAsString(), RefundResponse.class);
        assertNotNull(refund.getRefundId());

        // 8. Submit Excessive Refund Exceeding Remaining Payment Balance -> 422 Unprocessable Entity
        ProcessRefundRequest excessiveRefundReq = new ProcessRefundRequest(
                payment.getPaymentId(), "IDEM_REF_OVERFLOW_" + System.currentTimeMillis(), new BigDecimal("200.00"), "Exceeds remaining 150 balance"
        );
        mockMvc.perform(post("/api/v1/refunds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(excessiveRefundReq)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INVALID_TRANSACTION_STATE"));

        // 9. Query Audit Logs
        mockMvc.perform(get("/api/v1/audit/logs"))
                .andExpect(status().isOk());
    }
}
