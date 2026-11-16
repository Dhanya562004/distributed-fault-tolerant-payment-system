package com.paymentsystem.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsystem.dto.request.InitiatePaymentRequest;
import com.paymentsystem.dto.request.ProcessRefundRequest;
import com.paymentsystem.dto.response.PaymentResponse;
import com.paymentsystem.dto.response.RefundResponse;
import com.paymentsystem.entity.PaymentStatus;
import com.paymentsystem.entity.PaymentTransaction;
import com.paymentsystem.entity.RefundTransaction;
import com.paymentsystem.entity.User;
import com.paymentsystem.repository.PaymentTransactionRepository;
import com.paymentsystem.repository.RefundTransactionRepository;
import com.paymentsystem.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Verifies concurrent refund serialization relying on SELECT FOR UPDATE row locking.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RefundConcurrencyIntegrationTest {

    private static final String API_KEY = "apexpay_admin_secret_key_2026";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PaymentTransactionRepository paymentRepository;

    @Autowired
    private RefundTransactionRepository refundRepository;

    @Autowired
    private UserRepository userRepository;

    private String userCode;

    @BeforeEach
    void setupUser() {
        userCode = "USR_REF_CONC_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        User user = new User(userCode, "Concurrent Refund User", userCode + "@paymentsystem.io", 500000L);
        userRepository.saveAndFlush(user);
    }

    private String createSuccessfulPayment(long amount) throws Exception {
        String paymentIdem = "IDEM_PAY_" + UUID.randomUUID();
        InitiatePaymentRequest req = new InitiatePaymentRequest(
                paymentIdem, userCode, amount, "USD", "CREDIT_CARD", "Initial payment for refund test"
        );

        MvcResult result = mockMvc.perform(post("/api/v1/payments")
                        .header("X-Api-Key", API_KEY)
                        .header("X-Idempotency-Key", paymentIdem)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        PaymentResponse resp = objectMapper.readValue(result.getResponse().getContentAsString(), PaymentResponse.class);
        return resp.getPaymentId();
    }

    @Test
    @DisplayName("Two concurrent refunds of 60 on a payment of 100: exactly one succeeds, one fails on cumulative limit (422), payment stays SUCCESS")
    void testTwoConcurrentRefundsOf60On100_OneSucceeds_OneFailsOnCumulativeLimit() throws Exception {
        // Payment amount: 10000 ($100.00). User balance before payment was 500000, now 490000.
        String paymentId = createSuccessfulPayment(10000L);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger cumulativeLimitErrorCount = new AtomicInteger(0);

        for (int i = 0; i < 2; i++) {
            final String idemKey = "IDEM_REF_60_" + i + "_" + UUID.randomUUID();
            executor.submit(() -> {
                try {
                    startLatch.await();
                    ProcessRefundRequest refReq = new ProcessRefundRequest(paymentId, idemKey, 6000L, "Partial refund of 60");
                    MvcResult res = mockMvc.perform(post("/api/v1/refunds")
                                    .header("X-Api-Key", API_KEY)
                                    .header("X-Idempotency-Key", idemKey)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(refReq)))
                            .andReturn();

                    int status = res.getResponse().getStatus();
                    String content = res.getResponse().getContentAsString();
                    if (status == 201) {
                        successCount.incrementAndGet();
                    } else if (status == 422 && content.contains("CUMULATIVE_REFUND_LIMIT_EXCEEDED")) {
                        cumulativeLimitErrorCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    // unexpected error
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
        executor.shutdown();

        // Exactly one succeeded, exactly one failed on cumulative refund limit (422), NOT InvalidTransactionStateException
        assertEquals(1, successCount.get(), "Exactly one refund of 60 must succeed");
        assertEquals(1, cumulativeLimitErrorCount.get(), "The concurrent conflicting refund must fail with 422 CUMULATIVE_REFUND_LIMIT_EXCEEDED");

        // Verify total refunded amount
        List<RefundTransaction> refunds = refundRepository.findByPaymentId(paymentId);
        assertEquals(1, refunds.size());
        assertEquals(6000L, refunds.get(0).getAmount());

        // Verify payment remains in SUCCESS state
        PaymentTransaction finalPayment = paymentRepository.findByPaymentId(paymentId).orElseThrow();
        assertEquals(PaymentStatus.SUCCESS, finalPayment.getStatus(), "Payment must remain in SUCCESS state after partial refund");

        // Verify user balance was credited exactly 6000L (490000 + 6000 = 496000)
        User finalUser = userRepository.findByUserCode(userCode).orElseThrow();
        assertEquals(496000L, finalUser.getBalance());
    }

    @Test
    @DisplayName("Two concurrent full refunds of 100 on a payment of 100: exactly one succeeds, one fails with InvalidTransactionStateException (422), payment becomes REFUNDED")
    void testTwoConcurrentFullRefundsOf100_OneSucceeds_OneFailsOnInvalidState() throws Exception {
        String paymentId = createSuccessfulPayment(10000L);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger invalidStateErrorCount = new AtomicInteger(0);

        for (int i = 0; i < 2; i++) {
            final String idemKey = "IDEM_REF_FULL_" + i + "_" + UUID.randomUUID();
            executor.submit(() -> {
                try {
                    startLatch.await();
                    ProcessRefundRequest refReq = new ProcessRefundRequest(paymentId, idemKey, 10000L, "Full refund of 100");
                    MvcResult res = mockMvc.perform(post("/api/v1/refunds")
                                    .header("X-Api-Key", API_KEY)
                                    .header("X-Idempotency-Key", idemKey)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(refReq)))
                            .andReturn();

                    int status = res.getResponse().getStatus();
                    String content = res.getResponse().getContentAsString();
                    if (status == 201) {
                        successCount.incrementAndGet();
                    } else if (status == 422 && content.contains("INVALID_TRANSACTION_STATE")) {
                        invalidStateErrorCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    // unexpected error
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
        executor.shutdown();

        // Exactly one succeeded, one failed on InvalidTransactionStateException because payment transitioned to REFUNDED
        assertEquals(1, successCount.get(), "Exactly one full refund must succeed");
        assertEquals(1, invalidStateErrorCount.get(), "The concurrent full refund must fail with 422 INVALID_TRANSACTION_STATE");

        // Verify total refunds in DB
        List<RefundTransaction> refunds = refundRepository.findByPaymentId(paymentId);
        assertEquals(1, refunds.size());
        assertEquals(10000L, refunds.get(0).getAmount());

        // Verify payment is now in REFUNDED state
        PaymentTransaction finalPayment = paymentRepository.findByPaymentId(paymentId).orElseThrow();
        assertEquals(PaymentStatus.REFUNDED, finalPayment.getStatus(), "Payment must be transitioned to REFUNDED state");

        // Verify user balance credited exactly 10000L (490000 + 10000 = 500000)
        User finalUser = userRepository.findByUserCode(userCode).orElseThrow();
        assertEquals(500000L, finalUser.getBalance());
    }

    @Test
    @DisplayName("Replaying a full refund with the same idempotency key returns cached response with Idempotent-Replayed: true")
    void testReplayFullRefund_ReturnsCachedResponseWithoutDoubleRefund() throws Exception {
        String paymentId = createSuccessfulPayment(10000L);
        String refundIdem = "IDEM_REF_REPLAY_" + UUID.randomUUID();

        ProcessRefundRequest refReq = new ProcessRefundRequest(paymentId, refundIdem, 10000L, "Full refund with replay");

        // First call executes refund
        MvcResult firstResult = mockMvc.perform(post("/api/v1/refunds")
                        .header("X-Api-Key", API_KEY)
                        .header("X-Idempotency-Key", refundIdem)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refReq)))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andReturn();

        RefundResponse firstResp = objectMapper.readValue(firstResult.getResponse().getContentAsString(), RefundResponse.class);

        // Replay call returns cached response with Idempotent-Replayed: true
        MvcResult secondResult = mockMvc.perform(post("/api/v1/refunds")
                        .header("X-Api-Key", API_KEY)
                        .header("X-Idempotency-Key", refundIdem)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refReq)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn();

        RefundResponse secondResp = objectMapper.readValue(secondResult.getResponse().getContentAsString(), RefundResponse.class);
        assertEquals(firstResp.getRefundId(), secondResp.getRefundId());

        // Verify only one refund row exists
        assertEquals(1, refundRepository.findByPaymentId(paymentId).size());
    }
}
