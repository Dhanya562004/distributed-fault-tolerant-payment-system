package com.paymentsystem.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsystem.dto.request.InitiatePaymentRequest;
import com.paymentsystem.dto.response.PaymentResponse;
import com.paymentsystem.entity.PaymentStatus;
import com.paymentsystem.exception.GlobalExceptionHandler;
import com.paymentsystem.exception.PayloadMismatchException;
import com.paymentsystem.service.PaymentProcessingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PaymentControllerTest {

    private MockMvc mockMvc;

    @Mock
    private PaymentProcessingService paymentProcessingService;

    @InjectMocks
    private PaymentController paymentController;

    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(paymentController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("POST /api/v1/payments should return 201 CREATED for valid synchronous payment")
    void testInitiatePayment_Sync_ReturnsCreated() throws Exception {
        InitiatePaymentRequest request = new InitiatePaymentRequest(
                "IDEM_CTRL_1", "USR_ALICE", new BigDecimal("120.00"), "USD", "CREDIT_CARD", "Checkout payment"
        );

        PaymentResponse response = new PaymentResponse();
        response.setPaymentId("PAY_CTRL_101");
        response.setIdempotencyKey("IDEM_CTRL_1");
        response.setUserId("USR_ALICE");
        response.setAmount(new BigDecimal("120.00"));
        response.setStatus(PaymentStatus.SUCCESS);

        when(paymentProcessingService.initiatePayment(any(InitiatePaymentRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/payments")
                        .header("X-Idempotency-Key", "IDEM_CTRL_1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentId").value("PAY_CTRL_101"))
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    @DisplayName("POST /api/v1/payments should return 409 CONFLICT on PayloadMismatchException")
    void testInitiatePayment_PayloadMismatch_ReturnsConflict() throws Exception {
        InitiatePaymentRequest request = new InitiatePaymentRequest(
                "IDEM_MISMATCH", "USR_ALICE", new BigDecimal("500.00"), "USD", "CREDIT_CARD", "Conflicting amount"
        );

        when(paymentProcessingService.initiatePayment(any(InitiatePaymentRequest.class)))
                .thenThrow(new PayloadMismatchException("Request with idempotency key [IDEM_MISMATCH] was previously executed with a different payload."));

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("IDEMPOTENCY_PAYLOAD_MISMATCH"));
    }

    @Test
    @DisplayName("GET /api/v1/payments should support page and size parameters")
    void testGetAllPayments_WithPagination() throws Exception {
        PaymentResponse response = new PaymentResponse();
        response.setPaymentId("PAY_1");
        response.setStatus(PaymentStatus.SUCCESS);

        when(paymentProcessingService.getAllPayments(0, 10)).thenReturn(List.of(response));

        mockMvc.perform(get("/api/v1/payments?page=0&size=10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }
}
