package com.hipster.auth.resolver;

import com.hipster.auth.UserRole;
import com.hipster.auth.annotation.CurrentUser;
import com.hipster.auth.dto.CurrentUserInfo;
import com.hipster.auth.jwt.JwtTokenProvider;
import com.hipster.global.exception.ErrorCode;
import com.hipster.global.exception.InvalidTokenException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

@Component
@RequiredArgsConstructor
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    private static final String BEARER_PREFIX = "Bearer ";
    private final JwtTokenProvider jwtTokenProvider;

    @Override
    public boolean supportsParameter(final MethodParameter parameter) {
        return parameter.getParameterAnnotation(CurrentUser.class) != null
                && parameter.getParameterType().equals(CurrentUserInfo.class);
    }

    @Override
    public Object resolveArgument(final MethodParameter parameter, final ModelAndViewContainer mavContainer,
                                  final NativeWebRequest webRequest, final WebDataBinderFactory binderFactory) {
        final String token = extractToken(webRequest);

        final Long userId = jwtTokenProvider.extractUserId(token);
        final String roleString = jwtTokenProvider.extractRole(token);

        if (userId == null || roleString == null) {
            throw new InvalidTokenException(ErrorCode.INVALID_TOKEN_CLAIMS);
        }

        try {
            final UserRole role = UserRole.valueOf(roleString.toUpperCase());
            return new CurrentUserInfo(userId, role);
        } catch (IllegalArgumentException e) {
            throw new InvalidTokenException(ErrorCode.INVALID_TOKEN_CLAIMS);
        }
    }

    private String extractToken(final NativeWebRequest webRequest) {
        final HttpServletRequest request = (HttpServletRequest) webRequest.getNativeRequest();
        final String authorizationHeader = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (!StringUtils.hasText(authorizationHeader) || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            throw new InvalidTokenException(ErrorCode.AUTHORIZATION_HEADER_MISSING);
        }

        return authorizationHeader.substring(BEARER_PREFIX.length());
    }
}
