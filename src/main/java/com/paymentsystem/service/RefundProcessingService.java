package com.paymentsystem.service;

import com.paymentsystem.dto.request.ProcessRefundRequest;
import com.paymentsystem.dto.response.RefundResponse;

import java.util.List;

public interface RefundProcessingService {
    RefundResponse processRefund(ProcessRefundRequest request);
    RefundResponse getRefundStatus(String refundId);
    List<RefundResponse> getRefundsForPayment(String paymentId);
}
