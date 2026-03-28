# Task 04: Resolve the `maxTurns` Contract Mismatch

## Criticality

**Level:** Medium

This task is medium criticality because it is primarily a contract-honesty and maintainability issue: the project currently overpromises behavior, but the mismatch is less dangerous than the security and runtime-correctness gaps.

## Objective

Eliminate the mismatch between what the project advertises about multi-turn execution and what the runtime actually does.

Today the codebase contains:

- a `maxTurns` config field
- a `continuationPrompt()` helper
- documentation that implies multi-turn capability

But the active runtime does not actually execute a true per-run turn loop.

This task exists to remove that ambiguity.

## Decision Rule

This task has two valid outcomes, but only one should be chosen when you execute it.

### Recommended Default Outcome

If Task 03 has not already delivered a real structured multi-turn runtime, then:

- remove `maxTurns` from the public contract for now
- stop documenting multi-turn behavior as supported
- keep or delete internal code as appropriate, but do not advertise unsupported functionality

### Alternative Outcome

If Task 03 has already landed and the runtime is capable of true turn continuation, then:

- implement real `maxTurns` behavior end-to-end
- keep the feature public and documented

Direct instruction:

- do not leave the project in the current "half-advertised" state

## Why This Matters

A config field that does nothing is a trust failure.

Operators assume that:

- config fields are real
- docs reflect actual runtime behavior
- continuation support means the same process or session can continue working within a bounded turn budget

If that is not true, the project should not claim it.

## Current State

Inspect these files:

- `backend/src/main/kotlin/rockopera/config/WorkflowDefinition.kt`
- `backend/src/main/kotlin/rockopera/config/WorkflowLoader.kt`
- `backend/src/main/kotlin/rockopera/agent/PromptBuilder.kt`
- `README.md`
- `PHASES.md`
- `ROCKOPERA_SPEC.md`

Also verify actual usage with code search.

## Non-Goals

Do not implement fake multi-turn behavior by redispatching whole runs and calling that "turns".

Also out of scope:

- redesigning phase semantics
- adding unrelated retry logic
- changing tracker behavior

## Path A: De-Scope Unsupported Multi-Turn Behavior

This is the recommended path unless the runtime is already capable of true turn continuation.

### Required Changes

1. Remove or deprecate `maxTurns` from the public workflow contract.
2. Update docs so they no longer promise multi-turn execution.
3. Remove or clearly internalize `continuationPrompt()` if unused.
4. Add a short note in docs explaining that each RockOpera run currently executes one agent turn per dispatch cycle.

### Direct Instructions

- if you keep `maxTurns` in code for compatibility, mark it deprecated and unused
- do not leave examples in README or spec implying it is live
- update tests that assert or rely on the old config contract

### Acceptance Criteria for Path A

- no public doc claims real multi-turn support
- no config example teaches `maxTurns` as a live feature
- code comments make the limitation explicit where necessary

## Path B: Implement Real Multi-Turn Support

Choose this path only if the runtime can truly support multiple turns in one run.

### Required Semantics

If implemented, `maxTurns` must mean:

- one agent run may execute up to `maxTurns` turns before exiting
- the next turn continues from the same workspace and logical session context
- the loop stops early when work is done, when the issue becomes ineligible, or when the runtime fails

### Required Stop Conditions

The loop must stop when any of the following occurs:

- the phase completes successfully
- the tracker state is no longer active
- the phase fails
- the agent times out or stalls
- `maxTurns` is reached

### Required Behavior

1. Turn 1 uses the normal phase prompt.
2. Turns 2..N use explicit continuation guidance.
3. The runtime preserves the right session/thread context if supported.
4. The orchestrator still sees coherent activity, token, and turn-count updates.

### Direct Instructions

- do not implement multi-turn by re-running the exact same prompt blindly
- do not lose the current success/failure semantics of the phase
- do not let continuation bypass reconciliation or stall detection

### Suggested Implementation Points

- `AgentRunner` owns the turn loop
- `PromptBuilder.continuationPrompt()` becomes active only after the first turn
- `RunningEntry.turnCount` is updated in a truthful way
- docs explain exactly what a "turn" means

### Acceptance Criteria for Path B

- `maxTurns` changes real runtime behavior
- turn count reported in snapshots is accurate
- docs and examples match the implementation
- tests cover continuation and stop conditions

## Testing Requirements

Regardless of which path you choose, add tests.

### If You Choose Path A

Test for:

- config/docs no longer exposing false support
- deprecation behavior if applicable

### If You Choose Path B

Test for:

- turn loop stops at `maxTurns`
- continuation prompt is used after turn 1
- early exit on success
- early exit on failure
- early exit on reconciliation/ineligibility

## Documentation Requirements

Update all relevant docs, not just code.

Minimum set:

- `README.md`
- `PHASES.md`
- `workflow/WORKFLOW.md`
- any comments or test descriptions that mention multi-turn behavior

## Pitfalls to Avoid

- keeping the config field and merely changing wording without clarifying behavior
- implementing a loop that does not respect tracker state changes
- counting retries as turns
- counting separate process restarts as turns while documenting them as in-session continuation

## Strong Recommendation

Prefer honesty over ambition.

If the runtime is not ready for true multi-turn support, remove the claim now and reintroduce it later when it is real.

## Definition of Done

This task is done only when `maxTurns` is either:

- fully implemented and tested as a real runtime feature

or

- removed/de-scoped from the public contract so the codebase no longer overpromises.
