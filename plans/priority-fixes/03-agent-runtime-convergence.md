# Task 03: Converge on a Single Agent Runtime Model

## Criticality

**Level:** High

This task is high criticality because the codebase currently carries two conflicting execution models, which slows delivery, increases maintenance cost, and makes future runtime features riskier to implement correctly.

## Objective

Remove the architectural split between the currently used CLI-based agent path and the partially implemented app-server path.

The codebase currently contains two competing execution models:

- active path: `CliAgentClient` driven from `AgentRunner`
- dormant/partial path: `AppServerClient` plus `DynamicTool`

This task should leave the project with one primary runtime model, one clear execution story, and one clear source of truth.

## Recommendation

The recommended target is:

- make the app-server model the primary runtime
- keep the CLI model only as a temporary compatibility fallback if absolutely necessary
- remove dead or misleading abstractions once the migration is stable

Do not leave the project in a permanent dual-runtime limbo.

## Why This Matters

The current split causes several problems:

- the specification and the implementation diverge
- dynamic tools exist but are not actually used by the main path
- observability is less structured than the app-server path is designed to support
- maintainers have to reason about two architectures instead of one

If not resolved, future features will continue to land in the wrong layer or twice.

## Current State

Read these files carefully:

- `backend/src/main/kotlin/rockopera/agent/AgentRunner.kt`
- `backend/src/main/kotlin/rockopera/agent/CliAgentClient.kt`
- `backend/src/main/kotlin/rockopera/codex/AppServerClient.kt`
- `backend/src/main/kotlin/rockopera/codex/DynamicTool.kt`
- `backend/src/main/kotlin/rockopera/orchestrator/Orchestrator.kt`
- `ROCKOPERA_SPEC.md`

Current reality:

- `AgentRunner` launches `CliAgentClient`
- `AppServerClient` exists but is not the main execution path
- `DynamicTool` exists but is not wired into the main runtime
- the spec strongly implies a richer structured runtime than the code currently uses

## Non-Goals

Do not try to redesign the entire product in this task.

Out of scope:

- full tracker redesign
- frontend redesign
- changing phase semantics
- adding broad new product features unrelated to runtime convergence

## Required Outcome

After this task:

- one runtime model is clearly primary
- `AgentRunner` uses that runtime model directly
- structured events and IDs flow through the orchestrator consistently
- unused or misleading runtime code is either removed or explicitly marked as compatibility-only

## Target Architecture

Recommended end state:

1. `AgentRunner` owns high-level orchestration only
2. `AppServerClient` owns the subprocess protocol
3. `DynamicTool` is available to the live runtime where appropriate
4. `Orchestrator` receives structured `AgentEvent` updates from one runtime path

## Implementation Strategy

### Phase 1: Inventory and Contract Definition

Before editing behavior, write down the exact runtime contract that must survive the migration.

At minimum, preserve:

- workspace lifecycle
- phase selection
- prompt rendering
- environment variable injection
- token accounting
- status event streaming
- success/failure reporting
- PR and label operations after agent completion

Direct instruction:

- do not start rewriting until you have a clear parity checklist

### Phase 2: Make `AppServerClient` Production-Usable

The app-server path needs to support:

- process start and stop
- initialization handshake
- thread start
- turn start
- line-by-line event reading
- timeout handling
- error propagation with enough context

If gaps exist, fill them in this phase.

Quality bar:

- subprocess failures must be diagnosable
- protocol errors must be explicit
- resources must be cleaned up reliably

### Phase 3: Wire Dynamic Tools Into Real Execution

If the app-server runtime supports dynamic tool execution, wire it into the main agent flow.

Target capabilities:

- Gitea API tool when running against Gitea
- Linear GraphQL tool when running against Linear

Direct instruction:

- if a dynamic tool is available in code, it should either be usable in production or removed from the primary path

### Phase 4: Refactor `AgentRunner`

Refactor `AgentRunner` so it becomes runtime-agnostic at the orchestration level and runtime-specific only at the execution boundary.

Recommended split of responsibilities:

- `AgentRunner`: phase logic, git setup, prompt construction, post-run PR/review actions
- runtime client: session lifecycle and event streaming

Do not keep protocol details spread across multiple layers.

### Phase 5: Preserve Observability

Make sure these fields continue to work or improve:

- session IDs
- turn count
- last event
- last message
- token totals
- activity log

Prefer more structured event mapping over ad hoc stdout scraping.

### Phase 6: Decide Fate of `CliAgentClient`

You must make an explicit decision.

Allowed outcomes:

1. Remove `CliAgentClient` completely
2. Keep it behind an explicit compatibility mode
3. Keep it temporarily, but document that it is transitional and not the default

Not allowed:

- leaving both paths as equal first-class designs without a decision

## Suggested File-Level Work

### `AgentRunner.kt`

- swap the primary execution path to the converged runtime
- keep high-level orchestration behavior intact

### `AppServerClient.kt`

- fill in missing operational details
- harden protocol handling

### `DynamicTool.kt`

- ensure tool specs and execution path are production-usable

### `Orchestrator.kt`

- verify the event payloads still support token accounting and activity reporting

### Docs

- update README and spec-facing docs so they describe the real runtime

## Testing Strategy

### 1. Add a Fake App-Server Test Harness

Create a deterministic fake subprocess that emits known protocol messages.

Use it to test:

- initialization
- thread start
- turn start
- event streaming
- timeout behavior
- tool request / tool response flow

### 2. Add `AgentRunner` Integration Tests

Cover at least:

- coding phase success
- review phase with structured verdict
- runtime failure path
- timeout path

### 3. Verify Orchestrator Event Compatibility

Ensure the orchestrator still accumulates:

- tokens
- timestamps
- activity log entries

## Acceptance Criteria

All of the following must be true:

1. There is one clearly primary agent runtime model.
2. `AgentRunner` no longer depends on a split-brain runtime design.
3. Structured runtime events reach the orchestrator in a stable format.
4. Dynamic tools are either truly wired into production or consciously removed from the primary path.
5. README and related docs match the real runtime architecture.
6. Integration tests cover the converged path.

## Pitfalls to Avoid

- migrating the transport but losing post-run behavior like PR creation
- preserving both runtimes indefinitely "just in case"
- introducing protocol complexity into unrelated classes
- forgetting to update docs after the code is migrated
- relying on brittle stdout parsing when a structured protocol exists

## Strong Recommendations

- favor fewer abstractions with clearer ownership
- keep compatibility mode explicit, not accidental
- treat this as an architecture convergence task, not a feature spree

## Definition of Done

This task is done only when a maintainer can answer "How does RockOpera run an agent?" with one clear story, one primary code path, and documentation that matches the code.
