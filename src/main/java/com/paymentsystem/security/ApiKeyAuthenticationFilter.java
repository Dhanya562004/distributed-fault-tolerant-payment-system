package com.paymentsystem.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsystem.dto.response.ErrorResponse;
import com.paymentsystem.entity.ApiKey;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Filter that enforces API Key authentication on all endpoints except health and documentation.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiKeyAuthenticationFilter implements Filter {

    private static final Logger logger = LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);

    private final ApiKeyAuthenticationService apiKeyService;
    private final ObjectMapper objectMapper;

    @Autowired
    public ApiKeyAuthenticationFilter(ApiKeyAuthenticationService apiKeyService, ObjectMapper objectMapper) {
        this.apiKeyService = apiKeyService;
        this.objectMapper = objectMapper != null ? objectMapper.copy().findAndRegisterModules() : new ObjectMapper().findAndRegisterModules();
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String path = httpRequest.getRequestURI();

        // Exempt endpoints: health, Swagger/OpenAPI docs, H2 console, and internal error dispatch
        if (isExemptPath(path)) {
            chain.doFilter(request, response);
            return;
        }

        String providedKey = extractApiKey(httpRequest);
        String requestId = RequestContext.getRequestId();

        if (providedKey == null || providedKey.trim().isEmpty()) {
            logger.warn("[Trace: {}] Authentication rejected: missing API key on path {}", requestId, path);
            sendErrorResponse(httpResponse, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                    "Authentication required: missing X-Api-Key or Authorization header", path, requestId);
            return;
        }

        Optional<ApiKey> authenticatedKey = apiKeyService.authenticate(providedKey);
        if (authenticatedKey.isEmpty()) {
            logger.warn("[Trace: {}] Authentication rejected: invalid or inactive API key on path {}", requestId, path);
            sendErrorResponse(httpResponse, HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "Access forbidden: invalid credentials or inactive API key", path, requestId);
            return;
        }

        ApiKey apiKey = authenticatedKey.get();
        RequestContext.setApiKey(apiKey.getKeyValue());
        RequestContext.setCustomerId(apiKey.getCustomerId());

        chain.doFilter(request, response);
    }

    private boolean isExemptPath(String path) {
        if (path == null) return false;
        return path.equals("/actuator/health")
                || path.startsWith("/actuator")
                || path.equals("/health")
                || path.equals("/swagger-ui.html")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/h2-console")
                || path.equals("/error");
    }

    private String extractApiKey(HttpServletRequest request) {
        String apiKey = request.getHeader("X-Api-Key");
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            return apiKey.trim();
        }

        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && !authHeader.trim().isEmpty()) {
            String trimmed = authHeader.trim();
            if (trimmed.startsWith("Bearer ") || trimmed.startsWith("ApiKey ")) {
                return trimmed.substring(7).trim();
            }
            return trimmed;
        }
        return null;
    }

    private void sendErrorResponse(HttpServletResponse response, HttpStatus status, String code,
                                   String message, String path, String requestId) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        ErrorResponse errorResponse = new ErrorResponse(
                status.value(),
                code,
                message,
                path,
                requestId,
                List.of()
        );
        errorResponse.setCode(code);
        errorResponse.setRequestId(requestId);

        response.getWriter().write(objectMapper.writeValueAsString(errorResponse));
    }
}
