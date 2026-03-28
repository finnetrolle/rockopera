# Priority Fixes Roadmap

## Purpose

This directory contains standalone execution briefs for the highest-value follow-up work on RockOpera.

Each task file is intended to be independently assignable. A contributor should be able to take a single file, execute the work from it, and produce a reviewable result without needing extra planning context.

## Criticality Scale

- **Critical**: Immediate risk reduction or high-severity operational safety work.
- **High**: Important correctness, architecture, or regression-prevention work that should be completed soon.
- **Medium**: Important cleanup, contract-alignment, or developer-experience work, but not the first emergency response.

## Recommended Execution Order

1. [06-java-21-baseline.md](/Users/finnetrolle/dev/rockopera/plans/priority-fixes/06-java-21-baseline.md)
2. [01-security-hardening-defaults.md](/Users/finnetrolle/dev/rockopera/plans/priority-fixes/01-security-hardening-defaults.md)
3. [02-runtime-correctness-and-observability.md](/Users/finnetrolle/dev/rockopera/plans/priority-fixes/02-runtime-correctness-and-observability.md)
4. [05-integration-test-strategy.md](/Users/finnetrolle/dev/rockopera/plans/priority-fixes/05-integration-test-strategy.md)
5. [03-agent-runtime-convergence.md](/Users/finnetrolle/dev/rockopera/plans/priority-fixes/03-agent-runtime-convergence.md)
6. [04-resolve-max-turns-contract.md](/Users/finnetrolle/dev/rockopera/plans/priority-fixes/04-resolve-max-turns-contract.md)

## Why This Order

### 1. Java 21 baseline first

Start by making local builds predictable. This removes environment confusion before larger changes and gives every subsequent task a stable development baseline.

### 2. Security hardening next

This is the highest-risk production-facing gap. Unsafe defaults, permissive CORS, and token exposure paths should be reduced before deeper architectural work.

### 3. Runtime correctness after security

Once immediate risk is reduced, fix the places where the service currently lies about what it is doing or what it knows.

### 4. Integration tests before major runtime refactors

Add behavioral protection before the larger architecture convergence work. This gives the team confidence to refactor without flying blind.

### 5. Agent runtime convergence after test coverage improves

This is a larger structural change. It should happen after the environment is stable, obvious correctness bugs are fixed, and meaningful integration tests exist.

### 6. `maxTurns` contract last

This intentionally comes after runtime convergence because the correct answer depends on what execution model RockOpera ends up supporting. It may become either a real feature or a removed/de-scoped contract.

## Dependency Notes

- [06-java-21-baseline.md](/Users/finnetrolle/dev/rockopera/plans/priority-fixes/06-java-21-baseline.md)
  No task dependency. This is a baseline-enabler.

- [01-security-hardening-defaults.md](/Users/finnetrolle/dev/rockopera/plans/priority-fixes/01-security-hardening-defaults.md)
  Can be executed independently after the build environment is stable.

- [02-runtime-correctness-and-observability.md](/Users/finnetrolle/dev/rockopera/plans/priority-fixes/02-runtime-correctness-and-observability.md)
  Can be executed independently after the build environment is stable.

- [05-integration-test-strategy.md](/Users/finnetrolle/dev/rockopera/plans/priority-fixes/05-integration-test-strategy.md)
  Strongly benefits from Task 06 being complete. It also benefits from Task 02 if you want tests to lock in the corrected runtime behavior.

- [03-agent-runtime-convergence.md](/Users/finnetrolle/dev/rockopera/plans/priority-fixes/03-agent-runtime-convergence.md)
  Strongly recommended after Task 05 so the refactor has protection.

- [04-resolve-max-turns-contract.md](/Users/finnetrolle/dev/rockopera/plans/priority-fixes/04-resolve-max-turns-contract.md)
  Should be executed after Task 03 because the right answer depends on the chosen runtime architecture.

## Parallelization Guidance

These tasks can be parallelized carefully:

- Task 01 and Task 02 can run in parallel after Task 06.
- Task 05 can start once the baseline environment is stable, but it is most valuable after Task 02 lands.
- Task 03 should generally be kept on a single owner because it is a high-coupling architecture task.
- Task 04 should not start until the direction from Task 03 is settled.

## Task Index

- [01-security-hardening-defaults.md](/Users/finnetrolle/dev/rockopera/plans/priority-fixes/01-security-hardening-defaults.md)
  Critical. Safer defaults, secret-safe git auth, secret-safe logging, and restricted CORS.

- [02-runtime-correctness-and-observability.md](/Users/finnetrolle/dev/rockopera/plans/priority-fixes/02-runtime-correctness-and-observability.md)
  High. Fixes incorrect runtime reporting and no-op startup cleanup behavior.

- [03-agent-runtime-convergence.md](/Users/finnetrolle/dev/rockopera/plans/priority-fixes/03-agent-runtime-convergence.md)
  High. Reduces long-term architecture ambiguity by converging on one real agent runtime model.

- [04-resolve-max-turns-contract.md](/Users/finnetrolle/dev/rockopera/plans/priority-fixes/04-resolve-max-turns-contract.md)
  Medium. Brings the public contract back in line with actual runtime capability.

- [05-integration-test-strategy.md](/Users/finnetrolle/dev/rockopera/plans/priority-fixes/05-integration-test-strategy.md)
  High. Adds the test coverage needed to safely change orchestration behavior.

- [06-java-21-baseline.md](/Users/finnetrolle/dev/rockopera/plans/priority-fixes/06-java-21-baseline.md)
  Medium. Makes local backend development predictable and failures understandable.

## Expected Usage

Use this directory in one of two ways:

1. As a sequential roadmap for a single owner.
2. As a task bank for multiple contributors, using the dependency notes and parallelization guidance above.

If multiple contributors are working at once, keep one person responsible for integration sequencing so the tasks remain coherent as a program of work rather than a pile of unrelated changes.
