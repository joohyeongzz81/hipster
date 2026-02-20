package com.hipster.global.logging.aop;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.concurrent.TimeUnit;

@Aspect
@Component
public class ApiLoggingAspect {

    private static final Logger log = LoggerFactory.getLogger(ApiLoggingAspect.class);
    private final ApiQueryCounter queryCounter;
    private final MeterRegistry meterRegistry;

    public ApiLoggingAspect(ApiQueryCounter queryCounter, MeterRegistry meterRegistry) {
        this.queryCounter = queryCounter;
        this.meterRegistry = meterRegistry;
    }

    @Around("within(@org.springframework.web.bind.annotation.RestController *)")
    public Object logApiExecution(ProceedingJoinPoint joinPoint) throws Throwable {
        queryCounter.clear();
        long apiStartTime = System.currentTimeMillis();
        
        try {
            return joinPoint.proceed();
        } finally {
            long apiDuration = System.currentTimeMillis() - apiStartTime;
            long qCount = queryCounter.getCount();
            long qTime = queryCounter.getTime();

            HttpServletRequest request = null;
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes != null) {
                request = attributes.getRequest();
            }
            
            String uri = (request != null) ? request.getRequestURI() : "Unknown";
            String method = (request != null) ? request.getMethod() : "Unknown";
            String methodName = joinPoint.getSignature().getName();

            log.info("[API METRIC] URI: {} | HTTP Method: {} | Method: {}() | 총 처리시간: {}ms | DB 쿼리 횟수: {} | DB 수행시간: {}ms", 
                     uri, method, methodName, apiDuration, qCount, qTime);
            
            // Prometheus 메트릭 기록
            Timer.builder("api.execution.time")
                 .description("API Execution Time")
                 .tag("uri", uri)
                 .tag("method", method)
                 .register(meterRegistry)
                 .record(apiDuration, TimeUnit.MILLISECONDS);
                 
            Counter.builder("api.execution.count")
                   .description("API Execution Count")
                   .tag("uri", uri)
                   .tag("method", method)
                   .register(meterRegistry)
                   .increment();
            
            queryCounter.clear();
        }
    }
}
