package com.paymentsystem.service;

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
import java.time.Clock;
import java.time.Instant;

/**
 * Service responsible for generating and verifying HMAC-SHA256 webhook signatures.
 * Protects consumers from payload tampering, impersonation, and replay attacks.
 */
@Service
public class WebhookSignatureService {

    private static final Logger logger = LoggerFactory.getLogger(WebhookSignatureService.class);
    private static final String HMAC_SHA256_ALGORITHM = "HmacSHA256";
    private static final String SIGNATURE_VERSION_PREFIX = "v1=";

    private final String signingSecret;
    private final long signatureToleranceSeconds;
    private final Clock clock;

    @Autowired
    public WebhookSignatureService(
            @Value("${app.webhook.signing-secret:}") String signingSecret,
            @Value("${app.webhook.signature-tolerance-seconds:300}") long signatureToleranceSeconds,
            Clock clock) {
        this.signingSecret = signingSecret != null ? signingSecret.trim() : "";
        this.signatureToleranceSeconds = signatureToleranceSeconds;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    public WebhookSignatureService(String signingSecret, long signatureToleranceSeconds) {
        this(signingSecret, signatureToleranceSeconds, Clock.systemUTC());
    }

    public boolean isSecretConfigured() {
        return signingSecret != null && !signingSecret.isEmpty();
    }

    public String getSigningSecret() {
        return signingSecret;
    }

    public long getSignatureToleranceSeconds() {
        return signatureToleranceSeconds;
    }

    /**
     * Signs payload using the configured application signing secret.
     *
     * @param payload raw JSON request body bytes/string
     * @param timestampEpochSeconds UTC epoch seconds of this delivery attempt
     * @return versioned signature formatted as 'v1=<hex-encoded-hmac-sha256>'
     */
    public String sign(String payload, long timestampEpochSeconds) {
        if (!isSecretConfigured()) {
            throw new IllegalStateException("Webhook signing secret is not configured");
        }
        return sign(payload, timestampEpochSeconds, this.signingSecret);
    }

    /**
     * Signs payload using an explicitly specified signing secret.
     */
    public String sign(String payload, long timestampEpochSeconds, String secret) {
        if (payload == null) {
            throw new IllegalArgumentException("Payload cannot be null");
        }
        if (secret == null || secret.trim().isEmpty()) {
            throw new IllegalArgumentException("Signing secret cannot be null or empty");
        }

        String canonicalMessage = timestampEpochSeconds + "." + payload;
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256_ALGORITHM);
            mac.init(keySpec);
            byte[] rawHmac = mac.doFinal(canonicalMessage.getBytes(StandardCharsets.UTF_8));

            StringBuilder hex = new StringBuilder(rawHmac.length * 2);
            for (byte b : rawHmac) {
                hex.append(String.format("%02x", b));
            }
            return SIGNATURE_VERSION_PREFIX + hex.toString();
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("Failed to calculate HMAC-SHA256 signature", e);
        }
    }

    /**
     * Verifies webhook signature against raw payload, signature header, timestamp header, and secret.
     * Uses default tolerance configured in app properties.
     */
    public boolean verifySignature(String payload, String signatureHeader, String timestampHeader, String secret) {
        return verifySignature(payload, signatureHeader, timestampHeader, secret, this.signatureToleranceSeconds);
    }

    /**
     * Verifies webhook signature against raw payload, signature header, timestamp header, and secret
     * with custom tolerance window in seconds.
     *
     * Performs:
     * 1. Null and empty input validation.
     * 2. Timestamp freshness verification against current Clock to prevent replay attacks.
     * 3. HMAC-SHA256 calculation over canonical 'timestamp.payload'.
     * 4. Constant-time byte array comparison (MessageDigest.isEqual) to prevent timing side-channel attacks.
     */
    public boolean verifySignature(String payload, String signatureHeader, String timestampHeader, String secret, long toleranceSeconds) {
        if (payload == null || signatureHeader == null || timestampHeader == null || secret == null) {
            return false;
        }

        if (signatureHeader.trim().isEmpty() || timestampHeader.trim().isEmpty() || secret.trim().isEmpty()) {
            return false;
        }

        long timestamp;
        try {
            timestamp = Long.parseLong(timestampHeader.trim());
        } catch (NumberFormatException e) {
            logger.debug("Webhook verification rejected: malformed timestamp header '{}'", timestampHeader);
            return false;
        }

        long currentEpoch = Instant.now(clock).getEpochSecond();
        if (Math.abs(currentEpoch - timestamp) > toleranceSeconds) {
            logger.debug("Webhook verification rejected: timestamp {} expired (current: {}, tolerance: {}s)",
                    timestamp, currentEpoch, toleranceSeconds);
            return false;
        }

        if (!signatureHeader.startsWith(SIGNATURE_VERSION_PREFIX)) {
            logger.debug("Webhook verification rejected: signature header lacks version prefix '{}'", SIGNATURE_VERSION_PREFIX);
            return false;
        }

        String expectedSignature;
        try {
            expectedSignature = sign(payload, timestamp, secret);
        } catch (Exception e) {
            logger.warn("Failed calculating expected HMAC signature during verification", e);
            return false;
        }

        byte[] expectedBytes = expectedSignature.getBytes(StandardCharsets.UTF_8);
        byte[] providedBytes = signatureHeader.trim().getBytes(StandardCharsets.UTF_8);

        return MessageDigest.isEqual(expectedBytes, providedBytes);
    }
}
