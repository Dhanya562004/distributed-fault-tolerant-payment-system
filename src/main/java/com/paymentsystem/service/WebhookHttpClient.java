package com.paymentsystem.service;

import java.util.Map;

/**
 * HTTP client abstraction for dispatching outbound signed webhook payloads.
 */
public interface WebhookHttpClient {

    WebhookHttpResponse send(String url, Map<String, String> headers, String payload) throws Exception;

    class WebhookHttpResponse {
        private final int statusCode;
        private final String body;

        public WebhookHttpResponse(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public String getBody() {
            return body;
        }
    }
}
