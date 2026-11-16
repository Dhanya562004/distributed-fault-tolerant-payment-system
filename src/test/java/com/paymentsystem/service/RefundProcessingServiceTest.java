package com.paymentsystem.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsystem.dto.request.ProcessRefundRequest;
import com.paymentsystem.dto.response.RefundResponse;
import com.paymentsystem.entity.IdempotencyKeyRecord;
import com.paymentsystem.entity.PaymentStatus;
import com.paymentsystem.entity.PaymentTransaction;
import com.paymentsystem.entity.RefundTransaction;
import com.paymentsystem.entity.User;
import com.paymentsystem.exception.InvalidTransactionStateException;
import com.paymentsystem.exception.ResourceNotFoundException;
import com.paymentsystem.repository.PaymentTransactionRepository;
import com.paymentsystem.repository.RefundTransactionRepository;
import com.paymentsystem.repository.UserRepository;
import com.paymentsystem.service.impl.RefundProcessingServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RefundProcessingServiceTest {

    @Mock
    private RefundTransactionRepository refundRepository;

    @Mock
    private PaymentTransactionRepository paymentRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AuditService auditService;

    @Mock
    private IdempotencyService idempotencyService;

    private ObjectMapper objectMapper = new ObjectMapper();

    private RefundProcessingService refundService;

    @BeforeEach
    void setUp() {
        refundService = new RefundProcessingServiceImpl(
                refundRepository,
                paymentRepository,
                userRepository,
                auditService,
                idempotencyService,
                objectMapper
        );
    }

    @Test
    @DisplayName("Should successfully process valid refund and credit user balance")
    void testProcessRefund_Success() {
        String paymentId = "PAY_1001";
        String userId = "USR_BOB";
        Long paymentAmount = 20000L;
        Long refundAmount = 10000L;

        PaymentTransaction payment = new PaymentTransaction(paymentId, "IDEM_1001", userId, paymentAmount, "USD", "CREDIT_CARD", "Test payment");
        payment.setStatus(PaymentStatus.SUCCESS);

        User user = new User(userId, "Bob", "bob@example.com", 50000L);

        ProcessRefundRequest request = new ProcessRefundRequest(paymentId, "IDEM_REF_1001", refundAmount, "Customer requested partial refund");

        when(idempotencyService.tryAcquireOrGet(eq("IDEM_REF_1001"), any())).thenReturn(Optional.empty());
        when(paymentRepository.findByPaymentId(paymentId)).thenReturn(Optional.of(payment));
        when(refundRepository.findByPaymentId(paymentId)).thenReturn(List.of());
        when(userRepository.findByUserCode(userId)).thenReturn(Optional.of(user));

        RefundResponse response = refundService.processRefund(request);

        assertNotNull(response);
        assertNotNull(response.getRefundId());
        assertEquals(refundAmount, response.getAmount());
        assertEquals(PaymentStatus.REFUNDED, response.getStatus());

        verify(refundRepository, times(1)).save(any(RefundTransaction.class));
        verify(userRepository, times(1)).save(argThat(u -> u.getBalance() == 60000L));
        verify(idempotencyService, times(1)).markCompleted(eq("IDEM_REF_1001"), any(), eq(200));
    }

    @Test
    @DisplayName("Should throw InvalidTransactionStateException when refunding a FAILED payment")
    void testProcessRefund_FailedPayment_ThrowsException() {
        String paymentId = "PAY_FAILED_1002";
        ProcessRefundRequest request = new ProcessRefundRequest(paymentId, "IDEM_REF_1002", 5000L, "Invalid refund");

        PaymentTransaction payment = new PaymentTransaction(paymentId, "IDEM_1002", "USR_BOB", 10000L, "USD", "CREDIT_CARD", "Test");
        payment.setStatus(PaymentStatus.FAILED);

        when(idempotencyService.tryAcquireOrGet(eq("IDEM_REF_1002"), any())).thenReturn(Optional.empty());
        when(paymentRepository.findByPaymentId(paymentId)).thenReturn(Optional.of(payment));

        assertThrows(InvalidTransactionStateException.class, () -> refundService.processRefund(request));
    }

    @Test
    @DisplayName("Should throw InvalidTransactionStateException when cumulative refund exceeds payment amount")
    void testProcessRefund_ExceedsOriginalAmount_ThrowsException() {
        String paymentId = "PAY_1003";
        Long paymentAmount = 10000L;

        PaymentTransaction payment = new PaymentTransaction(paymentId, "IDEM_1003", "USR_BOB", paymentAmount, "USD", "CREDIT_CARD", "Test");
        payment.setStatus(PaymentStatus.SUCCESS);

        RefundTransaction priorRefund = new RefundTransaction("REF_001", paymentId, "USR_BOB", 8000L, PaymentStatus.REFUNDED, "Prior partial refund");

        ProcessRefundRequest request = new ProcessRefundRequest(paymentId, "IDEM_REF_1003", 3000L, "Second refund attempt");

        when(idempotencyService.tryAcquireOrGet(eq("IDEM_REF_1003"), any())).thenReturn(Optional.empty());
        when(paymentRepository.findByPaymentId(paymentId)).thenReturn(Optional.of(payment));
        when(refundRepository.findByPaymentId(paymentId)).thenReturn(List.of(priorRefund));

        assertThrows(InvalidTransactionStateException.class, () -> refundService.processRefund(request));
    }

    @Test
    @DisplayName("Should return cached response on duplicate refund idempotency key")
    void testProcessRefund_DuplicateIdempotencyKey_ReturnsCachedResponse() throws Exception {
        String key = "IDEM_REF_CACHED";
        ProcessRefundRequest request = new ProcessRefundRequest("PAY_1004", key, 5000L, "Duplicate");

        RefundResponse expectedResponse = new RefundResponse();
        expectedResponse.setRefundId("REF_CACHED_001");
        expectedResponse.setPaymentId("PAY_1004");
        expectedResponse.setAmount(5000L);
        expectedResponse.setStatus(PaymentStatus.REFUNDED);

        IdempotencyKeyRecord cachedRecord = new IdempotencyKeyRecord(key, "hash");
        cachedRecord.setStatus(IdempotencyKeyRecord.Status.COMPLETED);
        cachedRecord.setResponseBody(objectMapper.writeValueAsString(expectedResponse));

        when(idempotencyService.tryAcquireOrGet(eq(key), any())).thenReturn(Optional.of(cachedRecord));

        RefundResponse actualResponse = refundService.processRefund(request);

        assertNotNull(actualResponse);
        assertEquals("REF_CACHED_001", actualResponse.getRefundId());
        verify(refundRepository, never()).save(any());
    }

    @Test
    @DisplayName("Pessimistic locking: processRefund invokes findByPaymentIdForUpdate and findByUserCodeForUpdate")
    void testProcessRefund_UsesPessimisticLock() {
        String paymentId = "PAY_PESS_99";
        String userId = "USR_ALICE";
        Long paymentAmount = 10000L;
        Long refundAmount = 4000L;

        PaymentTransaction payment = new PaymentTransaction(paymentId, "IDEM_PESS_99", userId, paymentAmount, "USD", "CREDIT_CARD", "Pessimistic Test");
        payment.setStatus(PaymentStatus.SUCCESS);
        User user = new User(userId, "Alice", "alice@example.com", 20000L);

        ProcessRefundRequest request = new ProcessRefundRequest(paymentId, "IDEM_REF_PESS_99", refundAmount, "Locking test");

        when(idempotencyService.tryAcquireOrGet(eq("IDEM_REF_PESS_99"), any())).thenReturn(Optional.empty());
        when(paymentRepository.findByPaymentIdForUpdate(paymentId)).thenReturn(Optional.of(payment));
        when(refundRepository.findByPaymentId(paymentId)).thenReturn(List.of());
        when(userRepository.findByUserCodeForUpdate(userId)).thenReturn(Optional.of(user));

        RefundResponse response = refundService.processRefund(request);

        assertNotNull(response);
        assertEquals(refundAmount, response.getAmount());
        verify(paymentRepository, times(1)).findByPaymentIdForUpdate(paymentId);
        verify(userRepository, times(1)).findByUserCodeForUpdate(userId);
    }
}
