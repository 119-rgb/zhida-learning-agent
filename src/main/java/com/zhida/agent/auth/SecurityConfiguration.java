package com.zhida.agent.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

@Configuration
public class SecurityConfiguration {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean SecurityWebFilterChain security(ServerHttpSecurity http,
            @Value("${zhida.auth.enabled:false}") boolean enabled,
            @Value("${zhida.persistence.enabled:false}") boolean persistence,
            @Value("${zhida.auth.secret:}") String secret) {
        http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance());
        if (!enabled) return http.authorizeExchange(a -> a.anyExchange().permitAll()).build();
        if (!persistence) throw new IllegalStateException("登录模式必须启用数据库");
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) throw new IllegalStateException("ZHIDA_JWT_SECRET 至少需要32字节");
        var decoder = NimbusReactiveJwtDecoder.withSecretKey(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("zhida"));
        return http.authorizeExchange(a -> a.pathMatchers("/", "/index.html", "/app.js", "/style.css", "/favicon.ico", "/api/health",
                        "/api/v1/auth/config", "/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/guest").permitAll()
                .anyExchange().authenticated())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtDecoder(decoder))).build();
    }
}
