package com.zhida.agent.api;

import com.zhida.agent.common.config.ZhidaProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.util.StringUtils;

import java.util.Map;

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
                "status", "UP",
                "application", "zhida-learning-agent",
                "aiEnabled", properties.getAi().isEnabled(),
                "searchEnabled", StringUtils.hasText(properties.getSearch().getTavilyApiKey())
        );
    }
}
