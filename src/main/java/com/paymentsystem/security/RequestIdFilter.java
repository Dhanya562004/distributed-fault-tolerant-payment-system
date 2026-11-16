package com.paymentsystem.security;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * Assigns or extracts a unique Request ID for every incoming HTTP request,
 * attaches it to the SLF4J MDC, exposes it via RequestContext, and injects
 * the X-Request-Id response header.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter implements Filter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String REQUEST_ID_HEADER_ALT = "X-Request-ID";
    public static final String MDC_KEY = "requestId";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String requestId = httpRequest.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || requestId.trim().isEmpty()) {
            requestId = httpRequest.getHeader(REQUEST_ID_HEADER_ALT);
        }
        if (requestId == null || requestId.trim().isEmpty()) {
            requestId = "req_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        } else {
            requestId = requestId.trim();
        }

        RequestContext.setRequestId(requestId);
        MDC.put(MDC_KEY, requestId);
        httpResponse.setHeader(REQUEST_ID_HEADER, requestId);

        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
            RequestContext.clear();
        }
    }
}
