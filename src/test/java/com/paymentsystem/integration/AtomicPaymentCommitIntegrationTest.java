package com.paymentsystem.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsystem.dto.request.CreateUserRequest;
import com.paymentsystem.dto.request.InitiatePaymentRequest;
import com.paymentsystem.dto.request.SimulationConfigRequest;
import com.paymentsystem.exception.ServiceUnavailableException;
import com.paymentsystem.dto.response.PaymentResponse;
import com.paymentsystem.dto.response.UserResponse;
import com.paymentsystem.entity.IdempotencyKeyRecord;
import com.paymentsystem.entity.OutboxEvent;
import com.paymentsystem.entity.PaymentStatus;
import com.paymentsystem.entity.PaymentTransaction;
import com.paymentsystem.entity.User;
import com.paymentsystem.repository.IdempotencyKeyRepository;
import com.paymentsystem.repository.OutboxEventRepository;
import com.paymentsystem.repository.PaymentTransactionRepository;
import com.paymentsystem.repository.UserRepository;
import com.paymentsystem.service.GatewaySimulationService;
import com.paymentsystem.service.IdempotencyService;
import com.paymentsystem.service.PaymentProcessingService;
import com.paymentsystem.service.PaymentTransactionCoordinator;
import com.paymentsystem.worker.EventMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
public class AtomicPaymentCommitIntegrationTest {

    private static final String API_KEY = "apexpay_admin_secret_key_2026";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PaymentProcessingService paymentProcessingService;

    @Autowired
    private PaymentTransactionCoordinator paymentCoordinator;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PaymentTransactionRepository paymentRepository;

    @SpyBean
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    private IdempotencyService idempotencyService;

    @SpyBean
    private GatewaySimulationService gatewaySimulationService;

    @BeforeEach
    void setUp() {
        gatewaySimulationService.resetConfig();
    }

    @Test
    @DisplayName("1. Force exception before commit (outbox insert throws): balance unchanged, status not SUCCESS, no outbox row")
    void testExceptionBeforeCommit_RollsBackBalanceAndStatusAndOutbox() throws Exception {
        String userCode = "USR_ATOMIC_FAIL_" + System.currentTimeMillis();
        String paymentId = "PAY_ATOMIC_FAIL_" + System.currentTimeMillis();
        String idempotencyKey = "IDEM_ATOMIC_FAIL_" + System.currentTimeMillis();

        // 1. Create user with 1000.00 (100000 minor units)
        User user = new User(userCode, "Atomic Test User", userCode.toLowerCase() + "@test.com", 100000L);
        userRepository.saveAndFlush(user);

        // 2. Insert payment record in PENDING then commit PROCESSING
        PaymentTransaction tx = new PaymentTransaction(
                paymentId, idempotencyKey, userCode, 25000L, "USD", "CREDIT_CARD", "Atomic rollback test"
        );
        paymentRepository.saveAndFlush(tx);
        paymentCoordinator.commitPaymentProcessing(paymentId);

        // 3. Spy on outboxEventRepository to throw exception when saving outbox event
        doThrow(new DataIntegrityViolationException("Simulated forced DB outbox write failure"))
                .when(outboxEventRepository).save(any(OutboxEvent.class));

        InitiatePaymentRequest req = new InitiatePaymentRequest(
                idempotencyKey, userCode, 25000L, "USD", "CREDIT_CARD", "Atomic rollback test"
        );

        // 4. Execute payment attempt and assert it throws exception
        assertThrows(Exception.class, () -> paymentCoordinator.executePaymentAttempt(paymentId, req));

        // 5. Verify user balance was completely rolled back
        User reloadedUser = userRepository.findByUserCode(userCode).orElseThrow();
        assertEquals(100000L, reloadedUser.getBalance(),
                "User balance must remain 100000L after atomic rollback of payment attempt");

        // 6. Verify payment status in database is NOT SUCCESS (remains PROCESSING)
        PaymentTransaction reloadedTx = paymentRepository.findByPaymentId(paymentId).orElseThrow();
        assertNotEquals(PaymentStatus.SUCCESS, reloadedTx.getStatus(),
                "Payment status must not be SUCCESS if the outbox insert fails");
        assertEquals(PaymentStatus.PROCESSING, reloadedTx.getStatus());

        // 7. Verify no outbox event exists for this payment
        List<OutboxEvent> events = outboxEventRepository.findByAggregateId(paymentId);
        assertTrue(events.isEmpty(), "Outbox table must contain 0 events for rolled-back transaction");

        // Reset spy
        reset(outboxEventRepository);
    }

    @Test
    @DisplayName("2. Attempt 1 fails after deducting, attempt 2 succeeds: balance deducted exactly once, no optimistic lock exception, correct retryCount")
    void testAttempt1FailsAfterDeduction_Attempt2Succeeds_BalanceDeductedOnce() throws Exception {
        String userCode = "USR_RETRY_ONCE_" + System.currentTimeMillis();
        String idempotencyKey = "IDEM_RETRY_ONCE_" + System.currentTimeMillis();

        // 1. Create user with 1000.00
        CreateUserRequest userReq = new CreateUserRequest(userCode, "Retry Customer", userCode.toLowerCase() + "@test.com", 100000L);
        mockMvc.perform(post("/api/v1/users")
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(userReq)))
                .andExpect(status().isCreated());

        // 2. Configure mock/spy to throw ServiceUnavailableException on attempt 1, then succeed on attempt 2
        doThrow(new ServiceUnavailableException("Simulated transient 503"))
                .doNothing()
                .when(gatewaySimulationService).simulateGatewayCall(any(), any());

        // 3. Initiate payment of 300.00 (30000 minor units)
        InitiatePaymentRequest payReq = new InitiatePaymentRequest(
                idempotencyKey, userCode, 30000L, "USD", "CREDIT_CARD", "Retry deduction test"
        );

        MvcResult result = mockMvc.perform(post("/api/v1/payments")
                        .header("X-Api-Key", API_KEY)
                        .header("X-Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andReturn();

        PaymentResponse response = objectMapper.readValue(result.getResponse().getContentAsString(), PaymentResponse.class);
        assertEquals(PaymentStatus.SUCCESS, response.getStatus());
        assertTrue(response.getRetryCount() >= 1, "Payment must record at least 1 retry attempt");

        // 4. Verify user balance: 100000 - 30000 = 70000 (deducted exactly once, no double-spending)
        User reloadedUser = userRepository.findByUserCode(userCode).orElseThrow();
        assertEquals(70000L, reloadedUser.getBalance(),
                "User balance must be deducted exactly once despite previous attempt failure");

        // 5. Verify outbox event exists
        List<OutboxEvent> events = outboxEventRepository.findByAggregateId(response.getPaymentId());
        assertEquals(1, events.size(), "Exactly one outbox event must be persisted for the successful payment");
    }

    @Test
    @DisplayName("3. Calling attempt again on already-SUCCESS payment: no second deduction")
    void testCallingAttemptAgainOnAlreadySuccessPayment_NoSecondDeduction() throws Exception {
        String userCode = "USR_ALREADY_SUCCESS_" + System.currentTimeMillis();
        String idempotencyKey = "IDEM_ALREADY_SUCCESS_" + System.currentTimeMillis();

        // 1. Create user with 1000.00
        User user = new User(userCode, "Already Success User", userCode.toLowerCase() + "@test.com", 100000L);
        userRepository.saveAndFlush(user);

        // 2. Initiate valid payment of 400.00
        InitiatePaymentRequest payReq = new InitiatePaymentRequest(
                idempotencyKey, userCode, 40000L, "USD", "CREDIT_CARD", "Initial payment"
        );
        PaymentResponse response = paymentProcessingService.initiatePayment(payReq);
        assertEquals(PaymentStatus.SUCCESS, response.getStatus());

        User afterFirstCharge = userRepository.findByUserCode(userCode).orElseThrow();
        assertEquals(60000L, afterFirstCharge.getBalance(), "User balance after first payment must be 60000L");

        // 3. Directly invoke executePaymentAttempt on the already-SUCCESS payment
        PaymentTransaction returnedTx = paymentCoordinator.executePaymentAttempt(response.getPaymentId(), payReq);
        assertEquals(PaymentStatus.SUCCESS, returnedTx.getStatus());

        // 4. Verify user balance was NOT deducted a second time
        User afterSecondCall = userRepository.findByUserCode(userCode).orElseThrow();
        assertEquals(60000L, afterSecondCall.getBalance(),
                "User balance must remain 60000L: re-executing attempt on SUCCESS payment must short-circuit without charging again");
    }

    @Test
    @DisplayName("4. Async worker path: calling attempt on already-SUCCESS payment causes no second deduction")
    void testAsyncWorkerPath_AlreadySuccessPayment_NoSecondDeduction() {
        String userCode = "USR_ASYNC_DEDUP_" + System.currentTimeMillis();
        String idempotencyKey = "IDEM_ASYNC_DEDUP_" + System.currentTimeMillis();

        // 1. Create user with 1000.00
        User user = new User(userCode, "Async User", userCode.toLowerCase() + "@test.com", 100000L);
        userRepository.saveAndFlush(user);

        // 2. Create sync payment that succeeds
        InitiatePaymentRequest payReq = new InitiatePaymentRequest(
                idempotencyKey, userCode, 35000L, "USD", "CREDIT_CARD", "Async dedup payment"
        );
        PaymentResponse response = paymentProcessingService.initiatePayment(payReq);
        assertEquals(PaymentStatus.SUCCESS, response.getStatus());

        User userAfterSuccess = userRepository.findByUserCode(userCode).orElseThrow();
        assertEquals(65000L, userAfterSuccess.getBalance());

        // 3. Dispatch an async worker message for the same already-SUCCESS payment
        EventMessage msg = new EventMessage(
                "EVT_DEDUP_1", response.getPaymentId(), idempotencyKey, userCode, 35000L, "USD", "CREDIT_CARD", "Async dedup payment"
        );
        boolean workerResult = paymentProcessingService.processWorkerMessage(msg, "WORKER_P1");
        assertTrue(workerResult, "Worker must report success for already-completed payment");

        // 4. Verify balance is still 65000L (zero double deduction)
        User userAfterWorker = userRepository.findByUserCode(userCode).orElseThrow();
        assertEquals(65000L, userAfterWorker.getBalance(),
                "User balance must remain 65000L after duplicate async worker execution");
    }

    @Test
    @DisplayName("5. Crash after payment commit and before key update: recovery marks key COMPLETED, replay returns saved response, no second charge")
    void testCrashBetweenPaymentCommitAndKeyUpdate_RecoveryMarksKeyCompletedAndReplayDoesNotChargeAgain() throws Exception {
        String userCode = "USR_CRASH_TEST_" + System.currentTimeMillis();
        String paymentId = "PAY_CRASH_TEST_" + System.currentTimeMillis();
        String idempotencyKey = "IDEM_CRASH_TEST_" + System.currentTimeMillis();

        // 1. Create user with 1000.00
        User user = new User(userCode, "Crash Recovery Customer", userCode.toLowerCase() + "@test.com", 100000L);
        userRepository.saveAndFlush(user);

        // 2. Commit payment to SUCCESS and deduct user balance
        PaymentTransaction tx = new PaymentTransaction(
                paymentId, idempotencyKey, userCode, 20000L, "USD", "CREDIT_CARD", "Crash scenario"
        );
        paymentRepository.saveAndFlush(tx);
        paymentCoordinator.commitPaymentProcessing(paymentId);

        InitiatePaymentRequest payReq = new InitiatePaymentRequest(
                idempotencyKey, userCode, 20000L, "USD", "CREDIT_CARD", "Crash scenario"
        );
        paymentCoordinator.executePaymentAttempt(paymentId, payReq);

        User userAfterDeduction = userRepository.findByUserCode(userCode).orElseThrow();
        assertEquals(80000L, userAfterDeduction.getBalance(), "Balance deducted by payment commit");

        // 3. Simulate crash before idempotency key update: key remains IN_PROGRESS with started_at in the past
        String hash = idempotencyService.computePayloadHash(payReq);
        Instant tenMinutesAgo = Instant.now().minus(Duration.ofMinutes(10));
        IdempotencyKeyRecord stuckKey = new IdempotencyKeyRecord(
                API_KEY, idempotencyKey, hash, tenMinutesAgo, Instant.now().plus(Duration.ofHours(24))
        );
        stuckKey.setStatus(IdempotencyKeyRecord.Status.IN_PROGRESS);
        idempotencyKeyRepository.saveAndFlush(stuckKey);

        // 4. Trigger recovery job with 5-minute threshold
        int recovered = idempotencyService.recoverStuckKeys(Instant.now(), Duration.ofMinutes(5));
        assertTrue(recovered >= 1, "Recovery job must recover the stuck key");

        // 5. Verify the key is now COMPLETED
        IdempotencyKeyRecord recoveredKey = idempotencyKeyRepository.findByApiKeyAndKeyValue(API_KEY, idempotencyKey).orElseThrow();
        assertEquals(IdempotencyKeyRecord.Status.COMPLETED, recoveredKey.getStatus());
        assertEquals(200, recoveredKey.getHttpStatusCode());
        assertNotNull(recoveredKey.getResponseBody());
        assertTrue(recoveredKey.getResponseBody().contains(paymentId));

        // 6. Submit a replay request with the same idempotency key and payload
        mockMvc.perform(post("/api/v1/payments")
                        .header("X-Api-Key", API_KEY)
                        .header("X-Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payReq)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.paymentId").value(paymentId))
                .andExpect(jsonPath("$.isCachedIdempotentResponse").value(true));

        // 7. Verify balance is STILL 80000L (zero double charge on replay)
        User userFinal = userRepository.findByUserCode(userCode).orElseThrow();
        assertEquals(80000L, userFinal.getBalance(),
                "User balance must remain 80000L; no second charge allowed on idempotency replay");
    }

    @Test
    @DisplayName("6. Failed attempt followed by a retry must not cause an optimistic lock version conflict")
    void testFailedAttemptRetry_DoesNotCauseVersionConflict() {
        String userCode = "USR_VERSION_TEST_" + System.currentTimeMillis();
        String paymentId = "PAY_VERSION_TEST_" + System.currentTimeMillis();
        String idempotencyKey = "IDEM_VERSION_TEST_" + System.currentTimeMillis();

        // 1. Create user with 1000.00
        User user = new User(userCode, "Version Customer", userCode.toLowerCase() + "@test.com", 100000L);
        userRepository.saveAndFlush(user);

        // 2. Insert payment record (initial version in DB)
        PaymentTransaction tx = new PaymentTransaction(
                paymentId, idempotencyKey, userCode, 15000L, "USD", "CREDIT_CARD", "Version conflict test"
        );
        PaymentTransaction initialSaved = paymentRepository.saveAndFlush(tx);
        Long v0 = initialSaved.getLockVersion();

        // 3. Commit PROCESSING in its own transaction (version increments)
        PaymentTransaction processingTx = paymentCoordinator.commitPaymentProcessing(paymentId);
        Long v1 = processingTx.getLockVersion();
        assertTrue(v1 > (v0 != null ? v0 : -1), "Version must increment on PROCESSING commit");

        // 4. Simulate Attempt 1 failure: simulate error during attempt
        doThrow(new ServiceUnavailableException("Attempt 1 gateway failure"))
                .doNothing()
                .when(gatewaySimulationService).simulateGatewayCall(any(), any());

        InitiatePaymentRequest req = new InitiatePaymentRequest(
                idempotencyKey, userCode, 15000L, "USD", "CREDIT_CARD", "Version conflict test"
        );

        // Attempt 1 fails and rolls back
        assertThrows(ServiceUnavailableException.class, () -> paymentCoordinator.executePaymentAttempt(paymentId, req));

        // Record attempt failure in its own transaction
        PaymentTransaction recordedTx = paymentCoordinator.recordAttemptFailure(paymentId, 1, "Attempt 1 gateway failure");
        Long v2 = recordedTx.getLockVersion();
        assertTrue(v2 > v1, "Version must increment on recordAttemptFailure commit");

        // 5. Attempt 2 reloads fresh row via SELECT FOR UPDATE and succeeds without version conflict
        assertDoesNotThrow(() -> {
            PaymentTransaction succeededTx = paymentCoordinator.executePaymentAttempt(paymentId, req);
            assertEquals(PaymentStatus.SUCCESS, succeededTx.getStatus());
            Long v3 = succeededTx.getLockVersion();
            assertTrue(v3 > v2, "Version must increment on final SUCCESS commit");
        });

        // 6. Verify final balance: deducted exactly once (100000 - 15000 = 85000)
        User reloadedUser = userRepository.findByUserCode(userCode).orElseThrow();
        assertEquals(85000L, reloadedUser.getBalance());
    }
}
