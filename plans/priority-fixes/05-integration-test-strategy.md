# Task 05: Build Integration Test Coverage for the Critical Workflow

## Criticality

**Level:** High

This task is high criticality because the repository lacks automated protection around the most valuable orchestration behavior, making future fixes and refactors much more likely to regress in production.

## Objective

Add integration tests that protect the real value of RockOpera: orchestration of issue work through workspaces, agent execution, retries, and state transitions.

This task is successful when the repository has automated tests that exercise the critical workflow behavior rather than only helper methods and parsing utilities.

## Why This Matters

Today the codebase has useful unit tests, but the most important behavior remains weakly protected:

- dispatching the right issue
- creating/reusing the right workspace
- running the correct phase
- handling failures and retries
- reacting to tracker state changes
- exposing truthful state through the API

Without integration tests, regressions will continue to slip through in the seams between modules.

## Current State

Inspect the existing tests first:

- `backend/src/test/kotlin/rockopera/orchestrator/OrchestratorTest.kt`
- `backend/src/test/kotlin/rockopera/agent/PromptBuilderTest.kt`
- `backend/src/test/kotlin/rockopera/config/WorkflowLoaderTest.kt`
- `backend/src/test/kotlin/rockopera/tracker/LinearAdapterTest.kt`
- `backend/src/test/kotlin/rockopera/workspace/WorkspaceManagerTest.kt`

You should assume these tests are useful but insufficient for end-to-end backend correctness.

## Non-Goals

Do not start with browser E2E or Docker-heavy tests unless the core backend integration path is already covered.

Out of scope for the first pass:

- Playwright UI suites
- production-scale load testing
- snapshotting every dashboard visual detail

## Required Outcome

At the end of this task, the repository should have an integration-focused backend test suite covering the critical workflow path.

Minimum areas to cover:

1. dispatch and run selection
2. retry/backoff behavior
3. reconciliation cancellation
4. startup cleanup
5. observability API serialization

## Testing Philosophy

The goal is not to spin up the whole world for every test.

Use layered integration tests:

- real orchestrator logic
- real workflow config loading where useful
- temp filesystem workspaces
- fake tracker adapters
- fake agent runtime implementations
- Ktor test host for API behavior

This gives most of the value without brittle external dependencies.

## Recommended Test Architecture

### Layer 1: Service-Level Integration Tests

Test `Orchestrator` with:

- a fake `TrackerAdapter`
- a fake `AgentRunner`
- real coroutine behavior
- real temp directories where filesystem behavior matters

Use this layer to validate:

- eligible issue dispatch
- concurrency gating
- retry scheduling
- reconciliation cancellation
- startup cleanup

### Layer 2: Agent-Flow Integration Tests

Test `AgentRunner` with:

- temp workspace root
- a controlled fake or script-based agent process
- fake or stubbed tracker/Gitea interactions

Use this layer to validate:

- prompt construction and runtime launch
- success path
- failure path
- review path when applicable
- git lifecycle hooks where feasible

### Layer 3: API Integration Tests

Use Ktor test host or equivalent to validate:

- `/api/v1/state`
- `/api/v1/{issueIdentifier}`
- `/api/v1/refresh`

The goal is to ensure serialization and API wiring reflect the actual runtime snapshot.

## Minimum Test Matrix

Implement at least the following scenarios.

### Scenario 1: Dispatches the Right Candidate

Given:

- multiple issues
- mixed priorities
- one active eligible issue

Expect:

- eligible issue is dispatched
- running snapshot contains it

### Scenario 2: Failed Run Enters Retry Queue

Given:

- active issue
- fake runner that fails

Expect:

- worker exit marks failure
- retry entry is scheduled
- retry snapshot includes attempt and error

### Scenario 3: Reconciliation Cancels Ineligible Work

Given:

- issue running
- tracker later reports terminal or inactive state

Expect:

- running worker is canceled
- claim is released

### Scenario 4: Startup Cleanup Removes Terminal Workspace

Given:

- terminal issue
- existing workspace directory

Expect:

- startup cleanup removes the directory

### Scenario 5: State API Returns Truthful Snapshot

Given:

- orchestrator snapshot with running, retrying, totals, and rate limits

Expect:

- API response matches the snapshot structure and values

### Scenario 6: Review/Phase Transition Path

If the current runtime allows it, add one integration test proving that a phase transition behaves correctly.

Examples:

- coding success moves to review
- review approval moves to done
- review changes requested moves back to todo

## Suggested Test Helpers

Invest in helpers early. They will pay for themselves.

Useful helpers:

- fake tracker with mutable issue state
- fake runner that can succeed, fail, or block
- temp workflow builder
- temp workspace root builder
- snapshot polling helper with timeout

Direct instruction:

- do not duplicate setup code heavily across tests
- create reusable fixtures and helpers once you see repetition

## File and Structure Recommendations

Keep integration tests discoverable.

Recommended layout:

- `backend/src/test/kotlin/rockopera/orchestrator/...`
- `backend/src/test/kotlin/rockopera/agent/...`
- `backend/src/test/kotlin/rockopera/web/...`
- `backend/src/test/kotlin/rockopera/testsupport/...`

If you add a `testsupport` package, keep it small and purposeful.

## Quality Criteria

The tests should be:

- deterministic
- fast enough to run in normal local development
- explicit about timeouts
- readable by maintainers who did not write them

Avoid:

- sleeping blindly when waiting for async state
- overmocking every internal detail
- tightly coupling tests to log strings unless necessary

## Acceptance Criteria

All of the following must be true:

1. New integration tests cover the critical workflow path, not just utility code.
2. At least the minimum test matrix above is represented.
3. Tests are stable and do not require external Gitea/Linear services for the main pass.
4. Existing tests continue to pass.
5. The new tests make it easier to safely refactor orchestration code later.

## Manual Verification Checklist

After writing the tests:

1. Run the full backend test suite from a clean state.
2. Re-run the new integration tests individually to confirm they are stable.
3. Intentionally break one covered behavior and confirm a test fails for the right reason.

## Pitfalls to Avoid

- writing only more unit tests and calling it integration coverage
- relying on real external services for basic CI confidence
- using long sleeps instead of explicit synchronization
- trying to cover every edge case before the core path is protected

## Strong Recommendations

- cover the most valuable workflow seams first
- bias toward tests that validate behavior visible to operators
- use fake dependencies where that increases determinism without erasing the integration value

## Definition of Done

This task is done only when RockOpera's core backend workflow is protected by integration tests that would catch real regressions in dispatch, retries, reconciliation, cleanup, and API exposure.
