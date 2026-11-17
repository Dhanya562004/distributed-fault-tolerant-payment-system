package com.paymentsystem.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsystem.dto.CursorPayload;
import com.paymentsystem.exception.InvalidCursorException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CursorCodecService Comprehensive Unit Tests")
class CursorCodecServiceComprehensiveUnitTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Constructors with various signing secrets: dev, custom, empty/null")
    void testConstructors() {
        CursorCodecService devService = new CursorCodecService("apexpay_cursor_signing_secret_dev_2026", objectMapper);
        assertNotNull(devService);

        CursorCodecService prodService = new CursorCodecService("custom_strong_prod_secret_1234567890", objectMapper);
        assertNotNull(prodService);

        CursorCodecService emptySecretService = new CursorCodecService("", objectMapper);
        assertNotNull(emptySecretService);

        CursorCodecService nullSecretService = new CursorCodecService(null, objectMapper);
        assertNotNull(nullSecretService);
    }

    @Test
    @DisplayName("encode and decode roundtrip with and without filter bindings")
    void testEncodeAndDecodeRoundtrip() {
        CursorCodecService service = new CursorCodecService("test_secret_key_123", objectMapper);
        Instant now = Instant.parse("2026-11-16T15:00:00Z");
        Instant from = Instant.parse("2026-11-01T00:00:00Z");
        Instant to = Instant.parse("2026-11-30T23:59:59Z");

        // 1. Full filters
        String cursorWithFilters = service.encodeCursor(now, 12345L, PaymentStatus.SUCCESS, from, to, "scope1");
        assertNotNull(cursorWithFilters);

        CursorPayload payload1 = service.decodeAndValidateCursor(cursorWithFilters, PaymentStatus.SUCCESS, from, to, "scope1");
        assertEquals(now, payload1.getCreatedAt());
        assertEquals(12345L, payload1.getId());

        // 2. Null filters
        String cursorNoFilters = service.encodeCursor(now, 99999L, null, null, null, null);
        CursorPayload payload2 = service.decodeAndValidateCursor(cursorNoFilters, null, null, null, null);
        assertEquals(now, payload2.getCreatedAt());
        assertEquals(99999L, payload2.getId());
    }

    @Test
    @DisplayName("decodeAndValidateCursor rejects tampered and malformed cursors")
    void testDecodeCursorRejections() {
        CursorCodecService service = new CursorCodecService("test_secret_key_123", objectMapper);
        Instant now = Instant.now();

        // 1. Null or empty or blank
        assertNull(service.decodeAndValidateCursor(null, null, null, null, null));
        assertNull(service.decodeAndValidateCursor("", null, null, null, null));
        assertNull(service.decodeAndValidateCursor("   ", null, null, null, null));

        // 2. Too long (> 1024)
        String longCursor = "a".repeat(1025);
        assertThrows(InvalidCursorException.class, () -> service.decodeAndValidateCursor(longCursor, null, null, null, null));

        // 3. Invalid base64 in payload
        assertThrows(InvalidCursorException.class, () -> service.decodeAndValidateCursor("!invalidbase64!", null, null, null, null));

        // 4. Invalid JSON in decoded payload
        String invalidJson = Base64.getUrlEncoder().withoutPadding().encodeToString("not-a-json-object".getBytes());
        assertThrows(InvalidCursorException.class, () -> service.decodeAndValidateCursor(invalidJson, null, null, null, null));

        // 5. Tampered payload
        String tamperedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"v\":\"v1\",\"c\":\"2026-11-16T15:00:00Z\",\"id\":999,\"fh\":\"invalid\",\"sig\":\"bad-sig\"}".getBytes());
        assertThrows(InvalidCursorException.class, () -> service.decodeAndValidateCursor(tamperedPayload, null, null, null, null));

        // 6. Filter binding mismatch: expected status mismatch
        String cursorWithStatus = service.encodeCursor(now, 100L, PaymentStatus.SUCCESS, null, null, null);
        assertThrows(InvalidCursorException.class, () -> service.decodeAndValidateCursor(cursorWithStatus, PaymentStatus.FAILED, null, null, null));

        // 7. Filter binding mismatch: fromDate mismatch
        Instant from1 = Instant.parse("2026-11-01T00:00:00Z");
        Instant from2 = Instant.parse("2026-11-05T00:00:00Z");
        String cursorWithFrom = service.encodeCursor(now, 100L, null, from1, null, null);
        assertThrows(InvalidCursorException.class, () -> service.decodeAndValidateCursor(cursorWithFrom, null, from2, null, null));

        // 8. Filter binding mismatch: toDate mismatch
        Instant to1 = Instant.parse("2026-11-20T00:00:00Z");
        Instant to2 = Instant.parse("2026-11-25T00:00:00Z");
        String cursorWithTo = service.encodeCursor(now, 100L, null, null, to1, null);
        assertThrows(InvalidCursorException.class, () -> service.decodeAndValidateCursor(cursorWithTo, null, null, to2, null));
    }
}
