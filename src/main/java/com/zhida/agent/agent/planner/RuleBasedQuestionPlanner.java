package com.zhida.agent.agent.planner;

import com.zhida.agent.agent.model.ResearchPlan;
import com.zhida.agent.agent.model.ResearchStep;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class RuleBasedQuestionPlanner implements QuestionPlanner {

    private static final List<String> FRESH_KEYWORDS = List.of(
            "最新", "今天", "现在", "当前", "价格", "新闻", "政策", "版本", "recent", "latest", "today"
    );

    private static final List<String> DOCUMENT_KEYWORDS = List.of(
            "上传", "文档", "资料", "pdf", "word", "readme", "论文"
    );

    @Override
    public ResearchPlan plan(String question) {
        String normalized = question.trim().toLowerCase(Locale.ROOT);
        boolean needsFreshInformation = containsAny(normalized, FRESH_KEYWORDS);
        boolean needsKnowledgeBase = containsAny(normalized, DOCUMENT_KEYWORDS);
        String taskType = classify(normalized, needsFreshInformation, needsKnowledgeBase);

        List<ResearchStep> steps = new ArrayList<>();
        steps.add(new ResearchStep(
                "understand",
                "理解问题",
                "确认用户真正想解决的目标，并补充容易遗漏的分析角度。",
                List.of()
        ));

        if (needsKnowledgeBase) {
            steps.add(new ResearchStep(
                    "retrieve-document",
                    "检索本地资料",
                    "从用户提供的文档中查找与问题直接相关的内容。",
                    List.of("knowledge_search")
            ));
        }

        if (needsFreshInformation || "COMPARISON".equals(taskType) || "RESEARCH".equals(taskType)) {
            steps.add(new ResearchStep(
                    "research-web",
                    "查询公开资料",
                    "搜索并阅读可信来源，比较不同资料的一致点和冲突点。",
                    List.of("web_search", "read_web_page")
            ));
        }

        steps.add(new ResearchStep(
                "compose",
                "整理答案",
                "给出结论、依据、扩展角度、来源和下一步建议。",
                List.of()
        ));

        return new ResearchPlan(
                taskType,
                "研究并回答：" + question.trim(),
                List.of("默认以学习和研究为目的", "若信息不足，将明确说明限制"),
                List.copyOf(steps),
                needsFreshInformation,
                needsKnowledgeBase
        );
    }

    private String classify(String question, boolean fresh, boolean document) {
        if (document) {
            return "DOCUMENT_QA";
        }
        if (containsAny(question, List.of("区别", "对比", "比较", "还是", "怎么选", "优缺点", "versus", " vs "))) {
            return "COMPARISON";
        }
        if (containsAny(question, List.of("计划", "路线", "怎么学", "如何准备", "步骤"))) {
            return "LEARNING_PLAN";
        }
        if (fresh) {
            return "RESEARCH";
        }
        return "EXPLANATION";
    }

    private boolean containsAny(String value, List<String> keywords) {
        return keywords.stream().anyMatch(value::contains);
    }
}
