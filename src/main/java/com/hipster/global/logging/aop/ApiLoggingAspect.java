package com.hipster.global.logging.aop;

import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Aspect
@Component
public class ApiLoggingAspect {

    private static final Logger log = LoggerFactory.getLogger(ApiLoggingAspect.class);
    private final ApiQueryCounter queryCounter;

    public ApiLoggingAspect(ApiQueryCounter queryCounter) {
        this.queryCounter = queryCounter;
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
            if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes) {
                request = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
            }
            String uri = (request != null) ? request.getRequestURI() : "Unknown";
            String methodName = joinPoint.getSignature().getName();

            log.info("[API METRIC] URI: {} | Method: {}() | 총 처리시간: {}ms | DB 쿼리 횟수: {} | DB 수행시간: {}ms", 
                     uri, methodName, apiDuration, qCount, qTime);
            
            queryCounter.clear();
        }
    }
}
