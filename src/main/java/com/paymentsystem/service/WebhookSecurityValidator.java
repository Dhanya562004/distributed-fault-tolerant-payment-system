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

                String ip = addr.getHostAddress();
                if ("169.254.169.254".equals(ip) || ip.startsWith("169.254.") || "0.0.0.0".equals(ip)) {
                    throw new SecurityException("SSRF Blocked: Cloud instance metadata address is prohibited: " + ip);
                }
            }
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Webhook destination host could not be resolved: " + host, e);
        }
    }
}
