package com.paymentsystem.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsystem.dto.request.InitiatePaymentRequest;
import com.paymentsystem.entity.User;
import com.paymentsystem.exception.InsufficientBalanceException;
import com.paymentsystem.exception.InvalidTransactionStateException;
import com.paymentsystem.exception.ResourceNotFoundException;
import com.paymentsystem.ledger.LedgerService;
import com.paymentsystem.outbox.OutboxEvent;
import com.paymentsystem.outbox.OutboxEventRepository;
import com.paymentsystem.repository.UserRepository;
import com.paymentsystem.service.GatewaySimulationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentTransactionCoordinator Unit Tests")
class PaymentTransactionCoordinatorUnitTest {

    @Mock
    private PaymentTransactionRepository paymentRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private GatewaySimulationService gatewaySimulationService;

    @Mock
    private LedgerService ledgerService;

    private ObjectMapper objectMapper;
    private Clock fixedClock;
    private PaymentTransactionCoordinator coordinator;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        fixedClock = Clock.fixed(Instant.parse("2026-11-16T12:00:00Z"), ZoneId.of("UTC"));
        coordinator = new PaymentTransactionCoordinator(
                paymentRepository,
                outboxEventRepository,
                userRepository,
                gatewaySimulationService,
                ledgerService,
                objectMapper,
                fixedClock
        );
    }

    @Test
    @DisplayName("executePaymentAttempt returns immediately if payment already SUCCESS or FAILED")
    void testExecutePaymentAttempt_AlreadyTerminal() {
        InitiatePaymentRequest req = new InitiatePaymentRequest("IDEM_1", "USR_1", 1000L, "USD", "CARD", "desc");
        PaymentTransaction successTx = new PaymentTransaction("PAY_1", "IDEM_1", "USR_1", 1000L, "USD", "CARD", "desc");
        successTx.transitionTo(PaymentStatus.PROCESSING);
        successTx.transitionTo(PaymentStatus.SUCCESS);

        when(paymentRepository.findByPaymentIdForUpdate("PAY_1")).thenReturn(Optional.of(successTx));

        PaymentTransaction result1 = coordinator.executePaymentAttempt("PAY_1", req);
        assertEquals(PaymentStatus.SUCCESS, result1.getStatus());
        verify(userRepository, never()).findByUserCodeForUpdate(any());

        PaymentTransaction failedTx = new PaymentTransaction("PAY_2", "IDEM_2", "USR_1", 1000L, "USD", "CARD", "desc");
        failedTx.transitionTo(PaymentStatus.FAILED);
        when(paymentRepository.findByPaymentIdForUpdate("PAY_2")).thenReturn(Optional.of(failedTx));

        assertThrows(InvalidTransactionStateException.class, () -> coordinator.executePaymentAttempt("PAY_2", req));
    }

    @Test
    @DisplayName("executePaymentAttempt throws ResourceNotFoundException when payment not found")
    void testExecutePaymentAttempt_NotFound() {
        InitiatePaymentRequest req = new InitiatePaymentRequest("IDEM_3", "USR_MISSING", 1000L, "USD", "CARD", "desc");
        when(paymentRepository.findByPaymentIdForUpdate("PAY_MISSING")).thenReturn(Optional.empty());
        when(paymentRepository.findByPaymentId("PAY_MISSING")).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> coordinator.executePaymentAttempt("PAY_MISSING", req));
    }

    @Test
    @DisplayName("executePaymentAttempt throws InsufficientBalanceException when user has low balance")
    void testExecutePaymentAttempt_InsufficientBalance() {
        InitiatePaymentRequest req = new InitiatePaymentRequest("IDEM_4", "USR_POOR", 50000L, "USD", "CARD", "desc");
        PaymentTransaction tx = new PaymentTransaction("PAY_4", "IDEM_4", "USR_POOR", 50000L, "USD", "CARD", "desc");
        tx.transitionTo(PaymentStatus.PROCESSING);
        when(paymentRepository.findByPaymentIdForUpdate("PAY_4")).thenReturn(Optional.of(tx));

        User user = new User("USR_POOR", "Poor User", "poor@example.com", 1000L);
        when(userRepository.findByUserCodeForUpdate("USR_POOR")).thenReturn(Optional.of(user));

        assertThrows(InsufficientBalanceException.class, () -> coordinator.executePaymentAttempt("PAY_4", req));
    }

    @Test
    @DisplayName("executePaymentAttempt deducts balance, persists outbox, and records ledger on success")
    void testExecutePaymentAttempt_SuccessFlow() {
        InitiatePaymentRequest req = new InitiatePaymentRequest("IDEM_5", "USR_RICH", 5000L, "USD", "CARD", "desc");
        PaymentTransaction tx = new PaymentTransaction("PAY_5", "IDEM_5", "USR_RICH", 5000L, "USD", "CARD", "desc");
        tx.transitionTo(PaymentStatus.PROCESSING);
        when(paymentRepository.findByPaymentIdForUpdate("PAY_5")).thenReturn(Optional.of(tx));
        when(paymentRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        User user = new User("USR_RICH", "Rich User", "rich@example.com", 100000L);
        when(userRepository.findByUserCodeForUpdate("USR_RICH")).thenReturn(Optional.of(user));
        when(outboxEventRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        PaymentTransaction result = coordinator.executePaymentAttempt("PAY_5", req);
        assertEquals(PaymentStatus.SUCCESS, result.getStatus());
        assertEquals(95000L, user.getBalance());
        verify(userRepository).save(user);
        verify(outboxEventRepository).save(any(OutboxEvent.class));
        verify(ledgerService).recordPaymentEntries("PAY_5", "USR_RICH", 5000L, "USD", "Payment debit for PAY_5");
    }

    @Test
    @DisplayName("commitPaymentProcessing and commitPaymentFailure")
    void testCommitPaymentProcessingAndFailure() {
        PaymentTransaction tx = new PaymentTransaction("PAY_PROC", "IDEM_PR", "USR_1", 1000L, "USD", "CARD", "desc");
        when(paymentRepository.findByPaymentIdForUpdate("PAY_PROC")).thenReturn(Optional.of(tx));
        when(paymentRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        coordinator.commitPaymentProcessing("PAY_PROC");
        assertEquals(PaymentStatus.PROCESSING, tx.getStatus());

        // recordAttemptFailure while in PROCESSING state
        coordinator.recordAttemptFailure("PAY_PROC", 1, "Transient network blip");
        assertEquals("Transient network blip", tx.getFailureReason());
        assertEquals(1, tx.getRetryCount());

        // commitPaymentFailure transitions to FAILED
        coordinator.commitPaymentFailure("PAY_PROC", "Simulated fatal failure", 3);
        assertEquals(PaymentStatus.FAILED, tx.getStatus());
        assertEquals("Simulated fatal failure", tx.getFailureReason());
        assertEquals(3, tx.getRetryCount());
    }

    @Test
    @DisplayName("commitPaymentSuccess and commitPaymentFailure with tx object")
    void testCommitSuccessAndFailureWithTxObject() {
        PaymentTransaction tx = new PaymentTransaction("PAY_OBJ", "IDEM_O", "USR_1", 1000L, "USD", "CARD", "desc");
        when(outboxEventRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(paymentRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        OutboxEvent successEvt = coordinator.commitPaymentSuccess(tx);
        assertNotNull(successEvt);
        assertEquals(PaymentStatus.SUCCESS, tx.getStatus());

        PaymentTransaction txFail = new PaymentTransaction("PAY_FAIL_OBJ", "IDEM_FO", "USR_1", 1000L, "USD", "CARD", "desc");
        coordinator.commitPaymentFailure(txFail, "Failure reason");
        assertEquals(PaymentStatus.FAILED, txFail.getStatus());
    }

    @Test
    @DisplayName("Coordinator setters and flags")
    void testCoordinatorFlags() {
        coordinator.setDefaultMaxAttempts(10);
        assertEquals(10, coordinator.getDefaultMaxAttempts());
        coordinator.setLedgerEnabled(false);
        assertFalse(coordinator.isLedgerEnabled());
        coordinator.setLedgerEnabled(true);
        assertTrue(coordinator.isLedgerEnabled());
    }

    @Test
    @DisplayName("commitPaymentProcessing and commitPaymentFailure short-circuit on terminal states")
    void testCommitShortCircuits() {
        // commitPaymentProcessing on already PROCESSING
        PaymentTransaction txProc = new PaymentTransaction("PAY_P", "IDEM_P", "USR_1", 1000L, "USD", "CARD", "desc");
        txProc.transitionTo(PaymentStatus.PROCESSING);
        when(paymentRepository.findByPaymentIdForUpdate("PAY_P")).thenReturn(Optional.of(txProc));
        PaymentTransaction r1 = coordinator.commitPaymentProcessing("PAY_P");
        assertEquals(PaymentStatus.PROCESSING, r1.getStatus());

        // commitPaymentProcessing on already SUCCESS
        PaymentTransaction txSucc = new PaymentTransaction("PAY_S", "IDEM_S", "USR_1", 1000L, "USD", "CARD", "desc");
        txSucc.transitionTo(PaymentStatus.PROCESSING);
        txSucc.transitionTo(PaymentStatus.SUCCESS);
        when(paymentRepository.findByPaymentIdForUpdate("PAY_S")).thenReturn(Optional.of(txSucc));
        PaymentTransaction r2 = coordinator.commitPaymentProcessing("PAY_S");
        assertEquals(PaymentStatus.SUCCESS, r2.getStatus());

        // commitPaymentProcessing on already FAILED
        PaymentTransaction txFail = new PaymentTransaction("PAY_F", "IDEM_F", "USR_1", 1000L, "USD", "CARD", "desc");
        txFail.transitionTo(PaymentStatus.FAILED);
        when(paymentRepository.findByPaymentIdForUpdate("PAY_F")).thenReturn(Optional.of(txFail));
        PaymentTransaction r3 = coordinator.commitPaymentProcessing("PAY_F");
        assertEquals(PaymentStatus.FAILED, r3.getStatus());

        // commitPaymentFailure on already SUCCESS
        PaymentTransaction r4 = coordinator.commitPaymentFailure("PAY_S", "Some failure", 1);
        assertEquals(PaymentStatus.SUCCESS, r4.getStatus());
    }
}
