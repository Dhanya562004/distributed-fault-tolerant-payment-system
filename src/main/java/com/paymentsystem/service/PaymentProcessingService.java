package com.paymentsystem.service;

import com.paymentsystem.dto.request.InitiatePaymentRequest;
import com.paymentsystem.dto.response.PaymentResponse;
import com.paymentsystem.entity.PaymentTransaction;
import com.paymentsystem.worker.EventMessage;

import java.util.List;

public interface PaymentProcessingService {
    PaymentResponse initiatePayment(InitiatePaymentRequest request);
    void executeSinglePaymentAttempt(PaymentTransaction tx, InitiatePaymentRequest request);
    boolean processWorkerMessage(EventMessage message, String workerId);
    PaymentResponse getPaymentStatus(String paymentId);
    List<PaymentResponse> getAllPayments();
    List<PaymentResponse> getPaymentsByUser(String userId);
    PaymentResponse mapToResponse(PaymentTransaction tx);
}
