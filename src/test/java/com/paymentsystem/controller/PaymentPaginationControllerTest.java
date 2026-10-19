package com.paymentsystem.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsystem.dto.response.PagedPaymentResponse;
import com.paymentsystem.dto.response.PaginationMetadata;
import com.paymentsystem.dto.response.PaymentTransactionResponse;
import com.paymentsystem.entity.PaymentStatus;
import com.paymentsystem.exception.*;
import com.paymentsystem.service.PaymentProcessingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class PaymentPaginationControllerTest {

    private MockMvc mockMvc;

    @Mock
    private PaymentProcessingService paymentProcessingService;

    @InjectMocks
    private PaymentController paymentController;

    private static final String VALID_API_KEY = "apexpay_admin_secret_key_2026";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(paymentController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("GET /api/v1/payments/transactions should return 200 OK with valid API key")
    void testGetTransactions_ValidApiKey_Returns200() throws Exception {
        PaymentTransactionResponse tx = new PaymentTransactionResponse(
                "PAY_001", "PAY_001", "USR_ALICE", new BigDecimal("100.00"), "USD",
                PaymentStatus.SUCCESS, "CREDIT_CARD", "Test", Instant.now(), Instant.now()
        );
        PagedPaymentResponse response = new PagedPaymentResponse(
                List.of(tx), new PaginationMetadata(20, false, null)
        );

        when(paymentProcessingService.getTransactions(eq(20), isNull(), isNull(), isNull(), isNull(), eq(VALID_API_KEY), isNull(), isNull()))
                .thenReturn(response);

        mockMvc.perform(get("/api/v1/payments/transactions")
                        .header("X-Api-Key", VALID_API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].id").value("PAY_001"))
                .andExpect(jsonPath("$.data[0].paymentId").value("PAY_001"))
                .andExpect(jsonPath("$.data[0].status").value("SUCCESS"))
                .andExpect(jsonPath("$.pagination.limit").value(20))
                .andExpect(jsonPath("$.pagination.hasMore").value(false))
                .andExpect(jsonPath("$.pagination.nextCursor").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/v1/payments/transactions should return 401 Unauthorized when credentials are missing")
    void testGetTransactions_MissingAuth_Returns401() throws Exception {
        when(paymentProcessingService.getTransactions(any(), any(), any(), any(), any(), isNull(), isNull(), any()))
                .thenThrow(new UnauthorizedException("Authentication required: missing X-Api-Key or Authorization header"));

        mockMvc.perform(get("/api/v1/payments/transactions"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("Authentication required: missing X-Api-Key or Authorization header"));
    }

    @Test
    @DisplayName("GET /api/v1/payments/transactions should return 403 Forbidden on invalid credentials")
    void testGetTransactions_InvalidAuth_Returns403() throws Exception {
        when(paymentProcessingService.getTransactions(any(), any(), any(), any(), any(), eq("wrong_key"), isNull(), any()))
                .thenThrow(new ForbiddenException("Access forbidden: invalid credentials or insufficient permissions"));

        mockMvc.perform(get("/api/v1/payments/transactions")
                        .header("X-Api-Key", "wrong_key"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Access forbidden: invalid credentials or insufficient permissions"));
    }

    @Test
    @DisplayName("GET /api/v1/payments/transactions should return 400 Bad Request on invalid limit")
    void testGetTransactions_InvalidLimit_Returns400() throws Exception {
        when(paymentProcessingService.getTransactions(eq(0), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new InvalidPaginationFilterException("Invalid page limit: 0. Limit must be an integer between 1 and 100"));

        mockMvc.perform(get("/api/v1/payments/transactions?limit=0")
                        .header("X-Api-Key", VALID_API_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PAGINATION_FILTER"));
    }

    @Test
    @DisplayName("GET /api/v1/payments/transactions should return 400 Bad Request on non-numeric limit")
    void testGetTransactions_NonNumericLimit_Returns400() throws Exception {
        mockMvc.perform(get("/api/v1/payments/transactions?limit=abc")
                        .header("X-Api-Key", VALID_API_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PARAMETER"));
    }

    @Test
    @DisplayName("GET /api/v1/payments/transactions should return 400 Bad Request on malformed cursor")
    void testGetTransactions_MalformedCursor_Returns400() throws Exception {
        when(paymentProcessingService.getTransactions(any(), eq("bad-cursor"), any(), any(), any(), any(), any(), any()))
                .thenThrow(new InvalidCursorException("Malformed pagination cursor: invalid URL-safe Base64 encoding"));

        mockMvc.perform(get("/api/v1/payments/transactions?cursor=bad-cursor")
                        .header("X-Api-Key", VALID_API_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_CURSOR"));
    }

    @Test
    @DisplayName("GET /api/v1/payments/transactions should return 400 Bad Request on tampered cursor")
    void testGetTransactions_TamperedCursor_Returns400() throws Exception {
        when(paymentProcessingService.getTransactions(any(), eq("tampered-cursor"), any(), any(), any(), any(), any(), any()))
                .thenThrow(new InvalidCursorException("Tampered pagination cursor: HMAC cryptographic signature verification failed"));

        mockMvc.perform(get("/api/v1/payments/transactions?cursor=tampered-cursor")
                        .header("X-Api-Key", VALID_API_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_CURSOR"));
    }

    @Test
    @DisplayName("GET /api/v1/payments/transactions should return 400 Bad Request on filter mismatch")
    void testGetTransactions_FilterMismatch_Returns400() throws Exception {
        when(paymentProcessingService.getTransactions(any(), eq("cursor1"), eq("FAILED"), any(), any(), any(), any(), any()))
                .thenThrow(new InvalidCursorException("Cursor filter mismatch: query filters have changed between pages"));

        mockMvc.perform(get("/api/v1/payments/transactions?cursor=cursor1&status=FAILED")
                        .header("X-Api-Key", VALID_API_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_CURSOR"));
    }

    @Test
    @DisplayName("GET /api/v1/payments/transactions should return empty page with 200 OK when no data exists")
    void testGetTransactions_EmptyResults_Returns200() throws Exception {
        PagedPaymentResponse emptyResponse = new PagedPaymentResponse(
                Collections.emptyList(), new PaginationMetadata(20, false, null)
        );

        when(paymentProcessingService.getTransactions(eq(20), isNull(), isNull(), isNull(), isNull(), eq(VALID_API_KEY), isNull(), isNull()))
                .thenReturn(emptyResponse);

        mockMvc.perform(get("/api/v1/payments/transactions")
                        .header("X-Api-Key", VALID_API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.pagination.hasMore").value(false))
                .andExpect(jsonPath("$.pagination.nextCursor").doesNotExist());
    }
}
