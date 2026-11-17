package com.paymentsystem.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsystem.dto.request.InitiatePaymentRequest;
import com.paymentsystem.dto.response.PagedPaymentResponse;
import com.paymentsystem.dto.response.PagedResponse;
import com.paymentsystem.dto.response.PaymentResponse;
import com.paymentsystem.entity.User;
import com.paymentsystem.exception.InsufficientBalanceException;
import com.paymentsystem.exception.ResourceNotFoundException;
import com.paymentsystem.idempotency.IdempotencyKeyRecord;
import com.paymentsystem.idempotency.IdempotencyService;
import com.paymentsystem.outbox.DistributedQueueManager;
import com.paymentsystem.outbox.EventMessage;
import com.paymentsystem.repository.UserRepository;
import com.paymentsystem.service.AuditService;
import com.paymentsystem.service.GatewaySimulationService;
import com.paymentsystem.service.MetricsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentProcessing Comprehensive Unit Tests")
class PaymentProcessingComprehensiveUnitTest {

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

    @Mock
    private PaymentTransactionCoordinator paymentCoordinator;

    @Mock
    private PaymentPaginationService paginationService;

    @Mock
    private RefundTransactionRepository refundRepository;

    private ObjectMapper objectMapper;
    private PaymentProcessingServiceImpl paymentService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        paymentService = new PaymentProcessingServiceImpl(
                paymentRepository,
                userRepository,
                idempotencyService,
                gatewaySimulationService,
                auditService,
                metricsService,
                queueManager,
                objectMapper,
                paymentCoordinator,
                paginationService,
                refundRepository,
                null
        );
    }

    @Test
    @DisplayName("initiatePayment returns cached response when idempotency hit occurs")
    void testInitiatePayment_CachedHit() {
        InitiatePaymentRequest req = new InitiatePaymentRequest("IDEM_1", "USR_1", 1000L, "USD", "CARD", "desc");
        IdempotencyKeyRecord cachedRecord = new IdempotencyKeyRecord("key", "IDEM_1", "hash", Instant.now(), Instant.now().plusSeconds(3600));
        cachedRecord.setStatus(IdempotencyKeyRecord.Status.COMPLETED);
        cachedRecord.setResponseBody("{\"paymentId\":\"PAY_CACHED\",\"status\":\"SUCCESS\",\"amount\":1000}");
        cachedRecord.setHttpStatusCode(201);

        when(idempotencyService.tryAcquireOrGet(eq("IDEM_1"), any())).thenReturn(Optional.of(cachedRecord));

        PaymentResponse res = paymentService.initiatePayment(req);
        assertNotNull(res);
        assertTrue(res.getIsCachedIdempotentResponse());
        assertEquals("PAY_CACHED", res.getPaymentId());
    }

    @Test
    @DisplayName("initiatePayment sync processes with coordinator and completes idempotency key")
    void testInitiatePayment_SyncSuccess() {
        InitiatePaymentRequest req = new InitiatePaymentRequest("IDEM_2", "USR_1", 2000L, "USD", "CARD", "desc");
        when(idempotencyService.tryAcquireOrGet(eq("IDEM_2"), any())).thenReturn(Optional.empty());

        when(paymentRepository.saveAndFlush(any(PaymentTransaction.class))).thenAnswer(i -> i.getArgument(0));

        PaymentTransaction successTx = new PaymentTransaction("PAY_2", "IDEM_2", "USR_1", 2000L, "USD", "CARD", "desc");
        successTx.transitionTo(PaymentStatus.PROCESSING);
        successTx.transitionTo(PaymentStatus.SUCCESS);

        when(paymentCoordinator.executePaymentAttempt(anyString(), any(InitiatePaymentRequest.class))).thenReturn(successTx);

        PaymentResponse res = paymentService.initiatePayment(req);
        assertNotNull(res);
        assertEquals(PaymentStatus.SUCCESS, res.getStatus());
        verify(idempotencyService).markCompleted(eq("IDEM_2"), any(), eq(200));
    }

    @Test
    @DisplayName("initiatePayment async queues message and returns PENDING response")
    void testInitiatePayment_Async() {
        InitiatePaymentRequest req = new InitiatePaymentRequest("IDEM_ASYNC", "USR_1", 5000L, "USD", "CARD", "desc");
        req.setAsyncProcessing(true);

        when(idempotencyService.tryAcquireOrGet(eq("IDEM_ASYNC"), any())).thenReturn(Optional.empty());
        when(paymentRepository.saveAndFlush(any(PaymentTransaction.class))).thenAnswer(i -> i.getArgument(0));

        PaymentResponse res = paymentService.initiatePayment(req);
        assertNotNull(res);
        verify(queueManager).publish(eq("USR_1"), any(EventMessage.class));
        verify(paymentCoordinator).commitPaymentProcessing(anyString());
    }

    @Test
    @DisplayName("initiatePayment stops retries on non-retryable InsufficientBalanceException and returns FAILED response")
    void testInitiatePayment_NonRetryableBalanceException() {
        InitiatePaymentRequest req = new InitiatePaymentRequest("IDEM_FAIL", "USR_1", 100000L, "USD", "CARD", "desc");
        when(idempotencyService.tryAcquireOrGet(eq("IDEM_FAIL"), any())).thenReturn(Optional.empty());
        when(paymentRepository.saveAndFlush(any(PaymentTransaction.class))).thenAnswer(i -> i.getArgument(0));

        when(paymentCoordinator.executePaymentAttempt(anyString(), any(InitiatePaymentRequest.class)))
                .thenThrow(new InsufficientBalanceException("User has insufficient funds"));

        PaymentTransaction failedTx = new PaymentTransaction("PAY_FAIL", "IDEM_FAIL", "USR_1", 100000L, "USD", "CARD", "desc");
        failedTx.transitionTo(PaymentStatus.PROCESSING);
        failedTx.transitionTo(PaymentStatus.FAILED);
        failedTx.setFailureReason("User has insufficient funds");
        when(paymentCoordinator.commitPaymentFailure(anyString(), any(), anyInt())).thenReturn(failedTx);

        PaymentResponse res = paymentService.initiatePayment(req);
        assertNotNull(res);
        assertEquals(PaymentStatus.FAILED, res.getStatus());
        verify(paymentCoordinator).commitPaymentFailure(anyString(), any(), anyInt());
        verify(idempotencyService).markCompleted(eq("IDEM_FAIL"), any(), eq(500));
    }

    @Test
    @DisplayName("processWorkerMessage delegates to coordinator")
    void testProcessWorkerMessage() {
        EventMessage msg = new EventMessage("EVT_1", "PAY_WORKER", "IDEM_W", "USR_1", 1000L, "USD", "CARD", "desc");
        PaymentTransaction procTx1 = new PaymentTransaction("PAY_WORKER", "IDEM_W", "USR_1", 1000L, "USD", "CARD", "desc");
        procTx1.transitionTo(PaymentStatus.PROCESSING);

        when(paymentRepository.findByPaymentId("PAY_WORKER")).thenReturn(Optional.of(procTx1));
        when(paymentCoordinator.executePaymentAttempt(eq("PAY_WORKER"), any(InitiatePaymentRequest.class))).thenReturn(procTx1);

        boolean ok = paymentService.processWorkerMessage(msg, "WORKER_1");
        assertTrue(ok);

        EventMessage msg2 = new EventMessage("EVT_2", "PAY_WORKER_FAIL", "IDEM_W2", "USR_1", 1000L, "USD", "CARD", "desc");
        PaymentTransaction procTx = new PaymentTransaction("PAY_WORKER_FAIL", "IDEM_W2", "USR_1", 1000L, "USD", "CARD", "desc");
        procTx.transitionTo(PaymentStatus.PROCESSING);
        when(paymentRepository.findByPaymentId("PAY_WORKER_FAIL")).thenReturn(Optional.of(procTx));

        when(paymentCoordinator.executePaymentAttempt(eq("PAY_WORKER_FAIL"), any(InitiatePaymentRequest.class)))
                .thenThrow(new RuntimeException("Simulated worker error"));
        boolean fail = paymentService.processWorkerMessage(msg2, "WORKER_1");
        assertFalse(fail);
    }

    @Test
    @DisplayName("getPaymentStatus found vs ResourceNotFoundException")
    void testGetPaymentStatus() {
        PaymentTransaction tx = new PaymentTransaction("PAY_FIND", "IDEM_F", "USR_1", 100L, "USD", "CARD", "desc");
        when(paymentRepository.findByPaymentId("PAY_FIND")).thenReturn(Optional.of(tx));

        PaymentResponse found = paymentService.getPaymentStatus("PAY_FIND");
        assertEquals("PAY_FIND", found.getPaymentId());

        when(paymentRepository.findByPaymentId("PAY_MISSING")).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> paymentService.getPaymentStatus("PAY_MISSING"));
    }

    @Test
    @DisplayName("getTransactions keyset pagination delegation")
    void testGetTransactions_KeysetPagination() {
        PagedPaymentResponse mockPaged = new PagedPaymentResponse();
        when(paginationService.getTransactions(eq(20), eq("cursor"), eq("SUCCESS"), eq("2026-01-01"), eq("2026-12-31"), any(), any(), any()))
                .thenReturn(mockPaged);

        PagedPaymentResponse res = paymentService.getTransactions(20, "cursor", "SUCCESS", "2026-01-01", "2026-12-31", null, null, null);
        assertNotNull(res);
    }

    @Test
    @DisplayName("PaymentController endpoints")
    void testPaymentController() {
        PaymentProcessingService mockPaymentService = mock(PaymentProcessingService.class);
        PaymentController paymentController = new PaymentController(mockPaymentService);
        InitiatePaymentRequest req = new InitiatePaymentRequest("IDEM_CTRL", "USR_1", 500L, "USD", "CARD", "desc");
        PaymentResponse resp = new PaymentResponse();
        resp.setPaymentId("PAY_C");
        resp.setStatus(PaymentStatus.SUCCESS);
        resp.setIsCachedIdempotentResponse(true);

        when(mockPaymentService.initiatePayment(req)).thenReturn(resp);

        ResponseEntity<PaymentResponse> ctrlRes = paymentController.initiatePayment("IDEM_CTRL", req);
        assertEquals(201, ctrlRes.getStatusCode().value());
        assertEquals("true", ctrlRes.getHeaders().getFirst("Idempotent-Replayed"));

        // GET by ID
        when(mockPaymentService.getPaymentStatus("PAY_C")).thenReturn(resp);
        ResponseEntity<PaymentResponse> getRes = paymentController.getPaymentStatus("PAY_C");
        assertEquals(200, getRes.getStatusCode().value());

        // GET transactions
        PagedPaymentResponse pagedResp = new PagedPaymentResponse();
        when(mockPaymentService.getTransactions(10, "cursor", "SUCCESS", null, null, null, null, null)).thenReturn(pagedResp);
        ResponseEntity<PagedPaymentResponse> paged1 = paymentController.getTransactions(10, "cursor", "SUCCESS", null, null, null, null, null);
        assertEquals(200, paged1.getStatusCode().value());

        // GET all payments paged
        PagedResponse<PaymentResponse> allPaged = new PagedResponse<>();
        when(mockPaymentService.getAllPaymentsPaged(0, 10)).thenReturn(allPaged);
        ResponseEntity<PagedResponse<PaymentResponse>> paged2 = paymentController.getAllPayments(0, 10);
        assertEquals(200, paged2.getStatusCode().value());
    }

    @Test
    @DisplayName("RefundTransaction entity constructors, getters, setters")
    void testRefundTransactionEntity() {
        RefundTransaction refund = new RefundTransaction("REF_1", "PAY_1", "USR_1", 500L, PaymentStatus.SUCCESS, "refund desc");

        assertEquals("REF_1", refund.getRefundId());
        assertEquals("PAY_1", refund.getPaymentId());
        assertEquals("USR_1", refund.getUserId());
        assertEquals(500L, refund.getAmount());
        assertEquals(PaymentStatus.SUCCESS, refund.getStatus());
        assertEquals("refund desc", refund.getReason());

        refund.setRefundId("REF_2");
        refund.setPaymentId("PAY_2");
        refund.setUserId("USR_2");
        refund.setAmount(1000L);
        refund.setStatus(PaymentStatus.REFUNDED);
        refund.setReason("new reason");

        assertEquals("REF_2", refund.getRefundId());
        assertEquals("PAY_2", refund.getPaymentId());
        assertEquals("new reason", refund.getReason());
    }

    @Test
    @DisplayName("initiatePayment validation on null request and missing idempotency key")
    void testInitiatePayment_Validation() {
        assertThrows(IllegalArgumentException.class, () -> paymentService.initiatePayment(null));

        InitiatePaymentRequest reqNoKey = new InitiatePaymentRequest(null, "USR_1", 100L, "USD", "CARD", "desc");
        assertThrows(IllegalArgumentException.class, () -> paymentService.initiatePayment(reqNoKey));

        InitiatePaymentRequest reqBlankKey = new InitiatePaymentRequest("   ", "USR_1", 100L, "USD", "CARD", "desc");
        assertThrows(IllegalArgumentException.class, () -> paymentService.initiatePayment(reqBlankKey));
    }

    @Test
    @DisplayName("executeSinglePaymentAttempt provisions user when missing")
    void testExecuteSinglePaymentAttempt_NewUser() {
        PaymentTransaction tx = new PaymentTransaction("PAY_NEW_U", "IDEM_NU", "USR_NEW_99", 500L, "USD", "CARD", "desc");
        InitiatePaymentRequest req = new InitiatePaymentRequest("IDEM_NU", "USR_NEW_99", 500L, "USD", "CARD", "desc");

        when(userRepository.findByUserCodeForUpdate("USR_NEW_99")).thenReturn(Optional.empty());
        when(userRepository.findByUserCode("USR_NEW_99")).thenReturn(Optional.empty());

        paymentService.executeSinglePaymentAttempt(tx, req);
        verify(userRepository).save(argThat(u -> "USR_NEW_99".equals(u.getUserCode())));
    }

    @Test
    @DisplayName("getPaymentsByUser and getPaymentsByUserPaged boundary handling")
    void testGetPaymentsByUser_PaginationBoundaries() {
        PaymentTransaction tx1 = new PaymentTransaction("PAY_U1", "IDEM_1", "USR_TEST", 1000L, "USD", "CARD", "desc");
        when(paymentRepository.findByUserId("USR_TEST")).thenReturn(List.of(tx1));

        // Negative page, invalid sizes
        var page1 = paymentService.getPaymentsByUserPaged("USR_TEST", -1, -5);
        assertNotNull(page1);
        assertEquals(20, page1.getPagination().getLimit());

        var page2 = paymentService.getPaymentsByUserPaged("USR_TEST", 0, 500);
        assertNotNull(page2);
        assertEquals(20, page2.getPagination().getLimit());

        var list = paymentService.getPaymentsByUser("USR_TEST");
        assertNotNull(list);

        var listPaged = paymentService.getPaymentsByUser("USR_TEST", 0, 10);
        assertNotNull(listPaged);

        when(paymentRepository.findAll()).thenReturn(List.of(tx1));
        assertNotNull(paymentService.getAllPayments());
        assertNotNull(paymentService.getAllPayments(0, 10));
        assertNotNull(paymentService.getAllPaymentsPaged(-1, -5));
        assertNotNull(paymentService.getAllPaymentsPaged(0, 500));
    }

    @Test
    @DisplayName("PaymentTransaction entity lifecycle annotations and fields with pre-set values")
    void testPaymentTransactionLifecycle() {
        PaymentTransaction tx = new PaymentTransaction();
        tx.onCreate();
        assertEquals(PaymentStatus.PENDING, tx.getStatus());
        assertEquals(0, tx.getRetryCount());
        assertNotNull(tx.getCreatedAt());
        assertNotNull(tx.getUpdatedAt());

        // Pre-set values should not be overridden by onCreate
        Instant past = Instant.now().minusSeconds(100);
        PaymentTransaction txPreset = new PaymentTransaction("P1", "I1", "U1", 100L, "USD", "CARD", "desc");
        txPreset.setCreatedAt(past);
        txPreset.setUpdatedAt(past);
        txPreset.setRetryCount(5);
        txPreset.transitionTo(PaymentStatus.PROCESSING);
        txPreset.onCreate();
        assertEquals(past, txPreset.getCreatedAt());
        assertEquals(past, txPreset.getUpdatedAt());
        assertEquals(5, txPreset.getRetryCount());
        assertEquals(PaymentStatus.PROCESSING, txPreset.getStatus());

        tx.onUpdate();
        assertNotNull(tx.getUpdatedAt());

        // Test jitter policy setter
        paymentService.setRetryJitterPolicy(null);
        paymentService.setRetryJitterPolicy(new RetryJitterPolicy());
    }

    @Test
    @DisplayName("PaymentPaginationService mapToResponseDto covers REFUNDED, SUCCESS and FAILED")
    void testPaymentPaginationService_StatusMapping() {
        CursorCodecService codec = new CursorCodecService("test_secret", objectMapper);
        PaymentPaginationSecurityService sec = new PaymentPaginationSecurityService(false, "secret");
        PaymentPaginationService pagService = new PaymentPaginationService(paymentRepository, codec, sec, refundRepository);

        PaymentTransaction txRefunded = new PaymentTransaction("PAY_REF", "IDEM_R", "USR_1", 1000L, "USD", "CARD", "desc");
        txRefunded.setId(101L);
        txRefunded.transitionTo(PaymentStatus.PROCESSING);
        txRefunded.transitionTo(PaymentStatus.SUCCESS);
        txRefunded.transitionTo(PaymentStatus.REFUNDED);

        PaymentTransaction txSuccess = new PaymentTransaction("PAY_SUC", "IDEM_S", "USR_1", 2000L, "USD", "CARD", "desc");
        txSuccess.setId(102L);
        txSuccess.transitionTo(PaymentStatus.PROCESSING);
        txSuccess.transitionTo(PaymentStatus.SUCCESS);

        PaymentTransaction txFailed = new PaymentTransaction("PAY_FAIL", "IDEM_F", "USR_1", 3000L, "USD", "CARD", "desc");
        txFailed.setId(103L);
        txFailed.transitionTo(PaymentStatus.FAILED);

        when(refundRepository.sumRefundedByPaymentId("PAY_SUC")).thenReturn(500L);
        when(paymentRepository.findTransactionsKeyset(any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(txRefunded, txSuccess, txFailed));

        var res = pagService.getTransactions(10, null, null, null, null, null, null, null);
        assertNotNull(res);
        assertEquals(3, res.getData().size());
        assertEquals(1000L, res.getData().get(0).getRefundedAmount());
        assertEquals(0L, res.getData().get(0).getRemainingRefundable());

        assertEquals(500L, res.getData().get(1).getRefundedAmount());
        assertEquals(1500L, res.getData().get(1).getRemainingRefundable());

        assertEquals(0L, res.getData().get(2).getRefundedAmount());
        assertEquals(0L, res.getData().get(2).getRemainingRefundable());
    }
}
