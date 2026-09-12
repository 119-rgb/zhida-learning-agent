package com.zhida.agent.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.security.Principal;

@Component
public class OwnerResolver {
    private final boolean enabled;
    public OwnerResolver(@Value("${zhida.auth.enabled:false}") boolean enabled) { this.enabled = enabled; }
    public String owner(Principal principal) {
        if (!enabled) return "local-user";
        if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        return principal.getName();
    }
}
