package com.paymentsystem.service.impl;

import com.paymentsystem.service.WebhookHttpClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Standard implementation of WebhookHttpClient leveraging JDK 17+ java.net.http.HttpClient.
 * Configured with strict timeouts and redirects disabled to prevent SSRF bypasses.
 */
@Component
public class JdkWebhookHttpClient implements WebhookHttpClient {

    private final HttpClient httpClient;
    private final Duration readTimeout;

    @Autowired
    public JdkWebhookHttpClient(
            @Value("${app.webhook.connect-timeout-ms:3000}") long connectTimeoutMs,
            @Value("${app.webhook.read-timeout-ms:5000}") long readTimeoutMs) {
        this.readTimeout = Duration.ofMillis(readTimeoutMs);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public JdkWebhookHttpClient(HttpClient httpClient, Duration readTimeout) {
        this.httpClient = httpClient;
        this.readTimeout = readTimeout;
    }

    @Override
    public WebhookHttpResponse send(String url, Map<String, String> headers, String payload) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(readTimeout)
                .header("Content-Type", "application/json")
                .header("User-Agent", "ApexPay-Webhook-Engine/1.0")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));

        if (headers != null) {
            headers.forEach(builder::header);
        }

        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new WebhookHttpResponse(response.statusCode(), response.body());
    }
}
