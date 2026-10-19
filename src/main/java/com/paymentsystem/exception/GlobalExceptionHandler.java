package com.paymentsystem.exception;

import com.paymentsystem.dto.response.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(NoResourceFoundException ex, HttpServletRequest request) {
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        logger.warn("[Trace: {}] No static resource or route found for path: {}", traceId, request.getRequestURI());
        ErrorResponse response = new ErrorResponse(
                HttpStatus.NOT_FOUND.value(),
                "RESOURCE_NOT_FOUND",
                "No static resource or API endpoint found for path: " + request.getRequestURI(),
                request.getRequestURI(),
                traceId,
                List.of("Please refer to /swagger-ui.html for active API endpoints.")
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleResourceNotFound(ResourceNotFoundException ex, HttpServletRequest request) {
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        logger.warn("[Trace: {}] Resource not found: {}", traceId, ex.getMessage());
        ErrorResponse response = new ErrorResponse(
                HttpStatus.NOT_FOUND.value(),
                "RESOURCE_NOT_FOUND",
                ex.getMessage(),
                request.getRequestURI(),
                traceId,
                List.of()
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    @ExceptionHandler(PayloadMismatchException.class)
    public ResponseEntity<ErrorResponse> handlePayloadMismatch(PayloadMismatchException ex, HttpServletRequest request) {
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        logger.warn("[Trace: {}] Idempotency key payload mismatch: {}", traceId, ex.getMessage());
        ErrorResponse response = new ErrorResponse(
                HttpStatus.CONFLICT.value(),
                "IDEMPOTENCY_PAYLOAD_MISMATCH",
                ex.getMessage(),
                request.getRequestURI(),
                traceId,
                List.of("An operation with this idempotency key was previously processed with a different request body digest.")
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    @ExceptionHandler(InsufficientBalanceException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientBalance(InsufficientBalanceException ex, HttpServletRequest request) {
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        logger.warn("[Trace: {}] Insufficient user balance: {}", traceId, ex.getMessage());
        ErrorResponse response = new ErrorResponse(
                HttpStatus.UNPROCESSABLE_ENTITY.value(),
                "INSUFFICIENT_FUNDS",
                ex.getMessage(),
                request.getRequestURI(),
                traceId,
                List.of("The requested transaction amount exceeds the available user balance.")
        );
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(response);
    }

    @ExceptionHandler(DuplicateRequestException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateRequest(DuplicateRequestException ex, HttpServletRequest request) {
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        logger.warn("[Trace: {}] Duplicate request conflict: {}", traceId, ex.getMessage());
        ErrorResponse response = new ErrorResponse(
                HttpStatus.CONFLICT.value(),
                "IDEMPOTENCY_CONFLICT",
                ex.getMessage(),
                request.getRequestURI(),
                traceId,
                List.of("An operation with this idempotency key is already in progress or completed with a different payload.")
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    @ExceptionHandler(InvalidTransactionStateException.class)
    public ResponseEntity<ErrorResponse> handleInvalidState(InvalidTransactionStateException ex, HttpServletRequest request) {
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        logger.warn("[Trace: {}] Invalid state transition: {}", traceId, ex.getMessage());
        ErrorResponse response = new ErrorResponse(
                HttpStatus.UNPROCESSABLE_ENTITY.value(),
                "INVALID_TRANSACTION_STATE",
                ex.getMessage(),
                request.getRequestURI(),
                traceId,
                List.of()
        );
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(response);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLocking(OptimisticLockingFailureException ex, HttpServletRequest request) {
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        logger.error("[Trace: {}] Optimistic locking failure (concurrent modification): {}", traceId, ex.getMessage());
        ErrorResponse response = new ErrorResponse(
                HttpStatus.CONFLICT.value(),
                "CONCURRENT_MODIFICATION_CONFLICT",
                "Transaction was modified concurrently by another thread. Please retry with exponential backoff.",
                request.getRequestURI(),
                traceId,
                List.of(ex.getMessage())
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleHttpMessageNotReadable(org.springframework.http.converter.HttpMessageNotReadableException ex, HttpServletRequest request) {
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        logger.warn("[Trace: {}] Malformed JSON request body: {}", traceId, ex.getMessage());

        ErrorResponse response = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "MALFORMED_JSON_REQUEST",
                "JSON parse error: Invalid JSON syntax or unparseable payload structure",
                request.getRequestURI(),
                traceId,
                List.of("Please verify JSON body syntax and quotes.")
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationErrors(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .collect(Collectors.toList());
        logger.warn("[Trace: {}] Validation failed: {}", traceId, details);

        ErrorResponse response = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "VALIDATION_FAILED",
                "Invalid request payload parameters",
                request.getRequestURI(),
                traceId,
                details
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(InvalidCursorException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCursor(InvalidCursorException ex, HttpServletRequest request) {
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        logger.warn("[Trace: {}] Invalid pagination cursor: {}", traceId, ex.getMessage());
        ErrorResponse response = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "INVALID_CURSOR",
                ex.getMessage(),
                request.getRequestURI(),
                traceId,
                List.of("Please provide a valid, untampered cursor matching current query parameters.")
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(InvalidPaginationFilterException.class)
    public ResponseEntity<ErrorResponse> handleInvalidPaginationFilter(InvalidPaginationFilterException ex, HttpServletRequest request) {
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        logger.warn("[Trace: {}] Invalid pagination filter: {}", traceId, ex.getMessage());
        ErrorResponse response = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "INVALID_PAGINATION_FILTER",
                ex.getMessage(),
                request.getRequestURI(),
                traceId,
                List.of()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentTypeMismatch(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        logger.warn("[Trace: {}] Method argument type mismatch on parameter '{}': {}", traceId, ex.getName(), ex.getMessage());
        String paramType = ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "valid type";
        ErrorResponse response = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "INVALID_PARAMETER",
                "Invalid value '" + ex.getValue() + "' for query parameter '" + ex.getName() + "'. Expected type: " + paramType,
                request.getRequestURI(),
                traceId,
                List.of("Parameter '" + ex.getName() + "' must be formatted as " + paramType)
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ErrorResponse> handleUnauthorized(UnauthorizedException ex, HttpServletRequest request) {
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        logger.warn("[Trace: {}] Unauthorized request: {}", traceId, ex.getMessage());
        ErrorResponse response = new ErrorResponse(
                HttpStatus.UNAUTHORIZED.value(),
                "UNAUTHORIZED",
                ex.getMessage(),
                request.getRequestURI(),
                traceId,
                List.of("Please supply a valid X-Api-Key or Authorization Bearer header.")
        );
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ErrorResponse> handleForbidden(ForbiddenException ex, HttpServletRequest request) {
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        logger.warn("[Trace: {}] Forbidden request: {}", traceId, ex.getMessage());
        ErrorResponse response = new ErrorResponse(
                HttpStatus.FORBIDDEN.value(),
                "FORBIDDEN",
                ex.getMessage(),
                request.getRequestURI(),
                traceId,
                List.of("Access denied: insufficient permissions or invalid credentials.")
        );
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
    }

    @ExceptionHandler(PaymentProcessingException.class)
    public ResponseEntity<ErrorResponse> handlePaymentProcessingError(PaymentProcessingException ex, HttpServletRequest request) {
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        logger.error("[Trace: {}] Payment processing exception: {}", traceId, ex.getMessage(), ex);
        ErrorResponse response = new ErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "PAYMENT_PROCESSING_ERROR",
                ex.getMessage(),
                request.getRequestURI(),
                traceId,
                List.of()
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex, HttpServletRequest request) {
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        logger.error("[Trace: {}] Unhandled server error: {}", traceId, ex.getMessage(), ex);
        ErrorResponse response = new ErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "INTERNAL_SERVER_ERROR",
                "An unexpected internal error occurred: " + ex.getMessage(),
                request.getRequestURI(),
                traceId,
                List.of()
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
}
