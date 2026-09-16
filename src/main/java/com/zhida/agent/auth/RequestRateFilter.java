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

  /**
   * 仅供自动测试在用例之间清空计数窗口。生产限流规则（每分钟 30 次 POST、认证接口 10 次）
   * 和键的构成都不受该方法影响；业务代码没有调用点。
   */
  public void resetForTests() {
    windows.clear();
  }

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
