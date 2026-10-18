package com.paymentsystem.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsystem.dto.request.CreateUserRequest;
import com.paymentsystem.dto.request.InitiatePaymentRequest;
import com.paymentsystem.dto.request.ProcessRefundRequest;
import com.paymentsystem.dto.response.PaymentResponse;
import com.paymentsystem.dto.response.RefundResponse;
import com.paymentsystem.entity.IdempotencyKeyRecord;
import com.paymentsystem.entity.PaymentStatus;
import com.paymentsystem.entity.PaymentTransaction;
import com.paymentsystem.entity.User;
import com.paymentsystem.repository.IdempotencyKeyRepository;
import com.paymentsystem.repository.PaymentTransactionRepository;
import com.paymentsystem.repository.UserRepository;
import com.paymentsystem.service.AuditService;
import com.paymentsystem.service.IdempotencyService;
import com.paymentsystem.service.PaymentProcessingService;
import com.paymentsystem.util.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
public class IdempotencyExpiryAndReplayTest {

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        public MutableClock testMutableClock() {
            return new MutableClock(Instant.parse("2026-10-18T10:00:00Z"));
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MutableClock clock;

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private PaymentProcessingService paymentProcessingService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PaymentTransactionRepository paymentTransactionRepository;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    private AuditService auditService;

    private static final Instant BASE_TIME = Instant.parse("2026-10-18T10:00:00Z");

    @BeforeEach
    void resetClockAndState() {
        clock.setInstant(BASE_TIME);
    }

    private String createTestUser(BigDecimal initialBalance) throws Exception {
        String userCode = "USR_TTL_" + System.nanoTime();
        CreateUserRequest userReq = new CreateUserRequest(userCode, "TTL Test User", userCode.toLowerCase() + "@test.com", initialBalance);
        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(userReq)))
                .andExpect(status().isCreated());
        return userCode;
    }

    @Test
    @DisplayName("1. New idempotency key creates a payment")
    void test1_NewIdempotencyKeyCreatesPayment() throws Exception {
        String userCode = createTestUser(new BigDecimal("1000.00"));
        String key = "IDEM_TEST1_" + System.nanoTime();

        InitiatePaymentRequest req = new InitiatePaymentRequest(
                key, userCode, new BigDecimal("100.00"), "USD", "CREDIT_CARD", "Initial payment"
        );

        MvcResult result = mockMvc.perform(post("/api/v1/payments")
                        .header("X-Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andReturn();

        PaymentResponse response = objectMapper.readValue(result.getResponse().getContentAsString(), PaymentResponse.class);
        assertNotNull(response.getPaymentId());

        User user = userRepository.findByUserCode(userCode).orElseThrow();
        assertEquals(0, new BigDecimal("900.0000").compareTo(user.getBalance()));

        IdempotencyKeyRecord record = idempotencyKeyRepository.findByKeyValue(key).orElseThrow();
        assertEquals(IdempotencyKeyRecord.Status.COMPLETED, record.getStatus());
        assertNotNull(record.getCreatedAt());
        assertNotNull(record.getExpiresAt());
        assertEquals(BASE_TIME.plus(Duration.ofHours(24)), record.getExpiresAt());
    }

    @Test
    @DisplayName("2. Duplicate request within 24 hours replays the response")
    void test2_DuplicateRequestWithin24HoursReplaysResponse() throws Exception {
        String userCode = createTestUser(new BigDecimal("1000.00"));
        String key = "IDEM_TEST2_" + System.nanoTime();

        InitiatePaymentRequest req = new InitiatePaymentRequest(
                key, userCode, new BigDecimal("150.00"), "USD", "CREDIT_CARD", "Original purchase"
        );

        // 1. Initial execution at T0
        MvcResult firstResult = mockMvc.perform(post("/api/v1/payments")
                        .header("X-Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        PaymentResponse firstPayment = objectMapper.readValue(firstResult.getResponse().getContentAsString(), PaymentResponse.class);

        // 2. Advance time by 12 hours (well within the 24-hour retention period)
        clock.advance(Duration.ofHours(12));

        // 3. Repeat request with same key & payload -> replays exact response
        MvcResult replayResult = mockMvc.perform(post("/api/v1/payments")
                        .header("X-Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentId").value(firstPayment.getPaymentId()))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andReturn();

        PaymentResponse replayedPayment = objectMapper.readValue(replayResult.getResponse().getContentAsString(), PaymentResponse.class);
        assertEquals(firstPayment.getPaymentId(), replayedPayment.getPaymentId());

        // Balance must only be deducted ONCE: $1000 - $150 = $850
        User user = userRepository.findByUserCode(userCode).orElseThrow();
        assertEquals(0, new BigDecimal("850.0000").compareTo(user.getBalance()));
    }

    @Test
    @DisplayName("3. Cached replay includes Idempotent-Replayed: true header")
    void test3_CachedReplayIncludesIdempotentReplayedHeader() throws Exception {
        String userCode = createTestUser(new BigDecimal("1000.00"));
        String key = "IDEM_TEST3_" + System.nanoTime();

        InitiatePaymentRequest req = new InitiatePaymentRequest(
                key, userCode, new BigDecimal("75.00"), "USD", "CREDIT_CARD", "Test replay header"
        );

        // Initial execution
        mockMvc.perform(post("/api/v1/payments")
                        .header("X-Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());

        // Advance 1 hour
        clock.advance(Duration.ofHours(1));

        // Cached replay must include Idempotent-Replayed: true
        mockMvc.perform(post("/api/v1/payments")
                        .header("X-Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.isCachedIdempotentResponse").value(true));
    }

    @Test
    @DisplayName("4. Original payment does not include the replay=true header")
    void test4_OriginalPaymentDoesNotIncludeReplayHeader() throws Exception {
        String userCode = createTestUser(new BigDecimal("1000.00"));
        String key = "IDEM_TEST4_" + System.nanoTime();

        InitiatePaymentRequest req = new InitiatePaymentRequest(
                key, userCode, new BigDecimal("60.00"), "USD", "CREDIT_CARD", "Fresh payment"
        );

        // Fresh payment must NOT return Idempotent-Replayed header
        mockMvc.perform(post("/api/v1/payments")
                        .header("X-Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(jsonPath("$.isCachedIdempotentResponse").value(false));
    }

    @Test
    @DisplayName("5. Different payload with an unexpired key is rejected")
    void test5_DifferentPayloadWithUnexpiredKeyIsRejected() throws Exception {
        String userCode = createTestUser(new BigDecimal("1000.00"));
        String key = "IDEM_TEST5_" + System.nanoTime();

        InitiatePaymentRequest originalReq = new InitiatePaymentRequest(
                key, userCode, new BigDecimal("100.00"), "USD", "CREDIT_CARD", "Original payload"
        );

        mockMvc.perform(post("/api/v1/payments")
                        .header("X-Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(originalReq)))
                .andExpect(status().isCreated());

        // Advance 2 hours (unexpired key)
        clock.advance(Duration.ofHours(2));

        // Tampered payload with same key
        InitiatePaymentRequest tamperedReq = new InitiatePaymentRequest(
                key, userCode, new BigDecimal("200.00"), "USD", "CREDIT_CARD", "Tampered payload"
        );

        mockMvc.perform(post("/api/v1/payments")
                        .header("X-Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tamperedReq)))
                .andExpect(status().isConflict())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(jsonPath("$.error").value("IDEMPOTENCY_PAYLOAD_MISMATCH"));

        // Balance deducted only once for the original payment
        User user = userRepository.findByUserCode(userCode).orElseThrow();
        assertEquals(0, new BigDecimal("900.0000").compareTo(user.getBalance()));
    }

    @Test
    @DisplayName("6. Key expires exactly at the configured expiry boundary")
    void test6_KeyExpiresExactlyAtConfiguredExpiryBoundary() throws Exception {
        String userCode = createTestUser(new BigDecimal("1000.00"));
        String key = "IDEM_TEST6_" + System.nanoTime();

        InitiatePaymentRequest req = new InitiatePaymentRequest(
                key, userCode, new BigDecimal("50.00"), "USD", "CREDIT_CARD", "Boundary test"
        );

        mockMvc.perform(post("/api/v1/payments")
                        .header("X-Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());

        // At T0 + 23h 59m 59s: key is NOT expired -> returns cached replay
        clock.setInstant(BASE_TIME.plus(Duration.ofHours(24)).minusSeconds(1));
        mockMvc.perform(post("/api/v1/payments")
                        .header("X-Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.isCachedIdempotentResponse").value(true));

        // At T0 + 24h 00m 00s: key IS expired at the exact boundary
        clock.setInstant(BASE_TIME.plus(Duration.ofHours(24)));
        IdempotencyKeyRecord record = idempotencyKeyRepository.findByKeyValue(key).orElseThrow();
        assertTrue(record.isExpired(clock.instant()), "Key must be expired at exact 24-hour boundary");
    }

    @Test
    @DisplayName("7. Expired key can be reused for a new request")
    void test7_ExpiredKeyCanBeReusedForNewRequest() throws Exception {
        String userCode = createTestUser(new BigDecimal("1000.00"));
        String key = "IDEM_TEST7_" + System.nanoTime();

        InitiatePaymentRequest firstReq = new InitiatePaymentRequest(
                key, userCode, new BigDecimal("100.00"), "USD", "CREDIT_CARD", "Day 1 purchase"
        );

        MvcResult firstResult = mockMvc.perform(post("/api/v1/payments")
                        .header("X-Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(firstReq)))
                .andExpect(status().isCreated())
                .andReturn();

        PaymentResponse firstPayment = objectMapper.readValue(firstResult.getResponse().getContentAsString(), PaymentResponse.class);

        // Advance 25 hours (key is now expired)
        clock.advance(Duration.ofHours(25));

        // Submit new payment reusing the expired key (different amount)
        InitiatePaymentRequest secondReq = new InitiatePaymentRequest(
                key, userCode, new BigDecimal("200.00"), "USD", "CREDIT_CARD", "Day 2 purchase with reused key"
        );

        MvcResult secondResult = mockMvc.perform(post("/api/v1/payments")
                        .header("X-Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(secondReq)))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(jsonPath("$.isCachedIdempotentResponse").value(false))
                .andReturn();

        PaymentResponse secondPayment = objectMapper.readValue(secondResult.getResponse().getContentAsString(), PaymentResponse.class);

        // Verify distinct payment IDs were created
        assertNotEquals(firstPayment.getPaymentId(), secondPayment.getPaymentId());

        // Verify both payments were charged: $1000 - $100 - $200 = $700
        User user = userRepository.findByUserCode(userCode).orElseThrow();
        assertEquals(0, new BigDecimal("700.0000").compareTo(user.getBalance()));

        // Within 1 hour of the second payment (Day 2 + 1h), replay returns second payment
        clock.advance(Duration.ofHours(1));
        mockMvc.perform(post("/api/v1/payments")
                        .header("X-Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(secondReq)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.paymentId").value(secondPayment.getPaymentId()));
    }

    @Test
    @DisplayName("8. Concurrent requests reusing an expired key cannot double-charge")
    void test8_ConcurrentRequestsReusingExpiredKeyCannotDoubleCharge() throws Exception {
        String userCode = createTestUser(new BigDecimal("1000.00"));
        String key = "IDEM_TEST8_" + System.nanoTime();

        // 1. Initial payment
        InitiatePaymentRequest initialReq = new InitiatePaymentRequest(
                key, userCode, new BigDecimal("100.00"), "USD", "CREDIT_CARD", "Day 1 payment"
        );
        PaymentResponse initialResp = paymentProcessingService.initiatePayment(initialReq);
        assertNotNull(initialResp.getPaymentId());

        // 2. Expire the key by advancing 25 hours
        clock.advance(Duration.ofHours(25));

        // 3. Launch 20 concurrent threads attempting to reuse the expired key
        int threads = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch readyLatch = new CountDownLatch(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threads);

        AtomicInteger originalCharges = new AtomicInteger(0);
        AtomicInteger cachedReplays = new AtomicInteger(0);
        AtomicInteger duplicateConflicts = new AtomicInteger(0);
        AtomicInteger unexpectedFailures = new AtomicInteger(0);

        InitiatePaymentRequest reuseReq = new InitiatePaymentRequest(
                key, userCode, new BigDecimal("150.00"), "USD", "CREDIT_CARD", "Day 2 concurrent reuse"
        );

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    PaymentResponse resp = paymentProcessingService.initiatePayment(reuseReq);
                    if (resp != null && resp.getStatus() == PaymentStatus.SUCCESS) {
                        if (Boolean.TRUE.equals(resp.getIsCachedIdempotentResponse())) {
                            cachedReplays.incrementAndGet();
                        } else {
                            originalCharges.incrementAndGet();
                        }
                    } else {
                        unexpectedFailures.incrementAndGet();
                    }
                } catch (com.paymentsystem.exception.DuplicateRequestException e) {
                    duplicateConflicts.incrementAndGet();
                } catch (Exception e) {
                    unexpectedFailures.incrementAndGet();
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();
        boolean completed = finishLatch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(completed);
        assertEquals(1, originalCharges.get(), "Exactly ONE original charge must succeed when reusing expired key");
        assertEquals(0, unexpectedFailures.get(), "Zero unexpected failures allowed");
        assertEquals(threads, originalCharges.get() + cachedReplays.get() + duplicateConflicts.get());

        // Initial $100 + Reused $150 = $250 deducted. Final balance: $750
        User user = userRepository.findByUserCode(userCode).orElseThrow();
        assertEquals(0, new BigDecimal("750.0000").compareTo(user.getBalance()));

        // Exactly 2 payments exist for this key in total
        List<PaymentTransaction> txs = paymentTransactionRepository.findAll().stream()
                .filter(t -> key.equals(t.getIdempotencyKey()))
                .toList();
        assertEquals(2, txs.size());
    }

    @Test
    @DisplayName("9. Expired-key cleanup preserves payment audit history")
    void test9_ExpiredKeyCleanupPreservesPaymentAuditHistory() throws Exception {
        String userCode = createTestUser(new BigDecimal("1000.00"));
        String key = "IDEM_TEST9_" + System.nanoTime();

        InitiatePaymentRequest req = new InitiatePaymentRequest(
                key, userCode, new BigDecimal("120.00"), "USD", "CREDIT_CARD", "Payment to be cleaned up"
        );

        PaymentResponse payment = paymentProcessingService.initiatePayment(req);
        assertNotNull(payment.getPaymentId());

        // Advance 25 hours to make key eligible for cleanup
        clock.advance(Duration.ofHours(25));

        // Perform cleanup
        int cleaned = idempotencyService.cleanupExpiredKeys();
        assertTrue(cleaned >= 1, "At least 1 expired key should be cleaned up");

        // Idempotency key record is removed
        assertTrue(idempotencyKeyRepository.findByKeyValue(key).isEmpty(), "Expired idempotency key must be purged");

        // Payment record MUST still exist and remain in SUCCESS state
        PaymentTransaction tx = paymentTransactionRepository.findByPaymentId(payment.getPaymentId()).orElseThrow();
        assertEquals(PaymentStatus.SUCCESS, tx.getStatus());
        assertEquals(new BigDecimal("120.0000").compareTo(tx.getAmount()), 0);

        // Audit log MUST still exist
        var auditLogs = auditService.getLogsForPayment(payment.getPaymentId());
        assertFalse(auditLogs.isEmpty(), "Audit history must remain completely preserved");
    }

    @Test
    @DisplayName("10. Existing refund and payment behavior remains correct")
    void test10_ExistingRefundAndPaymentBehaviorRemainsCorrect() throws Exception {
        String userCode = createTestUser(new BigDecimal("1000.00"));
        String payKey = "IDEM_TEST10_PAY_" + System.nanoTime();
        String refKey = "IDEM_TEST10_REF_" + System.nanoTime();

        // 1. Process payment
        InitiatePaymentRequest payReq = new InitiatePaymentRequest(
                payKey, userCode, new BigDecimal("200.00"), "USD", "CREDIT_CARD", "Payment for refund"
        );
        PaymentResponse payResp = paymentProcessingService.initiatePayment(payReq);

        // 2. Process refund (initial execution)
        ProcessRefundRequest refReq = new ProcessRefundRequest(
                payResp.getPaymentId(), refKey, new BigDecimal("50.00"), "Partial refund"
        );

        MvcResult firstRefResult = mockMvc.perform(post("/api/v1/refunds")
                        .header("X-Idempotency-Key", refKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refReq)))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andReturn();

        RefundResponse refResp = objectMapper.readValue(firstRefResult.getResponse().getContentAsString(), RefundResponse.class);

        // 3. Duplicate refund request within retention window
        mockMvc.perform(post("/api/v1/refunds")
                        .header("X-Idempotency-Key", refKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refReq)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.refundId").value(refResp.getRefundId()));

        // Refunded $50 restored: $1000 - $200 + $50 = $850
        User user = userRepository.findByUserCode(userCode).orElseThrow();
        assertEquals(0, new BigDecimal("850.0000").compareTo(user.getBalance()));
    }

    @Test
    @DisplayName("11. Concurrent duplicate requests returning HTTP 409 do not include Idempotent-Replayed header")
    void test11_ConcurrentDuplicateRequestReturns409WithoutReplayHeader() throws Exception {
        String userCode = createTestUser(new BigDecimal("1000.00"));
        String key = "IDEM_TEST11_" + System.nanoTime();

        InitiatePaymentRequest req = new InitiatePaymentRequest(
                key, userCode, new BigDecimal("100.00"), "USD", "CREDIT_CARD", "In progress test"
        );

        // Simulate an in-progress idempotency key record in database
        String hash = idempotencyService.computePayloadHash(req);
        IdempotencyKeyRecord inProgressRecord = new IdempotencyKeyRecord(key, hash, clock.instant(), clock.instant().plus(Duration.ofHours(24)));
        inProgressRecord.setStatus(IdempotencyKeyRecord.Status.IN_PROGRESS);
        idempotencyKeyRepository.saveAndFlush(inProgressRecord);

        // Attempting to post the request while IN_PROGRESS must return HTTP 409 Conflict without Idempotent-Replayed header
        mockMvc.perform(post("/api/v1/payments")
                        .header("X-Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(jsonPath("$.error").value("IDEMPOTENCY_CONFLICT"));
    }
}
