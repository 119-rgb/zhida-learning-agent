package com.zhida.agent.agent;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.zhida.agent.tool.ResearchTools;
import com.zhida.agent.observability.ObservableToolInterceptor;
import jakarta.annotation.PostConstruct;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
@ConditionalOnProperty(prefix = "zhida.ai", name = "enabled", havingValue = "true")
public class ResearchAgentConfiguration {

    private static final String PLACEHOLDER_API_KEY = "demo-disabled";

    private final String deepseekApiKey;

    public ResearchAgentConfiguration(@Value("${spring.ai.deepseek.api-key:}") String deepseekApiKey) {
        this.deepseekApiKey = deepseekApiKey;
    }

    @PostConstruct
    void checkApiKeyConfigured() {
        if (!StringUtils.hasText(deepseekApiKey) || PLACEHOLDER_API_KEY.equals(deepseekApiKey)) {
            throw new IllegalStateException("""
                    已开启真实 Agent 模式（ZHIDA_AI_ENABLED=true），但没有配置有效的 DeepSeek API Key。
                    请设置环境变量 DEEPSEEK_API_KEY 后重新启动应用；
                    如果只想查看演示流程，请删除 ZHIDA_AI_ENABLED 环境变量或将其设置为 false。
                    """);
        }
    }

    private static final String INSTRUCTION = """
            你是“知答”，一个通用学习与研究 Agent。

            你的目标是理解用户真正要解决的问题，补充容易遗漏的角度，并给出清晰、可靠、可执行的答案。

            执行规则：
            1. 涉及最新信息、事实核验或方案比较时，优先调用 web_search，再选择可信网页调用 read_web_page。
            2. 用户询问“上传的文件、这份文档、知识库”等内容时，优先调用 knowledge_search。
            3. 使用知识库片段回答时，必须标明文件名和页码；没有页码时标明片段编号。
            4. 普通常识问题不必为了展示工具而强制联网或检索知识库。
            5. 工具不可用或资料不足时必须明确说明，禁止伪造搜索结果和引用。
            6. 优先使用官方文档、政府机构、论文原文或其他一手来源。
            7. 最终回答区分事实、推断和建议，并给出使用过的来源 URL 或本地文件定位。
            8. 不输出隐藏的内部思维过程，只输出简洁的结论、依据和可核对信息。
            9. 使用中文回答，除非用户明确要求其他语言。
            """;

    @Bean
    public MemorySaver researchMemorySaver() {
        return new MemorySaver();
    }

    @Bean
    public ReactAgent researchReactAgent(
            ChatModel chatModel,
            ResearchTools researchTools,
            MemorySaver researchMemorySaver,
            ObservableToolInterceptor observableToolInterceptor,
            com.zhida.agent.observability.ModelUsageInterceptor modelUsageInterceptor,
            @Value("${zhida.persistence.enabled:false}") boolean persistenceEnabled
    ) {
        var builder = ReactAgent.builder()
                .name("zhida_research_agent")
                .description("用于学习、资料检索、方案比较和问题研究的中文 Agent")
                .model(chatModel)
                .instruction(INSTRUCTION)
                .tools(MethodToolCallbackProvider.builder().toolObjects(researchTools).build().getToolCallbacks())
                .interceptors(observableToolInterceptor, modelUsageInterceptor)
                .enableLogging(false);
        // Database mode supplies a complete bounded message window per task.
        // Attaching MemorySaver as well would duplicate history and retain every task in RAM.
        if (!persistenceEnabled) builder.saver(researchMemorySaver);
        return builder.build();
    }
}
