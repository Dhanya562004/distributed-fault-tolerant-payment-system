package com.paymentsystem.security;

import com.paymentsystem.entity.ApiKey;
import com.paymentsystem.repository.ApiKeyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;

@Service
public class ApiKeyAuthenticationService {

    private static final Logger logger = LoggerFactory.getLogger(ApiKeyAuthenticationService.class);

    private final ApiKeyRepository apiKeyRepository;
    private final String defaultAdminKey;

    @Autowired
    public ApiKeyAuthenticationService(
            ApiKeyRepository apiKeyRepository,
            @Value("${app.security.api-key:apexpay_admin_secret_key_2026}") String defaultAdminKey) {
        this.apiKeyRepository = apiKeyRepository;
        this.defaultAdminKey = (defaultAdminKey != null) ? defaultAdminKey.trim() : "apexpay_admin_secret_key_2026";
    }

    /**
     * Validates an incoming API key against stored database keys or configured admin key.
     *
     * @param providedKey API key provided in request headers
     * @return Optional containing the validated ApiKey record, or empty if invalid
     */
    @Transactional(readOnly = true)
    public Optional<ApiKey> authenticate(String providedKey) {
        if (providedKey == null || providedKey.trim().isEmpty()) {
            return Optional.empty();
        }
        String key = providedKey.trim();

        // 1. Check database for active API key
        Optional<ApiKey> dbKeyOpt = apiKeyRepository.findByKeyValueAndActiveTrue(key);
        if (dbKeyOpt.isPresent()) {
            return dbKeyOpt;
        }

        // 2. Check fallback default admin key with constant-time equality
        if (!defaultAdminKey.isEmpty() && constantTimeEquals(defaultAdminKey, key)) {
            ApiKey adminKey = new ApiKey(defaultAdminKey, "CUST_ADMIN", "ApexPay Administrator");
            return Optional.of(adminKey);
        }

        logger.warn("Authentication failed for provided API key (not found or inactive)");
        return Optional.empty();
    }

    @Transactional
    public ApiKey registerApiKey(String keyValue, String customerId, String customerName) {
        return apiKeyRepository.findByKeyValue(keyValue).orElseGet(() -> {
            ApiKey newKey = new ApiKey(keyValue, customerId, customerName);
            return apiKeyRepository.save(newKey);
        });
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8)
        );
    }
}
