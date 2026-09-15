# Quiet Agent Progress UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Hide tool-reading activity and execution-error analysis cards from the live chat while preserving backend events and task state.

**Architecture:** Keep the SSE contract unchanged and make the browser renderer selectively ignore internal tool events. On failure, preserve a neutral retry message and task status but hide the analysis card and backend error details.

**Tech Stack:** Spring Boot, JUnit 5, AssertJ, vanilla JavaScript

---

### Task 1: Lock the quiet rendering contract

**Files:**
- Create: `src/test/java/com/zhida/agent/frontend/ChatProgressVisibilityTest.java`
- Modify: `src/main/resources/static/app.js`

- [x] **Step 1: Write the failing test**

Create a JUnit test that reads `app.js`, isolates `handleAgentEvent` and the form request handler, and asserts:

```java
assertThat(eventHandler)
        .doesNotContain("addToolItem(")
        .doesNotContain("addThinkingItem('执行遇到问题'")
        .doesNotContain("data.message")
        .contains("thinkingDetails.hidden = true;");
assertThat(requestHandler)
        .doesNotContain("addThinkingItem('没有顺利完成'")
        .doesNotContain("finishThinking('分析没有完成'");
```

- [x] **Step 2: Run the test to verify it fails**

Run: `mvn -Dtest=ChatProgressVisibilityTest test`

Expected: FAIL because the current live renderer calls `addToolItem`, adds both red failure items, and exposes `data.message`.

- [x] **Step 3: Implement the minimal renderer change**

In `handleAgentEvent`, remove the three tool rendering branches. Replace the `task.failed` branch with:

```javascript
} else if (type === 'task.failed') {
    if (!answerBuffer.trim()) answerBuffer = '暂时没有生成完整回答，请重新提问。';
    answerElement.replaceChildren(renderMarkdown(answerBuffer));
    thinkingDetails.hidden = true;
    answerElement.classList.remove('typing');
    statusText.textContent = '回答未完成，请重试';
}
```

In the form-level catch block, remove the calls that append a red analysis item and mark the analysis card failed. Set `thinkingDetails.hidden = true` and remove the typing state instead.

- [x] **Step 4: Run the test to verify it passes**

Run: `mvn -Dtest=ChatProgressVisibilityTest test`

Expected: PASS with one test and no failures.

- [x] **Step 5: Run the full verification**

Run: `mvn test`

Expected: all default tests pass; only the explicitly enabled real-MySQL test may be skipped.

Run: `mvn -DskipTests package`

Expected: BUILD SUCCESS.

### Task 2: Synchronize user-facing documentation

**Files:**
- Modify: `README.md`

- [x] **Step 1: Update the interface description**

Replace the claim that technical tool details are shown with wording that the live chat hides tool calls and internal failures while backend task records remain available for diagnosis.

- [x] **Step 2: Verify formatting and changed files**

Run: `git diff --check`

Expected: no whitespace errors.
