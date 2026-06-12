package com.company.observability.security;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * Add request ID to MDC for log correlation
 */
@Component
@Slf4j
public class RequestLoggingFilter implements Filter {

    private static final String REQUEST_ID_HEADER = "X-Request-ID";
    private static final String MDC_REQUEST_ID_KEY = "requestId";

    private static final String TENANT_HEADER = "X-Tenant-Id";
    private static final String MDC_TENANT_KEY = "tenant";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        // Get or generate request ID
        String requestId = httpRequest.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isEmpty()) {
            requestId = UUID.randomUUID().toString();
        }

        // Add to MDC for logging
        MDC.put(MDC_REQUEST_ID_KEY, requestId);

        // Add tenant to MDC (raw header value, not validated)
        String tenantId = httpRequest.getHeader(TENANT_HEADER);
        if (tenantId != null && !tenantId.isEmpty()) {
            MDC.put(MDC_TENANT_KEY, tenantId);
        }

        // Add to response header
        httpResponse.setHeader(REQUEST_ID_HEADER, requestId);

        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_REQUEST_ID_KEY);
            MDC.remove(MDC_TENANT_KEY);
        }
    }
}