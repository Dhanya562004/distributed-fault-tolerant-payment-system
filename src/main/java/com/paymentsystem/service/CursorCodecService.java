package com.paymentsystem.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paymentsystem.dto.CursorPayload;
import com.paymentsystem.entity.PaymentStatus;
import com.paymentsystem.exception.InvalidCursorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;

/**
 * Service for cryptographic encoding, HMAC-SHA256 signing, filter binding,
 * and secure decoding of keyset pagination cursors.
 */
@Service
public class CursorCodecService {

    private static final Logger logger = LoggerFactory.getLogger(CursorCodecService.class);
    private static final String CURSOR_VERSION = "v1";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int MAX_CURSOR_LENGTH = 1024;

    private final String signingSecret;
    private final ObjectMapper objectMapper;

    @Autowired
    public CursorCodecService(
            @Value("${app.pagination.signing-secret:apexpay_cursor_signing_secret_dev_2026}") String signingSecret,
            ObjectMapper objectMapper) {
        this.signingSecret = (signingSecret != null && !signingSecret.trim().isEmpty())
                ? signingSecret.trim()
                : "apexpay_cursor_signing_secret_dev_2026";
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    public CursorCodecService(String signingSecret) {
        this(signingSecret, new ObjectMapper());
    }

    /**
     * Computes a deterministic SHA-256 hex fingerprint over active query filters.
     */
    public String computeFilterHash(PaymentStatus status, Instant from, Instant to, String scope) {
        String statusStr = status != null ? status.name() : "";
        String fromStr = from != null ? from.toString() : "";
        String toStr = to != null ? to.toString() : "";
        String scopeStr = scope != null ? scope.trim() : "";

        String canonicalFilters = statusStr + "|" + fromStr + "|" + toStr + "|" + scopeStr;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonicalFilters.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hexString.append(String.format("%02x", b));
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 digest algorithm unavailable", e);
        }
    }

    /**
     * Generates an opaque, URL-safe Base64 encoded cursor signed with HMAC-SHA256.
     */
    public String encodeCursor(Instant createdAt, Long id, PaymentStatus status, Instant from, Instant to, String scope) {
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt timestamp cannot be null for cursor generation");
        }
        if (id == null || id <= 0) {
            throw new IllegalArgumentException("id must be a positive integer for cursor generation");
        }

        String filterHash = computeFilterHash(status, from, to, scope);
        String signature = calculateHmac(CURSOR_VERSION, createdAt.toString(), id, filterHash, this.signingSecret);

        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("v", CURSOR_VERSION);
            root.put("c", createdAt.toString());
            root.put("id", id);
            root.put("fh", filterHash);
            root.put("sig", signature);

            byte[] jsonBytes = objectMapper.writeValueAsBytes(root);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(jsonBytes);
        } catch (Exception e) {
            logger.error("Failed serializing pagination cursor to JSON", e);
            throw new RuntimeException("Failed generating pagination cursor", e);
        }
    }

    /**
     * Decodes and cryptographically validates an opaque cursor against active query filters.
     */
    public CursorPayload decodeAndValidateCursor(String cursorString, PaymentStatus status, Instant from, Instant to, String scope) {
        if (cursorString == null || cursorString.trim().isEmpty()) {
            return null;
        }

        String trimmed = cursorString.trim();
        if (trimmed.length() > MAX_CURSOR_LENGTH) {
            throw new InvalidCursorException("Pagination cursor exceeds maximum permitted size of " + MAX_CURSOR_LENGTH + " characters");
        }

        byte[] decodedBytes;
        try {
            decodedBytes = Base64.getUrlDecoder().decode(trimmed);
        } catch (IllegalArgumentException e) {
            throw new InvalidCursorException("Malformed pagination cursor: invalid URL-safe Base64 encoding");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(decodedBytes);
        } catch (Exception e) {
            throw new InvalidCursorException("Malformed pagination cursor: invalid JSON structure");
        }

        if (!root.isObject()) {
            throw new InvalidCursorException("Malformed pagination cursor: root must be a JSON object");
        }

        // Validate Version
        if (!root.hasNonNull("v")) {
            throw new InvalidCursorException("Malformed pagination cursor: missing version identifier");
        }
        String version = root.get("v").asText();
        if (!CURSOR_VERSION.equals(version)) {
            throw new InvalidCursorException("Unsupported cursor version '" + version + "'. Supported version: " + CURSOR_VERSION);
        }

        // Validate Mandatory Fields
        if (!root.hasNonNull("c") || !root.hasNonNull("id") || !root.hasNonNull("fh") || !root.hasNonNull("sig")) {
            throw new InvalidCursorException("Malformed pagination cursor: missing required cursor fields");
        }

        // Validate ID
        JsonNode idNode = root.get("id");
        if (!idNode.isIntegralNumber()) {
            throw new InvalidCursorException("Malformed pagination cursor: transaction ID must be a numeric integer");
        }
        long id = idNode.asLong();
        if (id <= 0) {
            throw new InvalidCursorException("Malformed pagination cursor: transaction ID must be positive");
        }

        // Validate Timestamp
        String createdAtStr = root.get("c").asText();
        Instant createdAt;
        try {
            createdAt = Instant.parse(createdAtStr);
        } catch (DateTimeParseException e) {
            throw new InvalidCursorException("Malformed pagination cursor: invalid ISO-8601 creation timestamp '" + createdAtStr + "'");
        }

        String filterHash = root.get("fh").asText();
        String providedSignature = root.get("sig").asText();

        // Verify Cryptographic Signature
        String expectedSignature = calculateHmac(CURSOR_VERSION, createdAtStr, id, filterHash, this.signingSecret);
        if (!constantTimeEquals(expectedSignature, providedSignature)) {
            throw new InvalidCursorException("Tampered pagination cursor: HMAC cryptographic signature verification failed");
        }

        // Verify Filter Binding Integrity
        String currentFilterHash = computeFilterHash(status, from, to, scope);
        if (!constantTimeEquals(currentFilterHash, filterHash)) {
            throw new InvalidCursorException("Cursor filter mismatch: query filters (status, date range, or scope) have changed between pages");
        }

        return new CursorPayload(createdAt, id);
    }

    private String calculateHmac(String version, String createdAtStr, long id, String filterHash, String secret) {
        String canonicalPayload = version + "|" + createdAtStr + "|" + id + "|" + filterHash;
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
            mac.init(keySpec);
            byte[] hmacBytes = mac.doFinal(canonicalPayload.getBytes(StandardCharsets.UTF_8));

            StringBuilder hex = new StringBuilder(hmacBytes.length * 2);
            for (byte b : hmacBytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("Failed calculating cursor HMAC-SHA256 signature", e);
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8)
        );
    }
}
