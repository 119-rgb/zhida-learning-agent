package com.zhida.agent.auth;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Order(-200)
public class RequestRateFilter implements WebFilter {
    private record Window(long minute, java.util.concurrent.atomic.AtomicInteger count) {}
    private final ConcurrentHashMap<String,Window> windows=new ConcurrentHashMap<>();
    @Override public Mono<Void> filter(ServerWebExchange exchange,WebFilterChain chain) {
        String path=exchange.getRequest().getPath().value();
        if (!path.startsWith("/api/") || !exchange.getRequest().getMethod().name().equals("POST")) return chain.filter(exchange);
        long minute=System.currentTimeMillis()/60000;
        var remote=exchange.getRequest().getRemoteAddress();
        String key=(remote==null?"unknown":remote.getAddress().getHostAddress()) + (path.startsWith("/api/v1/auth")?":auth":":api");
        if (windows.size()>10000) windows.entrySet().removeIf(e -> e.getValue().minute()<minute);
        if (!windows.containsKey(key) && windows.size()>=10000) {
            exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS); return exchange.getResponse().setComplete();
        }
        var window=windows.compute(key,(k,old) -> old==null || old.minute()!=minute?new Window(minute,new java.util.concurrent.atomic.AtomicInteger()):old);
        int limit=path.startsWith("/api/v1/auth")?10:30;
        if (window.count().incrementAndGet()>limit) {
            exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            exchange.getResponse().getHeaders().set("Retry-After","60");
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }
}
