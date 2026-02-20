package com.hipster.global.logging.filter;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.AbstractRequestLoggingFilter;

@Component
public class RequestLoggingFilter extends AbstractRequestLoggingFilter {

    public RequestLoggingFilter() {
        setIncludeClientInfo(true);
        setIncludeQueryString(true);
        setIncludePayload(true);
        setIncludeHeaders(false);
        setMaxPayloadLength(10000);
        setAfterMessagePrefix("REQUEST DATA: ");
    }

    @Override
    protected boolean shouldLog(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !uri.contains("/actuator") && !uri.contains("/prometheus") && !uri.contains("/health");
    }

    @Override
    protected void beforeRequest(HttpServletRequest request, String message) {
        // Logging starts
    }

    @Override
    protected void afterRequest(HttpServletRequest request, String message) {
        logger.info(message);
    }
}
