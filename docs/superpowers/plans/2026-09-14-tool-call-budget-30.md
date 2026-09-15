# 30-Call Tool Budget Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Raise the default per-task tool-call budget from 8 to 30 while preserving the environment override and rejecting the 31st call.

**Architecture:** Keep the existing task-scoped atomic counter and configuration binding. Align the Java property default, publisher convenience default, YAML fallback, environment example, and user documentation so every entry point has the same default.

**Tech Stack:** Java 17, Spring Boot configuration properties, Reactor, JUnit 5, AssertJ, Maven.

---

### Task 1: Lock the new default boundaries with tests

**Files:**
- Modify: `src/test/java/com/zhida/agent/observability/ToolBudgetTest.java`
- Create: `src/test/java/com/zhida/agent/common/config/ZhidaPropertiesTest.java`

- [ ] **Step 1: Write the failing publisher boundary test**

Add a test that calls `open("default-budget")`, successfully acquires 30 calls in a loop, and asserts that acquisition 31 throws `ToolBudgetExceededException`.

- [ ] **Step 2: Write the failing property default test**

Create `ZhidaPropertiesTest` and assert that `new ZhidaProperties().getExecution().getMaxToolCalls()` equals 30.

- [ ] **Step 3: Run the tests and verify RED**

Run:

```powershell
mvn '-Dtest=ToolBudgetTest,ZhidaPropertiesTest' test
```

Expected: both new tests fail because the current Java defaults are 8.

### Task 2: Align every default source

**Files:**
- Modify: `src/main/java/com/zhida/agent/common/config/ZhidaProperties.java`
- Modify: `src/main/java/com/zhida/agent/observability/ToolTracePublisher.java`
- Modify: `src/main/resources/application.yml`
- Modify: `.env.example`
- Modify: `README.md`

- [ ] **Step 1: Change Java defaults to 30**

Set `Execution.maxToolCalls` and `ToolTracePublisher.open(taskId)` to 30. Keep the existing `1..50` setter validation unchanged.

- [ ] **Step 2: Change external configuration defaults to 30**

Set the YAML fallback to `${ZHIDA_MAX_TOOL_CALLS:30}` and the example environment value to `ZHIDA_MAX_TOOL_CALLS=30`.

- [ ] **Step 3: Update README**

State that the default is 30, the legal range remains 1～50, the 31st call is rejected, and increasing the budget does not remove the 90-second timeout or cost risk.

- [ ] **Step 4: Run targeted tests and verify GREEN**

Run:

```powershell
mvn '-Dtest=ToolBudgetTest,ZhidaPropertiesTest' test
```

Expected: all targeted tests pass.

### Task 3: Verify the integrated project

**Files:**
- Verify all modified files above.

- [ ] **Step 1: Run the full test suite**

Run `mvn test` and require zero failures and zero errors; the opt-in real MySQL test may remain skipped.

- [ ] **Step 2: Build the executable JAR**

Run `mvn -DskipTests package` and require `BUILD SUCCESS`.

- [ ] **Step 3: Check the working diff**

Run `git diff --check` and inspect `git status --short`. Do not stage or commit unrelated existing working-tree changes.
