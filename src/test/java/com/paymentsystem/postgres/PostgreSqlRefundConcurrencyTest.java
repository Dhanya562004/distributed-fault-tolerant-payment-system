package com.paymentsystem.postgres;

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
 * Concurrency tests running on real PostgreSQL 16 Testcontainers.
 *
 * NOTE: These tests rely strictly on PostgreSQL row-level pessimistic write locking
 * (SELECT FOR UPDATE) executed by {@code PaymentTransactionRepository.findByPaymentIdForUpdate(paymentId)}
 * to serialize concurrent refund requests and prevent over-refunding and state race conditions.
 */
@AutoConfigureMockMvc
class PostgreSqlRefundConcurrencyTest extends AbstractPostgreSqlIntegrationTest {

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
        userCode = "USR_PG_REF_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        User user = new User(userCode, "PG Concurrent User", userCode + "@paymentsystem.io", 500000L);
        userRepository.saveAndFlush(user);
    }

    private String createSuccessfulPayment(long amount) throws Exception {
        String paymentIdem = "IDEM_PG_PAY_" + UUID.randomUUID();
        InitiatePaymentRequest req = new InitiatePaymentRequest(
                paymentIdem, userCode, amount, "USD", "CREDIT_CARD", "Initial PG payment for refund test"
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
    @DisplayName("PostgreSQL Row Lock: Two refunds of 60 on 100 concurrently - one succeeds, one fails on cumulative limit (422), payment stays SUCCESS")
    void testTwoConcurrentRefundsOf60On100_PostgreSql() throws Exception {
        String paymentId = createSuccessfulPayment(10000L);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger cumulativeLimitErrorCount = new AtomicInteger(0);

        for (int i = 0; i < 2; i++) {
            final String idemKey = "IDEM_PG_REF_60_" + i + "_" + UUID.randomUUID();
            executor.submit(() -> {
                try {
                    startLatch.await();
                    ProcessRefundRequest refReq = new ProcessRefundRequest(paymentId, idemKey, 6000L, "PG partial refund 60");
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

        assertEquals(1, successCount.get(), "Exactly one refund of 60 must succeed");
        assertEquals(1, cumulativeLimitErrorCount.get(), "Concurrent conflicting refund must fail with 422 CUMULATIVE_REFUND_LIMIT_EXCEEDED");

        List<RefundTransaction> refunds = refundRepository.findByPaymentId(paymentId);
        assertEquals(1, refunds.size());
        assertEquals(6000L, refunds.get(0).getAmount());

        PaymentTransaction finalPayment = paymentRepository.findByPaymentId(paymentId).orElseThrow();
        assertEquals(PaymentStatus.SUCCESS, finalPayment.getStatus(), "Payment must remain in SUCCESS state");

        User finalUser = userRepository.findByUserCode(userCode).orElseThrow();
        assertEquals(496000L, finalUser.getBalance());
    }

    @Test
    @DisplayName("PostgreSQL Row Lock: Two full refunds of 100 concurrently - one succeeds, one fails with InvalidTransactionStateException (422), payment becomes REFUNDED")
    void testTwoConcurrentFullRefundsOf100_PostgreSql() throws Exception {
        String paymentId = createSuccessfulPayment(10000L);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger invalidStateErrorCount = new AtomicInteger(0);

        for (int i = 0; i < 2; i++) {
            final String idemKey = "IDEM_PG_REF_FULL_" + i + "_" + UUID.randomUUID();
            executor.submit(() -> {
                try {
                    startLatch.await();
                    ProcessRefundRequest refReq = new ProcessRefundRequest(paymentId, idemKey, 10000L, "PG full refund 100");
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

        assertEquals(1, successCount.get(), "Exactly one full refund must succeed");
        assertEquals(1, invalidStateErrorCount.get(), "Concurrent full refund must fail with 422 INVALID_TRANSACTION_STATE");

        List<RefundTransaction> refunds = refundRepository.findByPaymentId(paymentId);
        assertEquals(1, refunds.size());
        assertEquals(10000L, refunds.get(0).getAmount());

        PaymentTransaction finalPayment = paymentRepository.findByPaymentId(paymentId).orElseThrow();
        assertEquals(PaymentStatus.REFUNDED, finalPayment.getStatus(), "Payment must be transitioned to REFUNDED state");

        User finalUser = userRepository.findByUserCode(userCode).orElseThrow();
        assertEquals(500000L, finalUser.getBalance());
    }
}
