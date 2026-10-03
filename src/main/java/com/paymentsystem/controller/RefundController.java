package com.paymentsystem.controller;

import com.paymentsystem.dto.request.ProcessRefundRequest;
import com.paymentsystem.dto.response.RefundResponse;
import com.paymentsystem.service.RefundProcessingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/refunds")
@Tag(name = "Refund Management", description = "Endpoints for processing and retrieving refund transactions")
public class RefundController {

    private final RefundProcessingService refundProcessingService;

    @Autowired
    public RefundController(RefundProcessingService refundProcessingService) {
        this.refundProcessingService = refundProcessingService;
    }

    @PostMapping
    @Operation(summary = "Process Refund Transaction", description = "Refunds an existing successful payment transaction and restores account balance")
    public ResponseEntity<RefundResponse> processRefund(
            @RequestHeader(value = "X-Idempotency-Key", required = false) String headerIdempotencyKey,
            @Valid @RequestBody ProcessRefundRequest request) {

        if (headerIdempotencyKey != null && !headerIdempotencyKey.trim().isEmpty()) {
            request.setIdempotencyKey(headerIdempotencyKey.trim());
        }

        RefundResponse response = refundProcessingService.processRefund(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{refundId}")
    @Operation(summary = "Fetch Refund Details", description = "Retrieves refund transaction record by refundId")
    public ResponseEntity<RefundResponse> getRefundStatus(@PathVariable String refundId) {
        return ResponseEntity.ok(refundProcessingService.getRefundStatus(refundId));
    }

    @GetMapping("/payment/{paymentId}")
    @Operation(summary = "List Payment Refunds", description = "Retrieves refund history for a given payment ID")
    public ResponseEntity<List<RefundResponse>> getRefundsForPayment(@PathVariable String paymentId) {
        return ResponseEntity.ok(refundProcessingService.getRefundsForPayment(paymentId));
    }
}
