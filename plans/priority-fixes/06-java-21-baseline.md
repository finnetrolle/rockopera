# Task 06: Enforce and Document Java 21 as the Supported Development Baseline

## Criticality

**Level:** Medium

This task is medium criticality because it mainly addresses developer experience and environment clarity; it does not directly fix production behavior, but it removes a costly source of confusion and failed local builds.

## Objective

Make the supported Java version explicit, enforce it early, and replace confusing build failures with a fast, human-readable message.

This task is driven by a real compatibility problem:

- the backend toolchain works on Java 21
- the Gradle/Kotlin setup currently fails on Java 25 with an opaque error like `IllegalArgumentException: 25.0.2`

That is a poor developer experience and an avoidable source of confusion.

## Required Outcome

After this task:

- the project clearly declares Java 21 as the supported baseline
- developers get a friendly failure before Gradle emits a confusing stacktrace
- docs and local setup hints all agree on the same version

## Why This Matters

Environment ambiguity wastes time.

When a maintainer runs `./gradlew test`, they should not have to reverse-engineer whether:

- the code is broken
- Gradle is broken
- Kotlin is broken
- or the local JDK is unsupported

The repository should answer that question immediately and clearly.

## Current State

Inspect these files:

- `backend/build.gradle.kts`
- `backend/gradle.properties`
- `backend/gradlew`
- `backend/gradlew.bat`
- `README.md`

Also verify the current toolchain behavior locally if needed.

Important observation:

- the Gradle build script itself may fail before Kotlin DSL project code can provide a friendly message

That means the best place for an early check is often the wrapper script, not only the Gradle build logic.

## Non-Goals

Do not use this task to fully upgrade the stack to Java 25 compatibility.

Out of scope:

- broad Gradle and Kotlin plugin upgrades for future-JDK support
- matrix support for every JDK from 17 to 25
- changing the runtime Docker image away from Java 21

## Recommended Implementation Strategy

### 1. Add a Preflight Check to `backend/gradlew`

Implement a shell-level version check before handing off to the Gradle wrapper.

The check should:

- inspect the active Java version
- detect the major version robustly
- allow Java 21
- fail fast for unsupported versions with a clear message

The error message should explicitly say something like:

```text
RockOpera backend currently supports Java 21 for local Gradle builds.
Detected Java 25.0.2.
Please set JAVA_HOME to a JDK 21 installation and retry.
```

Direct instruction:

- do not rely only on Gradle build logic for this check
- the wrapper script should stop the confusing failure before Gradle starts

### 2. Add Equivalent Behavior to `backend/gradlew.bat`

Windows users should receive the same clarity.

The exact implementation can differ, but the behavior should match:

- clear version check
- clear message
- early exit

### 3. Keep Gradle Toolchain Declaration on Java 21

The build already targets Java 21. Preserve that.

Use this task to ensure the repository is internally consistent:

- wrapper preflight says Java 21
- Gradle toolchain says Java 21
- docs say Java 21

### 4. Add Developer Environment Hints

Add one or more lightweight hints for local tooling.

Good options:

- `.java-version`
- `.tool-versions`
- a short section in README with exact `JAVA_HOME` guidance

You do not need every ecosystem file, but give developers at least one easy path.

### 5. Update Documentation

README must clearly state:

- Java 21 is required for backend local development
- Java 25 is currently not supported for local Gradle builds
- how to point `JAVA_HOME` at a Java 21 install

If you mention example commands, keep them concrete.

## Suggested File-Level Work

### `backend/gradlew`

- add preflight version parsing and friendly exit

### `backend/gradlew.bat`

- add equivalent Windows behavior

### `backend/build.gradle.kts`

- keep or clarify Java 21 toolchain intent

### `README.md`

- document the supported version and troubleshooting note

### Optional repo-root helper files

- `.java-version`
- `.tool-versions`

Only add what the team is likely to use.

## Acceptance Criteria

All of the following must be true:

1. Running `backend/gradlew` with an unsupported JDK fails immediately with a clear human-readable message.
2. Running with Java 21 continues normally.
3. README states the supported Java version explicitly.
4. Toolchain configuration and documentation agree with each other.

## Manual Verification Checklist

Perform both checks manually:

1. With Java 21 active, run `./gradlew test` and confirm the wrapper proceeds normally.
2. With Java 25 active, run `./gradlew test` and confirm the wrapper fails fast with the new explanatory message instead of a Gradle/Kotlin stacktrace.

If possible, also verify the Windows wrapper logic conceptually or on a Windows environment.

## Pitfalls to Avoid

- adding a check only in Gradle Kotlin DSL, which may be too late
- writing brittle version parsing that breaks on patch versions like `21.0.8`
- claiming support for multiple JDKs without actually verifying them
- updating docs but not the wrapper scripts

## Strong Recommendations

- optimize for a clear developer experience, not for cleverness
- keep the message actionable and concrete
- if future JDK support is desired, track that as a separate upgrade task

## Definition of Done

This task is done only when a developer with the wrong JDK gets a fast, helpful error, a developer with Java 21 can build normally, and the repository clearly communicates that Java 21 is the supported baseline.
