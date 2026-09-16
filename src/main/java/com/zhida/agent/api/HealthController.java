package com.zhida.agent.api;

import com.zhida.agent.common.config.ZhidaProperties;
import java.util.Map;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class HealthController {

  private final ZhidaProperties properties;

  /**
   * 售后模块是否启用。健康接口把它暴露出来，页面据此决定是显示「售后工作台」入口，
   * 还是提示需要以启用售后、JWT 与数据库的模式启动。页面入口本身不构成权限校验。
   */
  private final boolean supportEnabled;

  public HealthController(
      ZhidaProperties properties,
      @org.springframework.beans.factory.annotation.Value("${zhida.support.enabled:false}")
          boolean supportEnabled) {
    this.properties = properties;
    this.supportEnabled = supportEnabled;
  }

  @GetMapping("/health")
  public Map<String, Object> health() {
    return Map.of(
        "status",
        "UP",
        "application",
        "zhida-learning-agent",
        "aiEnabled",
        properties.getAi().isEnabled(),
        "framework",
        "LangChain4j + Spring MVC",
        "embeddingConfigured",
        StringUtils.hasText(properties.getRag().getEmbeddingBaseUrl())
            && StringUtils.hasText(properties.getRag().getEmbeddingApiKey())
            && StringUtils.hasText(properties.getRag().getEmbeddingModel()),
        "searchEnabled",
        StringUtils.hasText(properties.getSearch().getTavilyApiKey()),
        "supportEnabled",
        supportEnabled);
  }
}
