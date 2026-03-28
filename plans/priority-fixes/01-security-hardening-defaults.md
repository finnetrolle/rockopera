# Task 01: Security Hardening of Default Behavior

## Criticality

**Level:** Critical

This task is critical because the current defaults can expose real credentials, over-open the HTTP surface, and normalize unsafe agent execution in production-like environments.

## Objective

Move RockOpera from "unsafe by default engineering preview" to "explicitly opt-in for unsafe behavior".

This task is successful when a fresh RockOpera deployment:

- does not disable agent permissions by default
- does not expose permissive CORS to every origin by default
- does not place tracker tokens in clone URLs or command logs
- does not print secret-bearing shell commands to logs
- clearly documents how to opt into dangerous behavior when a team consciously wants it

## Why This Matters

Right now the project advertises and implements several risky defaults:

- the default agent command includes `--dangerously-skip-permissions`
- the HTTP server enables `anyHost()` CORS
- git clone/push flows embed the Gitea token in the URL
- git commands are logged verbatim, which creates a direct secret leakage path

These are not theoretical issues. They affect real deployments, real logs, and real credentials.

## Current State

Review these files before changing anything:

- `backend/src/main/kotlin/rockopera/config/WorkflowDefinition.kt`
- `backend/src/main/kotlin/rockopera/config/WorkflowLoader.kt`
- `workflow/WORKFLOW.md`
- `README.md`
- `backend/src/main/kotlin/rockopera/web/HttpServer.kt`
- `backend/src/main/kotlin/rockopera/agent/AgentRunner.kt`
- `backend/src/main/kotlin/rockopera/agent/CliAgentClient.kt`
- `.env.example`

Important existing risk points:

- default dangerous agent command in config defaults
- permissive CORS in `HttpServer`
- token-bearing clone URL in `AgentRunner.gitClone()`
- raw command logging in `runShellCommand()`

## Non-Goals

Do not turn this into a full enterprise security program.

Specifically out of scope for this task:

- adding full authentication to the RockOpera web UI
- building RBAC
- redesigning the entire sandbox model for every possible agent provider
- rotating or provisioning secrets automatically

## Deliverables

Produce all of the following in one coherent change:

1. Safer default agent command
2. Configurable CORS allowlist instead of `anyHost()`
3. Secret-safe git authentication flow
4. Secret masking in logs
5. Updated docs and examples
6. Tests for new behavior where practical

## Required Outcome

After this task:

- a default install must not skip permissions unless explicitly configured
- the system must not put the Gitea token in git remote URLs, shell command strings, or logs
- CORS must default to a closed or minimal configuration
- docs must explain the new default and the explicit opt-in path

## Recommended Implementation Strategy

### 1. Remove Unsafe Agent Defaults

Change the default command so it no longer includes `--dangerously-skip-permissions`.

Places to update:

- `WorkflowDefinition.kt`
- `WorkflowLoader.kt`
- `README.md`
- `workflow/WORKFLOW.md`
- any tests that assert the old default command

Recommended new default:

```text
claude -p --verbose --output-format stream-json
```

If the system still needs a "run fully unattended and unsafe" mode, require that mode to be declared explicitly in `WORKFLOW.md` by the operator.

Direct instruction:

- do not silently preserve the unsafe default
- force the user to opt into dangerous behavior in config

### 2. Replace Open CORS With Explicit Allowlist

The current `anyHost()` behavior is too open for an operator-facing service.

Add a config field for allowed origins. A simple shape is enough. Example:

```yaml
server:
  port: 4000
  host: 0.0.0.0
  allowed_origins:
    - http://localhost:3000
```

Implementation guidance:

- parse `server.allowed_origins` as a string list
- if the list is empty, prefer not installing permissive CORS at all
- if the list is present, install CORS and register exactly those origins
- keep `Content-Type` and any other truly needed headers explicit

Direct instruction:

- do not replace `anyHost()` with a different wildcard-like behavior
- do not default to `*`

### 3. Remove Tokens From Git URLs and Command Strings

This is the highest-sensitivity part of the task.

Current anti-pattern:

- `http://rockopera:TOKEN@host/owner/repo.git`

Required replacement:

- authenticate git without placing the token in the URL
- keep credentials outside logged command text

Recommended approach:

1. Add a small credential helper mechanism for git operations.
2. Use process-level environment variables and an ephemeral `GIT_ASKPASS` script or equivalent.
3. Ensure the helper file is created with restrictive permissions and cleaned up reliably.

Good properties of the solution:

- token never appears in the git remote URL
- token never appears in log messages
- token is not persisted into `.git/config`
- solution works for clone, fetch, push, and branch reuse flows

Direct instruction:

- do not "mask after the fact" while still placing the token in the command string
- the token must not enter the command string in the first place

### 4. Introduce Secret-Aware Command Logging

`runShellCommand()` currently logs the full git command.

Refactor command execution so callers can provide:

- the real command to execute
- an optional sanitized log label
- optional environment overrides

Example target shape:

```kotlin
runShellCommand(
    workDir = workspace.path,
    cmd = "git clone http://gitea/owner/repo.git .",
    logLabel = "git clone <repo>",
    env = gitAuthEnv
)
```

Quality bar:

- logs should still be useful for debugging
- logs must never contain tokens, passwords, or secret headers
- failures should remain diagnosable without exposing credentials

### 5. Review Other Secret Surfaces

While in this area, inspect these paths:

- HTTP headers set by Gitea client
- environment passed into child processes
- exception messages that may echo failing URLs
- README examples that still teach unsafe defaults

If a value can plausibly include a secret, prefer masking or avoiding it entirely.

### 6. Update Documentation

Update all operator-facing docs so they reflect the new behavior.

Minimum documentation changes:

- README quick start
- workflow example
- `.env.example`
- any comments that still imply unsafe mode is the normal mode

The docs must answer these questions clearly:

- what the new default behavior is
- how to opt into dangerous unattended mode
- how to configure allowed origins
- what security assumptions remain

## Suggested File-Level Plan

### `WorkflowDefinition.kt`

- change the default command
- add `allowedOrigins` or equivalent server config field

### `WorkflowLoader.kt`

- parse the new CORS allowlist
- keep defaults conservative

### `HttpServer.kt`

- remove `anyHost()`
- install CORS only when allowed origins are configured

### `AgentRunner.kt`

- replace tokenized clone URL flow
- route git auth through a secret-safe mechanism
- update command logging calls

### `README.md` and `workflow/WORKFLOW.md`

- update examples and explanatory text

### Tests

Add or update tests for:

- config parsing for `allowed_origins`
- default command no longer includes dangerous flag
- command sanitization or masking helpers

## Acceptance Criteria

All of the following must be true:

1. A default generated config or default runtime path does not skip permissions.
2. `HttpServer` no longer allows all origins by default.
3. No git command logs include the Gitea token.
4. Clone/fetch/push still work with the new auth path.
5. Documentation matches actual behavior.
6. Existing tests pass and new security-related tests pass.

## Manual Verification Checklist

Perform these checks after implementation:

1. Start the service with a normal config and confirm the agent command does not include `--dangerously-skip-permissions` unless explicitly set.
2. Inspect logs during clone/fetch/push and confirm no token appears.
3. Confirm `.git/config` does not store a tokenized remote URL.
4. Issue a browser request from an unapproved origin and confirm CORS is not open.
5. Verify the approved frontend origin still works when configured.

## Pitfalls to Avoid

- masking secrets only in logs while still embedding them in command strings
- keeping old dangerous examples in docs after code is changed
- breaking git rework flows while securing clone/push
- adding overly clever secret plumbing that becomes impossible to maintain
- treating "internal tool" as justification for unsafe defaults

## Strong Recommendations

- prefer simple, boring, auditable security improvements
- keep the unsafe path possible only as an explicit opt-in
- document the rationale in code comments where future maintainers might otherwise reintroduce the old behavior

## Definition of Done

This task is done only when the code, config defaults, and documentation all agree that RockOpera is safer by default and when secret leakage through git URLs and logs has been concretely removed.
