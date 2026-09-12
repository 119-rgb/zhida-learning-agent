package com.zhida.agent.agent.model;

import java.util.List;

public record ResearchStep(
        String stepId,
        String title,
        String goal,
        List<String> suggestedTools
) {
}
