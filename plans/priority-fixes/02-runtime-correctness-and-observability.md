# Task 02: Fix Runtime Correctness Gaps in Observability and Startup Cleanup

## Criticality

**Level:** High

This task is high criticality because the system currently misreports runtime state and logs lifecycle actions that do not actually happen, which undermines operator trust and can hide real failures.

## Objective

Repair the parts of the runtime that currently promise behavior but do not actually deliver it.

This task focuses on two concrete correctness gaps:

1. `rate_limits` are collected in memory but serialized as `null`
2. startup cleanup logs that it is removing workspaces for terminal issues, but does not actually remove them

These are not polish issues. They are correctness issues.

## Why This Matters

Operator trust depends on the dashboard and lifecycle hooks being accurate.

If the UI shows no rate limit data when the orchestrator has it, the observability layer is lying.
If startup cleanup says it removed stale workspaces but leaves them on disk, the lifecycle layer is lying.

Those mismatches create debugging pain and can hide future bugs.

## Current State

Inspect these files first:

- `backend/src/main/kotlin/rockopera/web/Presenter.kt`
- `backend/src/main/kotlin/rockopera/orchestrator/Orchestrator.kt`
- `backend/src/main/kotlin/rockopera/workspace/WorkspaceManager.kt`
- `backend/src/main/kotlin/rockopera/orchestrator/OrchestratorState.kt`
- `backend/src/main/kotlin/rockopera/model/Issue.kt`

Known current issues:

- `Presenter.stateResponse()` writes `rate_limits` as `JsonNull` even when the snapshot contains data
- `startupCleanup()` instantiates a runner and logs intent, but never calls workspace removal

## Non-Goals

Do not use this task to redesign the whole observability API.

Out of scope:

- replacing polling with push-based UI updates
- redesigning the entire snapshot structure
- adding persistent state storage
- sweeping refactors unrelated to the two gaps above

## Required Outcome

After this task:

- `GET /api/v1/state` returns real `rate_limits` payloads when present
- startup cleanup genuinely removes stale workspaces for terminal issues
- both paths are covered by automated tests

## Implementation Plan

### Part A: Fix `rate_limits` Serialization

#### Problem

`Orchestrator` stores `agentRateLimits`, but `Presenter.stateResponse()` serializes it incorrectly.

#### Required Fix

Serialize the map into a real JSON object or JSON value tree.

Implementation guidance:

- write a small helper that converts `Map<String, Any?>` into `JsonElement`
- support nested maps, lists, booleans, numbers, strings, and nulls
- for unsupported value types, convert safely to string instead of throwing

Direct instruction:

- do not keep the current `JsonNull` placeholder behavior
- do not silently drop rate-limit subfields

Suggested design:

```kotlin
private fun anyToJson(value: Any?): JsonElement
```

Use that helper from `Presenter.stateResponse()`.

#### Quality Criteria

- no exception should be thrown if the map contains mixed primitive types
- shape of the returned JSON should remain stable and readable
- `rate_limits` should be `null` only when the runtime has no rate-limit data at all

### Part B: Implement Real Startup Cleanup

#### Problem

`startupCleanup()` fetches terminal issues and logs "removing workspace", but does not remove anything.

#### Required Fix

Perform actual workspace removal for each terminal issue.

Implementation guidance:

1. Resolve the effective config for the issue's project if per-project overrides exist.
2. Build the right `WorkspaceManager` for that effective config.
3. Call `remove(issue.identifier)`.
4. Preserve the current "best effort" behavior:
   - one cleanup failure must not abort the whole startup
   - warnings should be logged with enough context

Direct instruction:

- do not route this through `AgentRunner`
- cleanup should be explicit and direct

#### Important Detail

Use the same workspace naming rules as runtime execution, so cleanup targets the correct directory.

That means:

- use `issue.identifier`
- rely on `WorkspaceManager.sanitizeKey()`
- respect project-specific workspace root or hooks if the config supports them

### Part C: Make Cleanup Idempotent

Startup cleanup should be safe to run repeatedly.

That means:

- missing directories should not fail the startup
- cleanup should succeed even if a workspace was already deleted manually
- hooks should not crash the service when the directory is already gone

## Suggested File-Level Changes

### `Presenter.kt`

- add JSON conversion helper(s)
- return real `rate_limits` payload

### `Orchestrator.kt`

- replace no-op startup cleanup block with real workspace removal
- use effective project config where relevant

### `WorkspaceManager.kt`

- likely no API change needed, but adjust if cleanup reveals missing edge-case handling

### Tests

Add focused tests for:

- `Presenter.stateResponse()` with populated rate limits
- startup cleanup removing terminal issue workspaces
- startup cleanup not failing when workspace does not exist

## Test Plan

### 1. Presenter Test

Create a snapshot with:

- one or two `rate_limits` fields
- nested values if possible

Assert that the serialized JSON:

- contains `rate_limits`
- preserves the expected values
- is not `null`

### 2. Startup Cleanup Test

Set up:

- a temp workflow config with a temp workspace root
- a terminal issue with a known identifier
- a workspace directory that exists on disk before startup

Run the startup cleanup path.

Assert:

- the directory is removed
- no exception escapes

### 3. No-Workspace Case

Set up a terminal issue with no existing workspace and ensure cleanup does not fail.

## Acceptance Criteria

All of the following must be true:

1. `Presenter.stateResponse()` emits real rate-limit JSON when data exists.
2. `rate_limits` is `null` only when no rate-limit data exists.
3. `startupCleanup()` actually removes the workspace directory for terminal issues.
4. cleanup remains best-effort and does not abort startup for one bad issue.
5. automated tests cover both fixes.

## Manual Verification Checklist

1. Start the service with a run that produces rate-limit data and confirm `/api/v1/state` returns it.
2. Create a fake stale workspace for a terminal issue and confirm startup removes it.
3. Re-run startup cleanup with the workspace already gone and confirm no crash occurs.

## Pitfalls to Avoid

- fixing the API output while leaving cleanup as a no-op
- introducing brittle JSON serialization that throws on unexpected value types
- deleting the wrong directory because of config mismatch
- performing cleanup through side-effect-heavy agent abstractions
- turning best-effort cleanup into all-or-nothing cleanup

## Strong Recommendations

- keep both fixes narrow and explicit
- add tests before moving on to unrelated refactors
- log what happened, not just what was intended to happen

## Definition of Done

This task is done only when the service tells the truth about `rate_limits`, actually cleans up stale terminal workspaces on startup, and has tests that prevent both regressions from returning.
