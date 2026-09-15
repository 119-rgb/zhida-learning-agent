# Knowledge Base Safety Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enforce owner-aware knowledge-base existence checks in persistent mode and make document deletion recoverable after partial failure or restart.

**Architecture:** A repository owns knowledge-base collection SQL, while `KnowledgeBaseAccessService` becomes the shared boundary that validates public IDs and resolves internal vector scopes. Document deletion becomes a persisted `DELETING` state followed by idempotent physical cleanup, so failure leaves a retryable record instead of a false `READY` record.

**Tech Stack:** Java 17, Spring Boot 3.5, Spring WebFlux, Spring JDBC, JUnit 5, AssertJ, Mockito

---

### Task 1: Enforce reusable knowledge-base access checks

**Files:**
- Create: `src/main/java/com/zhida/agent/knowledge/KnowledgeBaseRepository.java`
- Create: `src/main/java/com/zhida/agent/knowledge/KnowledgeBaseAccessService.java`
- Modify: `src/main/java/com/zhida/agent/api/KnowledgeCollectionController.java`
- Modify: `src/main/java/com/zhida/agent/api/KnowledgeController.java`
- Modify: `src/main/java/com/zhida/agent/application/ResearchOrchestrator.java`
- Test: `src/test/java/com/zhida/agent/auth/AuthIsolationTest.java`
- Test: `src/test/java/com/zhida/agent/application/ResearchOrchestratorTest.java`

- [x] **Step 1: Write failing persistent-mode access tests**

Add HTTP assertions proving an unregistered custom ID returns 404, a created custom knowledge base is accessible to its owner, and the same public ID cannot be reused by another owner.

- [x] **Step 2: Run the access tests and verify RED**

Run `mvn -Dtest=AuthIsolationTest test`.

Expected: the nonexistent custom knowledge-base request returns `200` instead of `404`.

- [x] **Step 3: Add repository and access service**

Create a conditional `KnowledgeBaseRepository` with `list(owner)`, `create(owner, name)`, and `exists(owner, id)`. Create `KnowledgeBaseAccessService.resolve(owner, requestedId)` that normalizes a missing ID to `default`, validates the public ID, requires repository ownership for persistent custom IDs, and finally calls `KnowledgeScope.key(owner, id)`.

The optional repository preserves current non-persistent local behavior while persistent/authenticated mode requires registration.

- [x] **Step 4: Route every access path through the service**

Replace direct collection SQL in `KnowledgeCollectionController`; replace direct `KnowledgeScope.key(...)` calls in `KnowledgeController`; inject the access service into `ResearchOrchestrator` and resolve the scope before putting it in execution metadata. Update direct orchestrator constructors in tests.

- [x] **Step 5: Run targeted tests and verify GREEN**

Run `mvn -Dtest=AuthIsolationTest,ResearchOrchestratorTest,ZhidaAgentApplicationTest test`.

Expected: all selected tests pass, including non-persistent temporary knowledge-base behavior.

### Task 2: Persist a recoverable document deletion state

**Files:**
- Modify: `src/main/java/com/zhida/agent/knowledge/DocumentStatus.java`
- Modify: `src/main/java/com/zhida/agent/knowledge/KnowledgeBaseService.java`
- Modify: `src/main/resources/static/app.js`
- Test: `src/test/java/com/zhida/agent/knowledge/KnowledgeBaseServiceTest.java`

- [x] **Step 1: Write failing deletion recovery tests**

Add tests proving a vector deletion failure leaves `DocumentStatus.DELETING`, a second delete completes after the mock recovers, and a restarted service schedules cleanup for a catalog entry left in `DELETING`.

- [x] **Step 2: Run the knowledge service tests and verify RED**

Run `mvn -Dtest=KnowledgeBaseServiceTest test`.

Expected: compilation or assertion failure because `DELETING` and recoverable deletion do not exist.

- [x] **Step 3: Implement the minimal deletion state machine**

Add `DELETING`. Before deleting physical data, replace the catalog entry with `entry.deleting()` and persist it. Make cleanup idempotent using `deleteIfExists`; remove the catalog entry only after vector and file cleanup succeed. On failure, keep `DELETING`. Reject retry-as-indexing while deleting.

- [x] **Step 4: Recover deletion after restart**

During catalog load, collect `DELETING` document IDs and submit idempotent cleanup to the existing executor. Queue rejection logs a warning and leaves the record retryable.

- [x] **Step 5: Show deletion state in the frontend**

Extend `DOCUMENT_STATUS_LABELS` with `DELETING: '删除中'`.

- [x] **Step 6: Run the knowledge service tests and verify GREEN**

Run `mvn -Dtest=KnowledgeBaseServiceTest test`.

Expected: all selected tests pass.

### Task 3: Document invariants without comment noise

**Files:**
- Modify: `src/main/java/com/zhida/agent/knowledge/KnowledgeBaseAccessService.java`
- Modify: `src/main/java/com/zhida/agent/knowledge/KnowledgeBaseService.java`
- Modify: `src/main/java/com/zhida/agent/knowledge/KnowledgeBaseRepository.java`

- [x] **Step 1: Add design-reason comments**

Comment only the non-obvious invariants: why custom IDs require repository ownership in persistent mode, why local non-persistent mode stays permissive, why `DELETING` is persisted before destructive work, and why failed cleanup must retain the catalog entry.

- [x] **Step 2: Check comments against code**

Remove comments that merely restate syntax and ensure every remaining comment describes an invariant, failure mode, or compatibility constraint.

### Task 4: Full verification

**Files:**
- Verify all modified production and test files

- [x] **Step 1: Run the complete default suite**

Run `mvn test`.

Expected: zero failures and zero errors; the explicitly gated real MySQL test may remain skipped.

- [x] **Step 2: Check the patch**

Run `git diff --check`, `git status --short`, and `git diff --stat`.

Expected: no whitespace errors; existing user changes remain present; only planned knowledge-base files and documents are newly changed by this implementation.
