package com.zhida.agent.auth;

import jakarta.servlet.DispatcherType;
import java.nio.charset.StandardCharsets;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfiguration {
  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12);
  }

  @Bean
  SecurityFilterChain security(
      HttpSecurity http,
      @Value("${zhida.auth.enabled:false}") boolean enabled,
      @Value("${zhida.persistence.enabled:false}") boolean persistence,
      @Value("${zhida.auth.secret:}") String secret)
      throws Exception {
    http.csrf(c -> c.disable())
        .httpBasic(c -> c.disable())
        .formLogin(c -> c.disable())
        .sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    // A fast SSE worker can finish before the request filter unwinds; write headers up front.
    http.headers(
        headers ->
            headers.addObjectPostProcessor(
                new org.springframework.security.config.ObjectPostProcessor<
                    org.springframework.security.web.header.HeaderWriterFilter>() {
                  @Override
                  public <O extends org.springframework.security.web.header.HeaderWriterFilter>
                      O postProcess(O filter) {
                    filter.setShouldWriteHeadersEagerly(true);
                    return filter;
                  }
                }));
    if (!enabled) return http.authorizeHttpRequests(a -> a.anyRequest().permitAll()).build();
    if (!persistence) throw new IllegalStateException("登录模式必须启用数据库");
    if (secret.getBytes(StandardCharsets.UTF_8).length < 32)
      throw new IllegalStateException("ZHIDA_JWT_SECRET 至少需要32字节");
    var decoder =
        NimbusJwtDecoder.withSecretKey(
                new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
            .macAlgorithm(MacAlgorithm.HS256)
            .build();
    decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("zhida"));
    return http.authorizeHttpRequests(
            a ->
                a.dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR)
                    .permitAll()
                    .requestMatchers(
                        "/",
                        "/index.html",
                        "/app.js",
                        "/style.css",
                        "/favicon.ico",
                        "/api/health",
                        "/api/v1/auth/config",
                        "/api/v1/auth/register",
                        "/api/v1/auth/login",
                        "/api/v1/auth/guest")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(o -> o.jwt(j -> j.decoder(decoder)))
        .build();
  }
}
