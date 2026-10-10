package com.paymentsystem.controller;

import com.paymentsystem.dto.request.InitiatePaymentRequest;
import com.paymentsystem.dto.response.PaymentResponse;
import com.paymentsystem.service.PaymentProcessingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/payments")
@Tag(name = "Payment Management", description = "Endpoints for initiating and querying payment transactions")
public class PaymentController {

    private final PaymentProcessingService paymentProcessingService;

    @Autowired
    public PaymentController(PaymentProcessingService paymentProcessingService) {
        this.paymentProcessingService = paymentProcessingService;
    }

    @PostMapping
    @Operation(summary = "Initiate Payment Transaction", description = "Initiates payment with idempotency key enforcement and automatic retry recovery")
    public ResponseEntity<PaymentResponse> initiatePayment(
            @RequestHeader(value = "X-Idempotency-Key", required = false) String headerIdempotencyKey,
            @Valid @RequestBody InitiatePaymentRequest request) {

        // Support header, body, or generated fallback idempotency key
        String key = (headerIdempotencyKey != null && !headerIdempotencyKey.trim().isEmpty())
                ? headerIdempotencyKey.trim()
                : request.getIdempotencyKey();

        if (key == null || key.trim().isEmpty()) {
            key = "IDEM_" + System.currentTimeMillis();
        }
        request.setIdempotencyKey(key);

        PaymentResponse response = paymentProcessingService.initiatePayment(request);

        if (Boolean.TRUE.equals(request.getAsyncProcessing())) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
        } else {
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        }
    }

    @GetMapping("/{paymentId}")
    @Operation(summary = "Fetch Payment Status", description = "Retrieves payment transaction record by paymentId")
    public ResponseEntity<PaymentResponse> getPaymentStatus(@PathVariable String paymentId) {
        PaymentResponse response = paymentProcessingService.getPaymentStatus(paymentId);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    @Operation(summary = "List All Payments", description = "Retrieves recent payment transactions with pagination support")
    public ResponseEntity<List<PaymentResponse>> getAllPayments(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(paymentProcessingService.getAllPayments(page, size));
    }

    @GetMapping("/user/{userId}")
    @Operation(summary = "List User Payments", description = "Retrieves payment transactions for specific userId with pagination support")
    public ResponseEntity<List<PaymentResponse>> getPaymentsByUser(
            @PathVariable String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(paymentProcessingService.getPaymentsByUser(userId, page, size));
    }
}
