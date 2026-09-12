package com.zhida.agent.conversation;

@FunctionalInterface
public interface ConversationSummarizer {
    String summarize(String prompt);
}
