package com.zhida.agent.agent.planner;

import com.zhida.agent.agent.model.ResearchPlan;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedQuestionPlannerTest {

    private final RuleBasedQuestionPlanner planner = new RuleBasedQuestionPlanner();

    @Test
    void shouldRecognizeComparisonAndAddWebResearchStep() {
        ResearchPlan plan = planner.plan("Spring AI 和 LangChain4j 有什么区别，我应该选择哪个？");

        assertThat(plan.taskType()).isEqualTo("COMPARISON");
        assertThat(plan.steps()).extracting("stepId")
                .containsExactly("understand", "research-web", "compose");
    }

    @Test
    void shouldRecognizeDocumentQuestion() {
        ResearchPlan plan = planner.plan("请分析我上传的 PDF 资料");

        assertThat(plan.needsKnowledgeBase()).isTrue();
        assertThat(plan.steps()).extracting("stepId").contains("retrieve-document");
    }

    @Test
    void shouldRecognizeFreshInformation() {
        ResearchPlan plan = planner.plan("查询最新的 Java LTS 版本");

        assertThat(plan.needsFreshInformation()).isTrue();
        assertThat(plan.taskType()).isEqualTo("RESEARCH");
    }
}
