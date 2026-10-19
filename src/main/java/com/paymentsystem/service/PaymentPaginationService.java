package com.paymentsystem.service;

import com.paymentsystem.dto.CursorPayload;
import com.paymentsystem.dto.response.PagedPaymentResponse;
import com.paymentsystem.dto.response.PaginationMetadata;
import com.paymentsystem.dto.response.PaymentTransactionResponse;
import com.paymentsystem.entity.PaymentStatus;
import com.paymentsystem.entity.PaymentTransaction;
import com.paymentsystem.exception.InvalidPaginationFilterException;
import com.paymentsystem.repository.PaymentTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Service managing deterministic cursor-based keyset pagination for payment transactions.
 * Enforces parameter bounds, date range invariants, authorization credentials,
 * and limit + 1 query execution.
 */
@Service
public class PaymentPaginationService {

    private static final Logger logger = LoggerFactory.getLogger(PaymentPaginationService.class);

    public static final int DEFAULT_LIMIT = 20;
    public static final int MIN_LIMIT = 1;
    public static final int MAX_LIMIT = 100;

    private final PaymentTransactionRepository paymentRepository;
    private final CursorCodecService cursorCodecService;
    private final PaymentPaginationSecurityService securityService;

    @Autowired
    public PaymentPaginationService(
            PaymentTransactionRepository paymentRepository,
            CursorCodecService cursorCodecService,
            PaymentPaginationSecurityService securityService) {
        this.paymentRepository = paymentRepository;
        this.cursorCodecService = cursorCodecService;
        this.securityService = securityService;
    }

    /**
     * Retrieves a page of payment transactions using indexed keyset pagination.
     */
    @Transactional(readOnly = true)
    public PagedPaymentResponse getTransactions(
            Integer limitParam,
            String cursorParam,
            String statusParam,
            String fromParam,
            String toParam,
            String apiKeyHeader,
            String authHeader,
            String tenantScopeHeader) {

        // 1. Enforce Authentication / Authorization
        securityService.validateAccess(apiKeyHeader, authHeader);

        // 2. Validate Limit parameter
        int limit = DEFAULT_LIMIT;
        if (limitParam != null) {
            if (limitParam < MIN_LIMIT || limitParam > MAX_LIMIT) {
                throw new InvalidPaginationFilterException(
                        "Invalid page limit: " + limitParam + ". Limit must be an integer between " + MIN_LIMIT + " and " + MAX_LIMIT);
            }
            limit = limitParam;
        }

        // 3. Parse and validate optional Status filter
        PaymentStatus status = null;
        if (statusParam != null && !statusParam.trim().isEmpty()) {
            try {
                status = PaymentStatus.valueOf(statusParam.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new InvalidPaginationFilterException(
                        "Invalid status filter '" + statusParam + "'. Supported values: PENDING, PROCESSING, SUCCESS, FAILED");
            }
        }

        // 4. Parse and validate optional From & To date range
        Instant fromInstant = null;
        if (fromParam != null && !fromParam.trim().isEmpty()) {
            try {
                fromInstant = Instant.parse(fromParam.trim());
            } catch (DateTimeParseException e) {
                throw new InvalidPaginationFilterException(
                        "Invalid 'from' timestamp format: '" + fromParam + "'. Expected valid ISO-8601 UTC timestamp (e.g. 2026-10-19T10:00:00Z)");
            }
        }

        Instant toInstant = null;
        if (toParam != null && !toParam.trim().isEmpty()) {
            try {
                toInstant = Instant.parse(toParam.trim());
            } catch (DateTimeParseException e) {
                throw new InvalidPaginationFilterException(
                        "Invalid 'to' timestamp format: '" + toParam + "'. Expected valid ISO-8601 UTC timestamp (e.g. 2026-10-19T10:00:00Z)");
            }
        }

        if (fromInstant != null && toInstant != null && fromInstant.isAfter(toInstant)) {
            throw new InvalidPaginationFilterException(
                    "Invalid date range: 'from' timestamp (" + fromInstant + ") cannot be after 'to' timestamp (" + toInstant + ")");
        }

        String scope = tenantScopeHeader != null ? tenantScopeHeader.trim() : null;

        // 5. Decode and cryptographically validate Cursor
        CursorPayload cursorPayload = null;
        if (cursorParam != null && !cursorParam.trim().isEmpty()) {
            cursorPayload = cursorCodecService.decodeAndValidateCursor(
                    cursorParam.trim(), status, fromInstant, toInstant, scope);
        }

        Instant cursorCreatedAt = cursorPayload != null ? cursorPayload.getCreatedAt() : null;
        Long cursorId = cursorPayload != null ? cursorPayload.getId() : null;

        // 6. Fetch limit + 1 records using database-level keyset filtering
        List<PaymentTransaction> rawResults = paymentRepository.findTransactionsKeyset(
                status, fromInstant, toInstant, cursorCreatedAt, cursorId, limit + 1);

        boolean hasMore = rawResults.size() > limit;
        List<PaymentTransaction> pageItems = hasMore ? rawResults.subList(0, limit) : rawResults;

        // 7. Compute nextCursor from the tail transaction if hasMore
        String nextCursor = null;
        if (hasMore && !pageItems.isEmpty()) {
            PaymentTransaction lastItem = pageItems.get(pageItems.size() - 1);
            nextCursor = cursorCodecService.encodeCursor(
                    lastItem.getCreatedAt(), lastItem.getId(), status, fromInstant, toInstant, scope);
        }

        // 8. Map database entities to public response DTOs
        List<PaymentTransactionResponse> responseList = new ArrayList<>(pageItems.size());
        for (PaymentTransaction tx : pageItems) {
            responseList.add(mapToResponseDto(tx));
        }

        PaginationMetadata metadata = new PaginationMetadata(limit, hasMore, nextCursor);
        return new PagedPaymentResponse(responseList, metadata);
    }

    private PaymentTransactionResponse mapToResponseDto(PaymentTransaction tx) {
        return new PaymentTransactionResponse(
                tx.getPaymentId(),
                tx.getPaymentId(),
                tx.getUserId(),
                tx.getAmount(),
                tx.getCurrency(),
                tx.getStatus(),
                tx.getPaymentMethod(),
                tx.getDescription(),
                tx.getCreatedAt(),
                tx.getUpdatedAt()
        );
    }
}
