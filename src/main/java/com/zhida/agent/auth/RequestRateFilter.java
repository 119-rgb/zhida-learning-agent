package com.zhida.agent.auth;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(-200)
public class RequestRateFilter extends OncePerRequestFilter {
  private record Window(long minute, java.util.concurrent.atomic.AtomicInteger count) {}

  private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = request.getRequestURI();
    if (!path.startsWith("/api/") || !request.getMethod().equals("POST")) {
      chain.doFilter(request, response);
      return;
    }
    long minute = System.currentTimeMillis() / 60000;

    String key = request.getRemoteAddr() + (path.startsWith("/api/v1/auth") ? ":auth" : ":api");
    if (windows.size() >= 10000) windows.entrySet().removeIf(e -> e.getValue().minute() < minute);
    if (!windows.containsKey(key) && windows.size() >= 10000) {
      response.setStatus(429);
      return;
    }
    var window =
        windows.compute(
            key,
            (k, old) ->
                old == null || old.minute() != minute
                    ? new Window(minute, new java.util.concurrent.atomic.AtomicInteger())
                    : old);
    int limit = path.startsWith("/api/v1/auth") ? 10 : 30;
    if (window.count().incrementAndGet() > limit) {
      response.setStatus(429);
      response.setHeader("Retry-After", "60");
      return;
    }
    chain.doFilter(request, response);
  }
}
