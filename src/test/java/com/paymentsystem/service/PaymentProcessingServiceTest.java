package com.paymentsystem.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsystem.dto.request.InitiatePaymentRequest;
import com.paymentsystem.dto.response.PaymentResponse;
import com.paymentsystem.entity.PaymentStatus;
import com.paymentsystem.entity.PaymentTransaction;
import com.paymentsystem.entity.User;
import com.paymentsystem.repository.PaymentTransactionRepository;
import com.paymentsystem.repository.UserRepository;
import com.paymentsystem.service.impl.PaymentProcessingServiceImpl;
import com.paymentsystem.worker.DistributedQueueManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentProcessingServiceTest {

    @Mock
    private PaymentTransactionRepository paymentRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private GatewaySimulationService gatewaySimulationService;

    @Mock
    private AuditService auditService;

    @Mock
    private MetricsService metricsService;

    @Mock
    private DistributedQueueManager queueManager;

    private ObjectMapper objectMapper = new ObjectMapper();

    private PaymentProcessingService paymentProcessingService;

    @BeforeEach
    void setUp() {
        paymentProcessingService = new PaymentProcessingServiceImpl(
                paymentRepository,
                userRepository,
                idempotencyService,
                gatewaySimulationService,
                auditService,
                metricsService,
                queueManager,
                objectMapper
        );
    }

    @Test
    @DisplayName("Should successfully process payment when user balance is sufficient")
    void testInitiatePayment_Success() {
        String key = "IDEM_TEST_2001";
        InitiatePaymentRequest req = new InitiatePaymentRequest(key, "USR_ALICE", 10000L, "USD", "CREDIT_CARD", "Order payment");

        User user = new User("USR_ALICE", "Alice", "alice@example.com", 50000L);

        when(idempotencyService.tryAcquireOrGet(key, req)).thenReturn(Optional.empty());
        when(userRepository.findByUserCode("USR_ALICE")).thenReturn(Optional.of(user));
        when(paymentRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            PaymentTransaction tx = invocation.getArgument(0);
            lenient().when(paymentRepository.findByPaymentId(tx.getPaymentId())).thenReturn(Optional.of(tx));
            lenient().when(paymentRepository.findByPaymentIdForUpdate(tx.getPaymentId())).thenReturn(Optional.of(tx));
            return tx;
        });

        PaymentResponse response = paymentProcessingService.initiatePayment(req);

        assertNotNull(response);
        assertEquals(PaymentStatus.SUCCESS, response.getStatus());
        assertEquals("USR_ALICE", response.getUserId());
        assertEquals(10000L, response.getAmount());
        assertEquals(40000L, user.getBalance());

        verify(metricsService, times(1)).incrementSuccessfulTransactions();
        verify(auditService, atLeastOnce()).recordAuditLog(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Should fail payment when user balance is insufficient")
    void testInitiatePayment_InsufficientBalance() {
        String key = "IDEM_TEST_2002";
        InitiatePaymentRequest req = new InitiatePaymentRequest(key, "USR_BOB", 100000L, "USD", "CREDIT_CARD", "Overbalance order");

        User user = new User("USR_BOB", "Bob", "bob@example.com", 20000L);

        when(idempotencyService.tryAcquireOrGet(key, req)).thenReturn(Optional.empty());
        when(userRepository.findByUserCode("USR_BOB")).thenReturn(Optional.of(user));
        when(paymentRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            PaymentTransaction tx = invocation.getArgument(0);
            lenient().when(paymentRepository.findByPaymentId(tx.getPaymentId())).thenReturn(Optional.of(tx));
            lenient().when(paymentRepository.findByPaymentIdForUpdate(tx.getPaymentId())).thenReturn(Optional.of(tx));
            return tx;
        });

        PaymentResponse response = paymentProcessingService.initiatePayment(req);

        assertNotNull(response);
        assertEquals(PaymentStatus.FAILED, response.getStatus());
        assertTrue(response.getFailureReason().contains("Insufficient user account balance"));
        assertEquals(0, response.getRetryCount(), "Business failures must fail fast and NOT be retried");

        verify(metricsService, times(1)).incrementFailedTransactions();
        verify(metricsService, never()).recordRetry();
    }

    @Test
    @DisplayName("Should return paginated payment records")
    void testGetAllPayments_Pagination() {
        com.paymentsystem.entity.PaymentTransaction tx1 = new com.paymentsystem.entity.PaymentTransaction("PAY_PAG_1", "IDEM_1", "USR_ALICE", 5000L, "USD", "CREDIT_CARD", "desc 1");
        com.paymentsystem.entity.PaymentTransaction tx2 = new com.paymentsystem.entity.PaymentTransaction("PAY_PAG_2", "IDEM_2", "USR_ALICE", 7500L, "USD", "CREDIT_CARD", "desc 2");
        com.paymentsystem.entity.PaymentTransaction tx3 = new com.paymentsystem.entity.PaymentTransaction("PAY_PAG_3", "IDEM_3", "USR_ALICE", 10000L, "USD", "CREDIT_CARD", "desc 3");

        when(paymentRepository.findAll()).thenReturn(java.util.List.of(tx1, tx2, tx3));

        java.util.List<PaymentResponse> pageResult = paymentProcessingService.getAllPayments(0, 2);

        assertNotNull(pageResult);
        assertEquals(2, pageResult.size());
    }
}
