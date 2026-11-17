package com.paymentsystem.payment;

import com.paymentsystem.exception.ForbiddenException;
import com.paymentsystem.exception.UnauthorizedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PaymentPaginationSecurityService Unit Tests")
class PaymentPaginationSecurityServiceUnitTest {

    @Test
    @DisplayName("validateAccess when security is disabled does not throw")
    void testSecurityDisabled() {
        PaymentPaginationSecurityService service = new PaymentPaginationSecurityService(false, "secret");
        assertFalse(service.isSecurityEnabled());
        assertDoesNotThrow(() -> service.validateAccess(null, null));
    }

    @Test
    @DisplayName("validateAccess with X-Api-Key header")
    void testValidateWithApiKeyHeader() {
        PaymentPaginationSecurityService service = new PaymentPaginationSecurityService(true, "valid_key_123");
        assertTrue(service.isSecurityEnabled());

        // Valid key
        assertDoesNotThrow(() -> service.validateAccess("valid_key_123", null));

        // Invalid key
        assertThrows(ForbiddenException.class, () -> service.validateAccess("wrong_key", null));

        // Missing credentials
        assertThrows(UnauthorizedException.class, () -> service.validateAccess(null, null));
        assertThrows(UnauthorizedException.class, () -> service.validateAccess("   ", "   "));
    }

    @Test
    @DisplayName("validateAccess with Authorization header formats (Bearer, ApiKey, Raw)")
    void testValidateWithAuthorizationHeader() {
        PaymentPaginationSecurityService service = new PaymentPaginationSecurityService(true, "valid_key_123");

        // Bearer prefix
        assertDoesNotThrow(() -> service.validateAccess(null, "Bearer valid_key_123"));

        // ApiKey prefix
        assertDoesNotThrow(() -> service.validateAccess(null, "ApiKey valid_key_123"));

        // Raw token
        assertDoesNotThrow(() -> service.validateAccess(null, "valid_key_123"));

        // Wrong Bearer
        assertThrows(ForbiddenException.class, () -> service.validateAccess(null, "Bearer invalid"));
    }

    @Test
    @DisplayName("validateAccess with empty expected key forbids all requests")
    void testEmptyExpectedKey() {
        PaymentPaginationSecurityService service = new PaymentPaginationSecurityService(true, "");
        assertThrows(ForbiddenException.class, () -> service.validateAccess("some_key", null));

        PaymentPaginationSecurityService serviceNull = new PaymentPaginationSecurityService(true, null);
        assertThrows(ForbiddenException.class, () -> serviceNull.validateAccess("some_key", null));
    }
}
