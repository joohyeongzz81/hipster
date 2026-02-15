package com.hipster.auth.aop;

import com.hipster.auth.UserRole;
import com.hipster.auth.annotation.RequireRole;
import com.hipster.auth.jwt.JwtTokenProvider;
import com.hipster.global.exception.ErrorCode;
import com.hipster.global.exception.ForbiddenException;
import com.hipster.global.exception.InvalidTokenException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.http.HttpHeaders;

import java.util.Arrays;
import java.util.List;

@Aspect
@Component
@RequiredArgsConstructor
public class RoleCheckAspect {

    private static final String BEARER_PREFIX = "Bearer ";
    private final JwtTokenProvider jwtTokenProvider;

    @Around("@annotation(requireRole)")
    public Object checkRole(org.aspectj.lang.ProceedingJoinPoint pjp, RequireRole requireRole) throws Throwable {
        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        String authorizationHeader = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (!StringUtils.hasText(authorizationHeader) || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            throw new InvalidTokenException(ErrorCode.INVALID_OR_MISSING_TOKEN);
        }

        String token = authorizationHeader.substring(BEARER_PREFIX.length());
        String roleString = jwtTokenProvider.extractRole(token);

        if (roleString == null) {
            throw new InvalidTokenException(ErrorCode.INVALID_OR_MISSING_TOKEN);
        }

        UserRole currentUserRole = UserRole.valueOf(roleString.toUpperCase());
        List<UserRole> requiredRoles = Arrays.asList(requireRole.value());

        if (!requiredRoles.contains(currentUserRole)) {
            throw new ForbiddenException(ErrorCode.INSUFFICIENT_PERMISSIONS);
        }

        return pjp.proceed();
    }
}
