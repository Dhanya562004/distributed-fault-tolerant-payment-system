package com.paymentsystem.service.impl;

import com.paymentsystem.dto.request.ProcessRefundRequest;
import com.paymentsystem.dto.response.RefundResponse;
import com.paymentsystem.entity.PaymentStatus;
import com.paymentsystem.entity.PaymentTransaction;
import com.paymentsystem.entity.RefundTransaction;
import com.paymentsystem.entity.User;
import com.paymentsystem.exception.InvalidTransactionStateException;
import com.paymentsystem.exception.ResourceNotFoundException;
import com.paymentsystem.repository.PaymentTransactionRepository;
import com.paymentsystem.repository.RefundTransactionRepository;
import com.paymentsystem.repository.UserRepository;
import com.paymentsystem.service.AuditService;
import com.paymentsystem.service.IdempotencyService;
import com.paymentsystem.service.RefundProcessingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class RefundProcessingServiceImpl implements RefundProcessingService {

    private static final Logger logger = LoggerFactory.getLogger(RefundProcessingServiceImpl.class);

    private final RefundTransactionRepository refundRepository;
    private final PaymentTransactionRepository paymentRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final IdempotencyService idempotencyService;

    @Autowired
    public RefundProcessingServiceImpl(RefundTransactionRepository refundRepository,
                                       PaymentTransactionRepository paymentRepository,
                                       UserRepository userRepository,
                                       AuditService auditService,
                                       IdempotencyService idempotencyService) {
        this.refundRepository = refundRepository;
        this.paymentRepository = paymentRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.idempotencyService = idempotencyService;
    }

    @Override
    @Transactional
    public RefundResponse processRefund(ProcessRefundRequest request) {
        idempotencyService.tryAcquireOrGet(request.getIdempotencyKey(), request);

        PaymentTransaction payment = paymentRepository.findByPaymentId(request.getPaymentId())
                .orElseThrow(() -> new ResourceNotFoundException("Payment transaction not found for ID: " + request.getPaymentId()));

        if (payment.getStatus() != PaymentStatus.SUCCESS) {
            throw new InvalidTransactionStateException("Cannot refund transaction in state [" + payment.getStatus() + "]. Only SUCCESS transactions are eligible for refund.");
        }

        if (request.getAmount().compareTo(payment.getAmount()) > 0) {
            throw new InvalidTransactionStateException("Refund amount [" + request.getAmount() + "] exceeds original payment amount [" + payment.getAmount() + "].");
        }

        String refundId = "REF_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();

        RefundTransaction refund = new RefundTransaction(
                refundId,
                payment.getPaymentId(),
                payment.getUserId(),
                request.getAmount(),
                PaymentStatus.REFUNDED,
                request.getReason()
        );
        refundRepository.save(refund);

        PaymentStatus prevStatus = payment.getStatus();
        payment.setStatus(PaymentStatus.REFUNDED);
        paymentRepository.save(payment);

        Optional<User> userOpt = userRepository.findByUserCode(payment.getUserId());
        userOpt.ifPresent(user -> {
            user.setBalance(user.getBalance().add(request.getAmount()));
            userRepository.save(user);
        });

        auditService.recordAuditLog(
                payment.getPaymentId(),
                request.getIdempotencyKey(),
                payment.getUserId(),
                prevStatus.name(),
                PaymentStatus.REFUNDED.name(),
                "PROCESS_REFUND",
                "API_HANDLER",
                "Refund of " + request.getAmount() + " processed successfully",
                Map.of("refundId", refundId, "reason", request.getReason() != null ? request.getReason() : "Customer request")
        );

        RefundResponse response = mapToResponse(refund);
        idempotencyService.markCompleted(request.getIdempotencyKey(), response, 200);
        return response;
    }

    @Override
    public RefundResponse getRefundStatus(String refundId) {
        RefundTransaction refund = refundRepository.findByRefundId(refundId)
                .orElseThrow(() -> new ResourceNotFoundException("Refund transaction not found for ID: " + refundId));
        return mapToResponse(refund);
    }

    @Override
    public List<RefundResponse> getRefundsForPayment(String paymentId) {
        return refundRepository.findByPaymentId(paymentId).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    private RefundResponse mapToResponse(RefundTransaction refund) {
        RefundResponse response = new RefundResponse();
        response.setRefundId(refund.getRefundId());
        response.setPaymentId(refund.getPaymentId());
        response.setUserId(refund.getUserId());
        response.setAmount(refund.getAmount());
        response.setStatus(refund.getStatus());
        response.setReason(refund.getReason());
        response.setCreatedAt(refund.getCreatedAt());
        return response;
    }
}
