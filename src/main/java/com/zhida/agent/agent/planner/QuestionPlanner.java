package com.zhida.agent.agent.planner;

import com.zhida.agent.agent.model.ResearchPlan;

public interface QuestionPlanner {

    ResearchPlan plan(String question);
}
