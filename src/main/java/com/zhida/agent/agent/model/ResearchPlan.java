package com.zhida.agent.agent.model;

import java.util.List;

public record ResearchPlan(
        String taskType,
        String interpretedGoal,
        List<String> assumptions,
        List<ResearchStep> steps,
        boolean needsFreshInformation,
        boolean needsKnowledgeBase
) {
}
