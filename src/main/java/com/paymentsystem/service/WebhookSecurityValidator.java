package com.paymentsystem.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * Validates outgoing webhook destination URLs to prevent Server-Side Request Forgery (SSRF),
 * private subnet scanning, and cloud metadata access.
 */
@Component
public class WebhookSecurityValidator {

    private static final Logger logger = LoggerFactory.getLogger(WebhookSecurityValidator.class);

    private final boolean allowPrivateDestinations;

    public WebhookSecurityValidator(@Value("${app.webhook.allow-private-destinations:false}") boolean allowPrivateDestinations) {
        this.allowPrivateDestinations = allowPrivateDestinations;
    }

    public boolean isAllowPrivateDestinations() {
        return allowPrivateDestinations;
    }

    /**
     * Validates destination URL against SSRF and transport security policies.
     * Throws IllegalArgumentException or SecurityException if validation fails.
     */
    public void validateUrl(String urlString) {
        if (urlString == null || urlString.trim().isEmpty()) {
            throw new IllegalArgumentException("Webhook destination URL cannot be empty");
        }

        URI uri;
        try {
            uri = URI.create(urlString.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed webhook destination URL: " + urlString, e);
        }

        String scheme = uri.getScheme();
        if (scheme == null) {
            throw new IllegalArgumentException("Webhook destination URL must specify a protocol scheme (e.g., https://)");
        }

        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("Webhook destination URL scheme must be HTTP or HTTPS: " + urlString);
        }

        if (!allowPrivateDestinations && !"https".equalsIgnoreCase(scheme)) {
            throw new SecurityException("Webhook destination URL must use HTTPS in production: " + urlString);
        }

        String host = uri.getHost();
        if (host == null || host.trim().isEmpty()) {
            throw new IllegalArgumentException("Webhook destination URL must specify a valid host: " + urlString);
        }

        if (allowPrivateDestinations) {
            return; // In isolated local test suites, loopback/private mock destinations are permitted
        }

        // Production checks: reject localhost, loopback, link-local, private subnets, and cloud metadata
        String hostLower = host.toLowerCase();
        if ("localhost".equals(hostLower) || hostLower.endsWith(".localhost") || hostLower.endsWith(".internal") || hostLower.endsWith(".local")) {
            throw new SecurityException("SSRF Blocked: Local and internal hostnames are prohibited: " + host);
        }

        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress addr : addresses) {
                validateAddress(addr);
            }
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Webhook destination host could not be resolved: " + host, e);
        }
    }

    private void validateAddress(InetAddress addr) {
        if (addr.isLoopbackAddress()) {
            throw new SecurityException("SSRF Blocked: Loopback address is prohibited: " + addr.getHostAddress());
        }
        if (addr.isSiteLocalAddress()) {
            throw new SecurityException("SSRF Blocked: Private network address is prohibited: " + addr.getHostAddress());
        }
        if (addr.isLinkLocalAddress()) {
            throw new SecurityException("SSRF Blocked: Link-local address is prohibited: " + addr.getHostAddress());
        }
        if (addr.isAnyLocalAddress()) {
            throw new SecurityException("SSRF Blocked: Any-local address is prohibited: " + addr.getHostAddress());
        }
        if (addr.isMulticastAddress()) {
            throw new SecurityException("SSRF Blocked: Multicast address is prohibited: " + addr.getHostAddress());
        }

        byte[] rawBytes = addr.getAddress();

        // IPv6 Address Checks (16 bytes)
        if (rawBytes.length == 16) {
            // Check for IPv4-mapped IPv6 addresses (::ffff:x.x.x.x)
            boolean isIpv4Mapped = true;
            for (int i = 0; i < 10; i++) {
                if (rawBytes[i] != 0) {
                    isIpv4Mapped = false;
                    break;
                }
            }
            if (isIpv4Mapped && (rawBytes[10] & 0xff) == 0xff && (rawBytes[11] & 0xff) == 0xff) {
                byte[] ipv4Bytes = new byte[]{rawBytes[12], rawBytes[13], rawBytes[14], rawBytes[15]};
                try {
                    InetAddress ipv4Addr = InetAddress.getByAddress(ipv4Bytes);
                    validateAddress(ipv4Addr);
                    return;
                } catch (UnknownHostException ignored) {
                }
            }

            // Check for IPv6 Unique Local Address (ULA) fc00::/7 (covers fc00:: to fdff::, including fd00:ec2::254)
            if ((rawBytes[0] & 0xfe) == 0xfc) {
                throw new SecurityException("SSRF Blocked: IPv6 Unique Local Address (ULA) is prohibited: " + addr.getHostAddress());
            }

            // Check for IPv6 Link-Local fe80::/10
            if ((rawBytes[0] & 0xff) == 0xfe && (rawBytes[1] & 0xc0) == 0x80) {
                throw new SecurityException("SSRF Blocked: IPv6 Link-local address is prohibited: " + addr.getHostAddress());
            }

            // Check for IPv6 Loopback ::1
            boolean isAllZeroExceptLast = true;
            for (int i = 0; i < 15; i++) {
                if (rawBytes[i] != 0) {
                    isAllZeroExceptLast = false;
                    break;
                }
            }
            if (isAllZeroExceptLast && rawBytes[15] == 1) {
                throw new SecurityException("SSRF Blocked: IPv6 loopback address is prohibited: " + addr.getHostAddress());
            }
        }

        // IPv4 Address Checks (4 bytes)
        if (rawBytes.length == 4) {
            int b0 = rawBytes[0] & 0xff;
            int b1 = rawBytes[1] & 0xff;

            // Cloud instance metadata 169.254.0.0/16 (e.g. 169.254.169.254)
            if (b0 == 169 && b1 == 254) {
                throw new SecurityException("SSRF Blocked: Cloud instance metadata address is prohibited: " + addr.getHostAddress());
            }

            // Current network 0.0.0.0/8
            if (b0 == 0) {
                throw new SecurityException("SSRF Blocked: Any-local or current network address is prohibited: " + addr.getHostAddress());
            }

            // Carrier-Grade NAT (CG-NAT) 100.64.0.0/10
            if (b0 == 100 && (b1 & 0xc0) == 64) {
                throw new SecurityException("SSRF Blocked: Carrier-Grade NAT address is prohibited: " + addr.getHostAddress());
            }

            // Broadcast 255.255.255.255
            if (b0 == 255 && b1 == 255 && (rawBytes[2] & 0xff) == 255 && (rawBytes[3] & 0xff) == 255) {
                throw new SecurityException("SSRF Blocked: Broadcast address is prohibited: " + addr.getHostAddress());
            }
        }
    }
}
