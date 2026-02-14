package com.hipster.auth.jwt;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "hipster.jwt")
public class JwtProperties {
    private String secret;
    private Long accessTokenExpiry;
    private Long refreshTokenExpiry;
}
