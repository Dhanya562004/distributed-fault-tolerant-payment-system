package com.paymentsystem.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsystem.dto.request.ProcessRefundRequest;
import com.paymentsystem.dto.response.RefundResponse;
import com.paymentsystem.entity.PaymentStatus;
import com.paymentsystem.exception.GlobalExceptionHandler;
import com.paymentsystem.exception.InvalidTransactionStateException;
import com.paymentsystem.service.RefundProcessingService;
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
class RefundControllerTest {

    private MockMvc mockMvc;

    @Mock
    private RefundProcessingService refundProcessingService;

    @InjectMocks
    private RefundController refundController;

    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(refundController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("POST /api/v1/refunds should return 201 CREATED for valid refund request")
    void testProcessRefund_Success() throws Exception {
        ProcessRefundRequest request = new ProcessRefundRequest(
                "PAY_1001", "IDEM_REF_CTRL", new BigDecimal("50.00"), "Product return"
        );

        RefundResponse response = new RefundResponse();
        response.setRefundId("REF_1001");
        response.setPaymentId("PAY_1001");
        response.setUserId("USR_ALICE");
        response.setAmount(new BigDecimal("50.00"));
        response.setStatus(PaymentStatus.REFUNDED);

        when(refundProcessingService.processRefund(any(ProcessRefundRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/refunds")
                        .header("X-Idempotency-Key", "IDEM_REF_CTRL")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.refundId").value("REF_1001"))
                .andExpect(jsonPath("$.status").value("REFUNDED"));
    }

    @Test
    @DisplayName("POST /api/v1/refunds should return 422 UNPROCESSABLE_ENTITY on InvalidTransactionStateException")
    void testProcessRefund_InvalidState_ReturnsUnprocessableEntity() throws Exception {
        ProcessRefundRequest request = new ProcessRefundRequest(
                "PAY_1002", "IDEM_REF_INVALID", new BigDecimal("50.00"), "Cannot refund non-successful transaction"
        );

        when(refundProcessingService.processRefund(any(ProcessRefundRequest.class)))
                .thenThrow(new InvalidTransactionStateException("Cannot refund transaction in state [FAILED]."));

        mockMvc.perform(post("/api/v1/refunds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INVALID_TRANSACTION_STATE"));
    }

    @Test
    @DisplayName("GET /api/v1/refunds/{refundId} should return 200 OK")
    void testGetRefundStatus_Success() throws Exception {
        RefundResponse response = new RefundResponse();
        response.setRefundId("REF_2001");
        response.setPaymentId("PAY_2001");
        response.setStatus(PaymentStatus.REFUNDED);

        when(refundProcessingService.getRefundStatus("REF_2001")).thenReturn(response);

        mockMvc.perform(get("/api/v1/refunds/REF_2001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refundId").value("REF_2001"));
    }
}
