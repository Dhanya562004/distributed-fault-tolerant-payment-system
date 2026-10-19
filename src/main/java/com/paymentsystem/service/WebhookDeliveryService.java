package com.paymentsystem.service;

import com.paymentsystem.dto.DeliveryResult;
import com.paymentsystem.entity.OutboxEvent;
import com.paymentsystem.entity.OutboxStatus;
import com.paymentsystem.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates signing, SSRF validation, HTTP dispatch, and error classification
 * for individual outbox events. Also provides manual redelivery management for DEAD_LETTER events.
 */
@Service
public class WebhookDeliveryService {

    private static final Logger logger = LoggerFactory.getLogger(WebhookDeliveryService.class);

    private final WebhookSignatureService signatureService;
    private final WebhookSecurityValidator securityValidator;
    private final WebhookHttpClient httpClient;
    private final OutboxEventRepository outboxEventRepository;
    private final Clock clock;

    @Value("${app.webhook.enabled:false}")
    private boolean enabled;

    @Value("${app.webhook.destination-url:}")
    private String defaultDestinationUrl;

    @Value("${app.webhook.signing-secret:}")
    private String signingSecret;

    @Autowired
    public WebhookDeliveryService(
            WebhookSignatureService signatureService,
            WebhookSecurityValidator securityValidator,
            WebhookHttpClient httpClient,
            OutboxEventRepository outboxEventRepository,
            Clock clock) {
        this.signatureService = signatureService;
        this.securityValidator = securityValidator;
        this.httpClient = httpClient;
        this.outboxEventRepository = outboxEventRepository;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDefaultDestinationUrl() {
        return defaultDestinationUrl;
    }

    public void setDefaultDestinationUrl(String defaultDestinationUrl) {
        this.defaultDestinationUrl = defaultDestinationUrl;
    }

    public String getSigningSecret() {
        return signingSecret;
    }

    public void setSigningSecret(String signingSecret) {
        this.signingSecret = signingSecret;
    }

    /**
     * Attempts HTTP delivery of an outbox event to the configured default destination URL.
     */
    public DeliveryResult deliver(OutboxEvent event) {
        return deliver(event, this.defaultDestinationUrl);
    }

    /**
     * Attempts HTTP delivery of an outbox event to a specified destination URL.
     * Generates fresh timestamp and HMAC-SHA256 signature for each delivery attempt while preserving stable event ID.
     */
    public DeliveryResult deliver(OutboxEvent event, String targetUrl) {
        if (event == null) {
            return DeliveryResult.permanentFailure("Outbox event cannot be null");
        }

        if (targetUrl == null || targetUrl.trim().isEmpty()) {
            return DeliveryResult.permanentFailure("Webhook destination URL is not configured");
        }

        // Rule 20: If webhook delivery is enabled without a signing secret, fail safely rather than sending unsigned webhooks.
        if (signingSecret == null || signingSecret.trim().isEmpty()) {
            logger.warn("[SECURITY SAFEGUARD] Outgoing webhook for event {} blocked: webhook signing secret is not configured", event.getEventId());
            return DeliveryResult.permanentFailure("Webhook signing secret is not configured");
        }

        // Rule: Prevent SSRF
        try {
            securityValidator.validateUrl(targetUrl);
        } catch (SecurityException se) {
            logger.error("[SSRF DEFENSE] Blocked malicious webhook destination for event {}: {}", event.getEventId(), se.getMessage());
            return DeliveryResult.permanentFailure("SSRF validation failed: " + se.getMessage());
        } catch (IllegalArgumentException iae) {
            logger.warn("Invalid webhook destination URL for event {}: {}", event.getEventId(), iae.getMessage());
            return DeliveryResult.permanentFailure("Invalid URL: " + iae.getMessage());
        }

        // Canonical message format: timestamp + "." + rawPayload
        long timestampEpochSeconds = Instant.now(clock).getEpochSecond();
        String signature;
        try {
            signature = signatureService.sign(event.getPayload(), timestampEpochSeconds, signingSecret);
        } catch (Exception e) {
            logger.error("Failed to compute HMAC-SHA256 signature for event {}: {}", event.getEventId(), e.getMessage());
            return DeliveryResult.permanentFailure("Signature generation failure: " + e.getMessage());
        }

        Map<String, String> headers = new HashMap<>();
        headers.put("X-ApexPay-Event-Id", event.getEventId());
        headers.put("X-ApexPay-Timestamp", String.valueOf(timestampEpochSeconds));
        headers.put("X-ApexPay-Signature", signature);

        logger.info("[OUTBOX DISPATCH] Sending webhook event {} (type: {}, attempt: {}) to destination host",
                event.getEventId(), event.getEventType(), event.getAttemptCount() + 1);

        try {
            WebhookHttpClient.WebhookHttpResponse response = httpClient.send(targetUrl, headers, event.getPayload());
            if (response == null) {
                logger.error("[OUTBOX PERMANENT FAILURE] HTTP client returned null response for event {}", event.getEventId());
                return DeliveryResult.permanentFailure("HTTP client returned null response");
            }
            int statusCode = response.getStatusCode();

            if (statusCode >= 200 && statusCode < 300) {
                logger.info("[OUTBOX SUCCESS] Webhook event {} successfully acknowledged with HTTP {}", event.getEventId(), statusCode);
                return DeliveryResult.success(statusCode, response.getBody());
            } else if (WebhookRetryPolicy.isRetryableStatusCode(statusCode)) {
                logger.warn("[OUTBOX RETRYABLE HTTP] Event {} received transient HTTP {} from destination", event.getEventId(), statusCode);
                return DeliveryResult.retryableFailure(statusCode, "Transient HTTP response: " + statusCode);
            } else {
                logger.warn("[OUTBOX PERMANENT HTTP] Event {} received non-retryable HTTP {} from destination", event.getEventId(), statusCode);
                return DeliveryResult.permanentFailure(statusCode, "Non-retryable client error HTTP " + statusCode);
            }
        } catch (Exception ex) {
            if (WebhookRetryPolicy.isRetryableException(ex)) {
                logger.warn("[OUTBOX RETRYABLE EXCEPTION] Event {} network failure ({}): {}", event.getEventId(), ex.getClass().getSimpleName(), ex.getMessage());
                return DeliveryResult.retryableFailure(0, "Network failure: " + ex.getMessage());
            } else {
                logger.error("[OUTBOX PERMANENT EXCEPTION] Event {} encountered non-retryable exception: {}", event.getEventId(), ex.getMessage(), ex);
                return DeliveryResult.permanentFailure("Non-retryable dispatch failure: " + ex.getMessage());
            }
        }
    }

    /**
     * Safely queues a DEAD_LETTER event for manual redelivery.
     * Preserves original eventId and resets status to PENDING atomically.
     */
    @Transactional
    public boolean redeliverDeadLetterEvent(String eventId) {
        if (eventId == null || eventId.trim().isEmpty()) {
            return false;
        }

        int updated = outboxEventRepository.resetDeadLetterEvent(eventId.trim(), Instant.now(clock));
        if (updated > 0) {
            logger.info("[MANUAL REDELIVERY] Queued dead-letter event {} for redelivery with original event ID preserved", eventId);
            return true;
        } else {
            logger.warn("[MANUAL REDELIVERY] Event {} not found in DEAD_LETTER status or already claimed", eventId);
            return false;
        }
    }

    /**
     * Retrieves all events currently residing in DEAD_LETTER status.
     */
    public List<OutboxEvent> getDeadLetterEvents() {
        return outboxEventRepository.findByStatus(OutboxStatus.DEAD_LETTER);
    }
}
