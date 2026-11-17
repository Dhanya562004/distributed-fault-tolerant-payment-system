package com.paymentsystem.service;
import com.paymentsystem.payment.PaymentProcessingService;
import com.paymentsystem.idempotency.IdempotencyService;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsystem.dto.request.InitiatePaymentRequest;
import com.paymentsystem.dto.response.PaymentResponse;
import com.paymentsystem.payment.PaymentStatus;
import com.paymentsystem.payment.PaymentTransaction;
import com.paymentsystem.entity.User;
import com.paymentsystem.exception.PaymentProcessingException;
import com.paymentsystem.payment.PaymentTransactionRepository;
import com.paymentsystem.repository.UserRepository;
import com.paymentsystem.payment.PaymentProcessingServiceImpl;
import com.paymentsystem.outbox.DistributedQueueManager;
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
class FaultToleranceRetryTest {

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
    @DisplayName("Should recover and succeed on second attempt after transient gateway failure")
    void testRetryRecovery_TransientGatewayError() {
        String key = "IDEM_RETRY_3001";
        InitiatePaymentRequest req = new InitiatePaymentRequest(key, "USR_CHARLIE", 5000L, "USD", "DEBIT_CARD", "Retry test");
        User user = new User("USR_CHARLIE", "Charlie", "charlie@example.com", 100000L);

        when(idempotencyService.tryAcquireOrGet(key, req)).thenReturn(Optional.empty());
        when(userRepository.findByUserCode("USR_CHARLIE")).thenReturn(Optional.of(user));
        when(paymentRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            PaymentTransaction tx = invocation.getArgument(0);
            lenient().when(paymentRepository.findByPaymentId(tx.getPaymentId())).thenReturn(Optional.of(tx));
            lenient().when(paymentRepository.findByPaymentIdForUpdate(tx.getPaymentId())).thenReturn(Optional.of(tx));
            return tx;
        });

        doThrow(new PaymentProcessingException("Gateway Socket Timeout"))
                .doNothing()
                .when(gatewaySimulationService).simulateGatewayCall(any(), any());

        PaymentResponse response = paymentProcessingService.initiatePayment(req);

        assertNotNull(response);
        assertEquals(PaymentStatus.SUCCESS, response.getStatus());
        assertEquals(1, response.getRetryCount());
        verify(metricsService, times(1)).recordRetry();
        verify(metricsService, times(1)).incrementSuccessfulTransactions();
    }
}
