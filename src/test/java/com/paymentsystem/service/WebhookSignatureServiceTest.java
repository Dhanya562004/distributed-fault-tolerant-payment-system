package com.paymentsystem.service;

import com.paymentsystem.util.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class WebhookSignatureServiceTest {

    private static final String SECRET = "whsec_test_secret_apexpay_key_998877";
    private static final String SAMPLE_PAYLOAD = "{\"eventId\":\"evt_101\",\"eventType\":\"payment.succeeded\",\"amount\":150.00}";

    private MutableClock clock;
    private WebhookSignatureService signatureService;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-19T10:00:00Z"));
        signatureService = new WebhookSignatureService(SECRET, 300, clock);
    }

    @Test
    @DisplayName("Should successfully generate versioned v1 HMAC-SHA256 signature")
    void testSign_Success() {
        long timestamp = clock.instant().getEpochSecond();
        String signature = signatureService.sign(SAMPLE_PAYLOAD, timestamp);

        assertNotNull(signature);
        assertTrue(signature.startsWith("v1="), "Signature must use versioned 'v1=' format");
        assertEquals(67, signature.length(), "v1= (3 chars) + 64 hex chars = 67 chars");
    }

    @Test
    @DisplayName("Signature verification succeeds with authentic payload, timestamp, and secret")
    void testVerify_Success() {
        long timestamp = clock.instant().getEpochSecond();
        String signature = signatureService.sign(SAMPLE_PAYLOAD, timestamp, SECRET);

        boolean valid = signatureService.verifySignature(SAMPLE_PAYLOAD, signature, String.valueOf(timestamp), SECRET);
        assertTrue(valid, "Signature verification should succeed for unmodified payload");
    }

    @Test
    @DisplayName("Modified payload fails signature verification (tamper resistance)")
    void testVerify_ModifiedPayload_Fails() {
        long timestamp = clock.instant().getEpochSecond();
        String signature = signatureService.sign(SAMPLE_PAYLOAD, timestamp, SECRET);

        String tamperedPayload = "{\"eventId\":\"evt_101\",\"eventType\":\"payment.succeeded\",\"amount\":99999.00}";
        boolean valid = signatureService.verifySignature(tamperedPayload, signature, String.valueOf(timestamp), SECRET);
        assertFalse(valid, "Tampered payload must fail signature verification");
    }

    @Test
    @DisplayName("Incorrect signing secret fails signature verification")
    void testVerify_IncorrectSecret_Fails() {
        long timestamp = clock.instant().getEpochSecond();
        String signature = signatureService.sign(SAMPLE_PAYLOAD, timestamp, SECRET);

        String wrongSecret = "whsec_wrong_attacker_secret_000000";
        boolean valid = signatureService.verifySignature(SAMPLE_PAYLOAD, signature, String.valueOf(timestamp), wrongSecret);
        assertFalse(valid, "Signature signed with different secret must fail verification");
    }

    @Test
    @DisplayName("Expired timestamp beyond tolerance window fails verification (replay attack prevention)")
    void testVerify_ExpiredTimestamp_Fails() {
        long originalTimestamp = clock.instant().getEpochSecond();
        String signature = signatureService.sign(SAMPLE_PAYLOAD, originalTimestamp, SECRET);

        // Fast-forward clock past 300-second tolerance window (e.g., 301 seconds later)
        clock.advance(Duration.ofSeconds(305));

        boolean valid = signatureService.verifySignature(SAMPLE_PAYLOAD, signature, String.valueOf(originalTimestamp), SECRET);
        assertFalse(valid, "Replayed webhook with expired timestamp must fail verification");
    }

    @Test
    @DisplayName("Future timestamp beyond tolerance window fails verification (clock skew defense)")
    void testVerify_FutureTimestamp_Fails() {
        long futureTimestamp = clock.instant().getEpochSecond() + 350; // 350s in future
        String signature = signatureService.sign(SAMPLE_PAYLOAD, futureTimestamp, SECRET);

        boolean valid = signatureService.verifySignature(SAMPLE_PAYLOAD, signature, String.valueOf(futureTimestamp), SECRET);
        assertFalse(valid, "Timestamp too far in future must fail verification");
    }

    @Test
    @DisplayName("Missing or null signature header fails verification safely")
    void testVerify_MissingSignature_Fails() {
        long timestamp = clock.instant().getEpochSecond();
        assertFalse(signatureService.verifySignature(SAMPLE_PAYLOAD, null, String.valueOf(timestamp), SECRET));
        assertFalse(signatureService.verifySignature(SAMPLE_PAYLOAD, "", String.valueOf(timestamp), SECRET));
        assertFalse(signatureService.verifySignature(SAMPLE_PAYLOAD, "   ", String.valueOf(timestamp), SECRET));
    }

    @Test
    @DisplayName("Missing or malformed timestamp header fails verification safely")
    void testVerify_MissingOrMalformedTimestamp_Fails() {
        String signature = signatureService.sign(SAMPLE_PAYLOAD, clock.instant().getEpochSecond(), SECRET);

        assertFalse(signatureService.verifySignature(SAMPLE_PAYLOAD, signature, null, SECRET));
        assertFalse(signatureService.verifySignature(SAMPLE_PAYLOAD, signature, "", SECRET));
        assertFalse(signatureService.verifySignature(SAMPLE_PAYLOAD, signature, "not-a-number", SECRET));
    }

    @Test
    @DisplayName("Malformed signature header lacking v1 prefix fails verification safely")
    void testVerify_MalformedSignature_Fails() {
        long timestamp = clock.instant().getEpochSecond();
        String badSignature = "v2=1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef";

        boolean valid = signatureService.verifySignature(SAMPLE_PAYLOAD, badSignature, String.valueOf(timestamp), SECRET);
        assertFalse(valid, "Signature without v1= prefix must be safely rejected");
    }

    @Test
    @DisplayName("Payload within tolerance window passes verification")
    void testVerify_WithinTolerance_Passes() {
        long timestamp = clock.instant().getEpochSecond();
        String signature = signatureService.sign(SAMPLE_PAYLOAD, timestamp, SECRET);

        // Advance 250s (within 300s tolerance)
        clock.advance(Duration.ofSeconds(250));

        boolean valid = signatureService.verifySignature(SAMPLE_PAYLOAD, signature, String.valueOf(timestamp), SECRET);
        assertTrue(valid, "Webhook within tolerance window should be accepted");
    }
}
