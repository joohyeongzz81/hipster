package com.hipster.global.logging.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MdcLoggingFilter extends OncePerRequestFilter {

    private static final String REQUEST_ID = "REQUEST_ID";
    private static final String REQUEST_TIME = "REQUEST_TIME";
    private static final String REQUEST_URI = "REQUEST_URI";
    private static final String CLIENT_IP = "CLIENT_IP";
    private static final String HTTP_METHOD = "HTTP_METHOD";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            MDC.put(REQUEST_ID, UUID.randomUUID().toString().replace("-", ""));
            MDC.put(REQUEST_TIME, Instant.now().toString());
            MDC.put(REQUEST_URI, request.getRequestURI());
            MDC.put(CLIENT_IP, getClientIp(request));
            MDC.put(HTTP_METHOD, request.getMethod());
            
            filterChain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }
}
