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

  public HealthController(ZhidaProperties properties) {
    this.properties = properties;
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
        StringUtils.hasText(properties.getSearch().getTavilyApiKey()));
  }
}
