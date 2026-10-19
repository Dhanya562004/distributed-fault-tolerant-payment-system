package com.paymentsystem.service;

import com.paymentsystem.exception.ForbiddenException;
import com.paymentsystem.exception.UnauthorizedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Access control and authorization guard protecting sensitive transaction pagination endpoints.
 */
@Service
public class PaymentPaginationSecurityService {

    private static final Logger logger = LoggerFactory.getLogger(PaymentPaginationSecurityService.class);

    private final boolean securityEnabled;
    private final String expectedApiKey;

    public PaymentPaginationSecurityService(
            @Value("${app.pagination.security.enabled:true}") boolean securityEnabled,
            @Value("${app.pagination.security.api-key:apexpay_admin_secret_key_2026}") String expectedApiKey) {
        this.securityEnabled = securityEnabled;
        this.expectedApiKey = expectedApiKey != null ? expectedApiKey.trim() : "";
    }

    public boolean isSecurityEnabled() {
        return securityEnabled;
    }

    /**
     * Enforces credential authentication and authorization scope.
     *
     * @param apiKeyHeader optional value of X-Api-Key HTTP header
     * @param authorizationHeader optional value of Authorization HTTP header (Bearer or raw token)
     * @throws UnauthorizedException if credentials are missing
     * @throws ForbiddenException if credentials fail constant-time comparison
     */
    public void validateAccess(String apiKeyHeader, String authorizationHeader) {
        if (!securityEnabled) {
            return;
        }

        String providedKey = null;
        if (apiKeyHeader != null && !apiKeyHeader.trim().isEmpty()) {
            providedKey = apiKeyHeader.trim();
        } else if (authorizationHeader != null && !authorizationHeader.trim().isEmpty()) {
            String trimmedAuth = authorizationHeader.trim();
            if (trimmedAuth.startsWith("Bearer ")) {
                providedKey = trimmedAuth.substring(7).trim();
            } else if (trimmedAuth.startsWith("ApiKey ")) {
                providedKey = trimmedAuth.substring(7).trim();
            } else {
                providedKey = trimmedAuth;
            }
        }

        if (providedKey == null || providedKey.isEmpty()) {
            logger.warn("Pagination access rejected: missing authentication credentials");
            throw new UnauthorizedException("Authentication required: missing X-Api-Key or Authorization header");
        }

        if (expectedApiKey.isEmpty() || !constantTimeEquals(expectedApiKey, providedKey)) {
            logger.warn("Pagination access forbidden: credential mismatch or insufficient permissions");
            throw new ForbiddenException("Access forbidden: invalid credentials or insufficient permissions to access payment transactions");
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8)
        );
    }
}
