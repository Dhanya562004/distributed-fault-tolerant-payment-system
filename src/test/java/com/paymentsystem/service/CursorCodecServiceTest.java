package com.paymentsystem.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paymentsystem.dto.CursorPayload;
import com.paymentsystem.entity.PaymentStatus;
import com.paymentsystem.exception.InvalidCursorException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class CursorCodecServiceTest {

    private CursorCodecService cursorCodecService;
    private final String secret = "test_signing_secret_xyz123";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        cursorCodecService = new CursorCodecService(secret, objectMapper);
    }

    @Test
    @DisplayName("encodeCursor generates valid opaque base64 string")
    void testEncodeAndDecodeValidCursor() {
        Instant now = Instant.parse("2026-10-19T10:15:30.123456Z");
        Long id = 42L;

        String cursor = cursorCodecService.encodeCursor(now, id, PaymentStatus.SUCCESS, null, null, null);
        assertNotNull(cursor);
        assertFalse(cursor.isEmpty());

        CursorPayload payload = cursorCodecService.decodeAndValidateCursor(cursor, PaymentStatus.SUCCESS, null, null, null);
        assertNotNull(payload);
        assertEquals(now, payload.getCreatedAt());
        assertEquals(id, payload.getId());
    }

    @Test
    @DisplayName("decodeAndValidateCursor returns null on null or empty input")
    void testNullOrEmptyCursor() {
        assertNull(cursorCodecService.decodeAndValidateCursor(null, null, null, null, null));
        assertNull(cursorCodecService.decodeAndValidateCursor("  ", null, null, null, null));
    }

    @Test
    @DisplayName("decodeAndValidateCursor rejects oversized cursor")
    void testOversizedCursorRejected() {
        String oversized = "a".repeat(1025);
        InvalidCursorException ex = assertThrows(InvalidCursorException.class, () ->
                cursorCodecService.decodeAndValidateCursor(oversized, null, null, null, null));
        assertTrue(ex.getMessage().contains("maximum permitted size"));
    }

    @Test
    @DisplayName("decodeAndValidateCursor rejects malformed Base64")
    void testMalformedBase64Rejected() {
        InvalidCursorException ex = assertThrows(InvalidCursorException.class, () ->
                cursorCodecService.decodeAndValidateCursor("!@#$%^&*()", null, null, null, null));
        assertTrue(ex.getMessage().contains("invalid URL-safe Base64"));
    }

    @Test
    @DisplayName("decodeAndValidateCursor rejects non-JSON payload")
    void testMalformedJsonRejected() {
        String base64 = Base64.getUrlEncoder().withoutPadding().encodeToString("not-a-json".getBytes(StandardCharsets.UTF_8));
        InvalidCursorException ex = assertThrows(InvalidCursorException.class, () ->
                cursorCodecService.decodeAndValidateCursor(base64, null, null, null, null));
        assertTrue(ex.getMessage().contains("invalid JSON structure"));
    }

    @Test
    @DisplayName("decodeAndValidateCursor rejects unsupported version")
    void testUnsupportedVersionRejected() throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("v", "v2");
        root.put("c", Instant.now().toString());
        root.put("id", 100L);
        root.put("fh", "dummy");
        root.put("sig", "dummy");
        String cursor = Base64.getUrlEncoder().withoutPadding().encodeToString(objectMapper.writeValueAsBytes(root));

        InvalidCursorException ex = assertThrows(InvalidCursorException.class, () ->
                cursorCodecService.decodeAndValidateCursor(cursor, null, null, null, null));
        assertTrue(ex.getMessage().contains("Unsupported cursor version"));
    }

    @Test
    @DisplayName("decodeAndValidateCursor rejects missing required fields")
    void testMissingFieldsRejected() throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("v", "v1");
        root.put("c", Instant.now().toString());
        // missing id, fh, sig
        String cursor = Base64.getUrlEncoder().withoutPadding().encodeToString(objectMapper.writeValueAsBytes(root));

        InvalidCursorException ex = assertThrows(InvalidCursorException.class, () ->
                cursorCodecService.decodeAndValidateCursor(cursor, null, null, null, null));
        assertTrue(ex.getMessage().contains("missing required cursor fields"));
    }

    @Test
    @DisplayName("decodeAndValidateCursor rejects non-numeric or non-positive ID")
    void testInvalidIdRejected() throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("v", "v1");
        root.put("c", Instant.now().toString());
        root.put("id", -5L);
        root.put("fh", "dummy");
        root.put("sig", "dummy");
        String cursor = Base64.getUrlEncoder().withoutPadding().encodeToString(objectMapper.writeValueAsBytes(root));

        InvalidCursorException ex = assertThrows(InvalidCursorException.class, () ->
                cursorCodecService.decodeAndValidateCursor(cursor, null, null, null, null));
        assertTrue(ex.getMessage().contains("transaction ID must be positive"));
    }

    @Test
    @DisplayName("decodeAndValidateCursor rejects malformed timestamp")
    void testInvalidTimestampRejected() throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("v", "v1");
        root.put("c", "invalid-date-time");
        root.put("id", 10L);
        root.put("fh", "dummy");
        root.put("sig", "dummy");
        String cursor = Base64.getUrlEncoder().withoutPadding().encodeToString(objectMapper.writeValueAsBytes(root));

        InvalidCursorException ex = assertThrows(InvalidCursorException.class, () ->
                cursorCodecService.decodeAndValidateCursor(cursor, null, null, null, null));
        assertTrue(ex.getMessage().contains("invalid ISO-8601"));
    }

    @Test
    @DisplayName("decodeAndValidateCursor rejects tampered HMAC signature")
    void testTamperedSignatureRejected() {
        Instant now = Instant.parse("2026-10-19T10:15:30.123456Z");
        Long id = 42L;

        String cursor = cursorCodecService.encodeCursor(now, id, PaymentStatus.SUCCESS, null, null, null);
        // Tamper with the raw base64 string
        byte[] decoded = Base64.getUrlDecoder().decode(cursor);
        String json = new String(decoded, StandardCharsets.UTF_8);
        String tamperedJson = json.replace("42", "43");
        String tamperedCursor = Base64.getUrlEncoder().withoutPadding().encodeToString(tamperedJson.getBytes(StandardCharsets.UTF_8));

        InvalidCursorException ex = assertThrows(InvalidCursorException.class, () ->
                cursorCodecService.decodeAndValidateCursor(tamperedCursor, PaymentStatus.SUCCESS, null, null, null));
        assertTrue(ex.getMessage().contains("Tampered pagination cursor"));
    }

    @Test
    @DisplayName("decodeAndValidateCursor rejects changed filters between pages")
    void testChangedFiltersRejected() {
        Instant now = Instant.parse("2026-10-19T10:15:30.123456Z");
        Long id = 42L;

        // Generated with status SUCCESS
        String cursor = cursorCodecService.encodeCursor(now, id, PaymentStatus.SUCCESS, null, null, null);

        // Client attempts to pass the cursor to a query with status FAILED
        InvalidCursorException ex = assertThrows(InvalidCursorException.class, () ->
                cursorCodecService.decodeAndValidateCursor(cursor, PaymentStatus.FAILED, null, null, null));
        assertTrue(ex.getMessage().contains("Cursor filter mismatch"));
    }
}
