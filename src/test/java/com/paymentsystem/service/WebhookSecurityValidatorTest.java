package com.paymentsystem.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WebhookSecurityValidatorTest {

    private final WebhookSecurityValidator prodValidator = new WebhookSecurityValidator(false);
    private final WebhookSecurityValidator testValidator = new WebhookSecurityValidator(true);

    @Test
    @DisplayName("Production: Valid HTTPS external URL is accepted")
    void testValidateUrl_ValidHttps_Accepted() {
        assertDoesNotThrow(() -> prodValidator.validateUrl("https://1.1.1.1/webhooks/apexpay"));
        assertDoesNotThrow(() -> prodValidator.validateUrl("https://8.8.8.8/notify"));
    }

    @Test
    @DisplayName("Production: Plain HTTP is rejected")
    void testValidateUrl_PlainHttp_Rejected() {
        SecurityException ex = assertThrows(SecurityException.class,
                () -> prodValidator.validateUrl("http://api.merchant.com/webhooks"));
        assertTrue(ex.getMessage().contains("must use HTTPS in production"));
    }

    @Test
    @DisplayName("Production: Localhost is blocked to prevent SSRF")
    void testValidateUrl_Localhost_Blocked() {
        assertThrows(SecurityException.class, () -> prodValidator.validateUrl("https://localhost:8443/webhook"));
        assertThrows(SecurityException.class, () -> prodValidator.validateUrl("https://app.localhost/hook"));
    }

    @Test
    @DisplayName("Production: Loopback IPv4 and IPv6 addresses are blocked")
    void testValidateUrl_Loopback_Blocked() {
        assertThrows(SecurityException.class, () -> prodValidator.validateUrl("https://127.0.0.1:8443/webhook"));
    }

    @Test
    @DisplayName("Production: Cloud metadata IP (169.254.169.254) is blocked")
    void testValidateUrl_CloudMetadata_Blocked() {
        assertThrows(SecurityException.class,
                () -> prodValidator.validateUrl("https://169.254.169.254/latest/meta-data/"));
    }

    @Test
    @DisplayName("Production: RFC 1918 private subnets are blocked")
    void testValidateUrl_PrivateSubnets_Blocked() {
        assertThrows(SecurityException.class, () -> prodValidator.validateUrl("https://10.0.0.1/hook"));
        assertThrows(SecurityException.class, () -> prodValidator.validateUrl("https://192.168.1.1/hook"));
        assertThrows(SecurityException.class, () -> prodValidator.validateUrl("https://172.16.0.1/hook"));
    }

    @Test
    @DisplayName("Invalid or malformed URLs throw IllegalArgumentException")
    void testValidateUrl_Malformed_ThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> prodValidator.validateUrl(null));
        assertThrows(IllegalArgumentException.class, () -> prodValidator.validateUrl(""));
        assertThrows(IllegalArgumentException.class, () -> prodValidator.validateUrl("   "));
        assertThrows(IllegalArgumentException.class, () -> prodValidator.validateUrl("not_a_valid_url"));
        assertThrows(IllegalArgumentException.class, () -> prodValidator.validateUrl("ftp://api.merchant.com/hook"));
    }

    @Test
    @DisplayName("Test environment: Localhost and HTTP mock receivers are permitted")
    void testValidateUrl_TestMode_AllowsPrivateDestinations() {
        assertDoesNotThrow(() -> testValidator.validateUrl("http://localhost:8080/mock-webhook"));
        assertDoesNotThrow(() -> testValidator.validateUrl("http://127.0.0.1:8080/mock-webhook"));
    }

    @Test
    @DisplayName("Production: IPv4-mapped IPv6 loopback and metadata addresses are blocked")
    void testValidateUrl_Ipv4MappedIpv6_Blocked() {
        assertThrows(SecurityException.class, () -> prodValidator.validateUrl("https://[::ffff:127.0.0.1]:8443/webhook"));
        assertThrows(SecurityException.class, () -> prodValidator.validateUrl("https://[::ffff:169.254.169.254]/latest/meta-data/"));
        assertThrows(SecurityException.class, () -> prodValidator.validateUrl("https://[::ffff:10.0.0.1]/hook"));
    }

    @Test
    @DisplayName("Production: IPv6 Unique Local Addresses (ULA fc00::/7) and Link-Local are blocked")
    void testValidateUrl_Ipv6UlaAndLinkLocal_Blocked() {
        assertThrows(SecurityException.class, () -> prodValidator.validateUrl("https://[fd00::1]/webhook"));
        assertThrows(SecurityException.class, () -> prodValidator.validateUrl("https://[fc00::1234]/webhook"));
        assertThrows(SecurityException.class, () -> prodValidator.validateUrl("https://[fe80::1]/webhook"));
    }

    @Test
    @DisplayName("Production: Carrier-Grade NAT (100.64.0.0/10) is blocked")
    void testValidateUrl_CarrierGradeNat_Blocked() {
        assertThrows(SecurityException.class, () -> prodValidator.validateUrl("https://100.64.0.1/hook"));
        assertThrows(SecurityException.class, () -> prodValidator.validateUrl("https://100.127.255.254/hook"));
    }
}
