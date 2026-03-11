# RockOpera - Full Project Specification

This document contains a complete specification for building RockOpera - an autonomous coding agent orchestrator, based on the architecture and behavior of Symphony. RockOpera is functionally equivalent to Symphony but built on a different technology stack: **Kotlin + Ktor** (backend) and **TypeScript/React/Vite** (frontend dashboard).

---

## Table of Contents

1. [Project Overview](#1-project-overview)
2. [Problem Statement](#2-problem-statement)
3. [Goals and Non-Goals](#3-goals-and-non-goals)
4. [System Architecture](#4-system-architecture)
5. [Domain Model](#5-domain-model)
6. [Workflow Specification](#6-workflow-specification)
7. [Configuration Layer](#7-configuration-layer)
8. [Orchestrator State Machine](#8-orchestrator-state-machine)
9. [Polling, Scheduling, and Reconciliation](#9-polling-scheduling-and-reconciliation)
10. [Workspace Management](#10-workspace-management)
11. [Agent Runner Protocol](#11-agent-runner-protocol)
12. [Issue Tracker Integration (Linear)](#12-issue-tracker-integration-linear)
13. [Prompt Construction](#13-prompt-construction)
14. [Observability and Logging](#14-observability-and-logging)
15. [HTTP Server and REST API](#15-http-server-and-rest-api)
16. [Web Dashboard (React)](#16-web-dashboard-react)
17. [CLI Entrypoint](#17-cli-entrypoint)
18. [Failure Model and Recovery](#18-failure-model-and-recovery)
19. [Security and Safety](#19-security-and-safety)
20. [Reference Algorithms](#20-reference-algorithms)
21. [Technology Stack Mapping](#21-technology-stack-mapping)

---

## 1. Project Overview

RockOpera is a long-running automation service that:

1. Continuously polls an issue tracker (Linear) for work items
2. Creates isolated filesystem workspaces for each issue
3. Launches a coding agent (Codex in app-server mode) in each workspace
4. Manages the full lifecycle: dispatch, retries, reconciliation, and cleanup
5. Provides real-time observability via a web dashboard and JSON API

The service acts as a **scheduler/runner and tracker reader**. It does NOT write to the issue tracker directly - ticket mutations (state transitions, comments, PR links) are performed by the coding agent using tools available in the workflow.

A successful run may end at a workflow-defined handoff state (e.g., `Human Review`), not necessarily `Done`.

---

## 2. Problem Statement

RockOpera solves four operational problems:

1. **Repeatable daemon workflow** - Turns issue execution into an automated process instead of manual scripts
2. **Workspace isolation** - Each agent runs in its own per-issue workspace directory, preventing cross-contamination
3. **In-repo workflow policy** - The `WORKFLOW.md` file keeps agent prompts and runtime settings version-controlled alongside the code
4. **Observability** - Provides enough visibility to operate and debug multiple concurrent agent runs

---

## 3. Goals and Non-Goals

### Goals

- Poll the issue tracker on a fixed cadence and dispatch work with bounded concurrency
- Maintain a single authoritative orchestrator state for dispatch, retries, and reconciliation
- Create deterministic per-issue workspaces and preserve them across runs
- Stop active runs when issue state changes make them ineligible
- Recover from transient failures with exponential backoff
- Load runtime behavior from `WORKFLOW.md`
- Expose operator-visible observability (structured logs + web dashboard + JSON API)
- Support restart recovery without requiring a persistent database
- Dynamic reload of `WORKFLOW.md` without service restart

### Non-Goals

- Multi-tenant control plane
- General-purpose workflow engine or distributed job scheduler
- Built-in business logic for editing tickets/PRs/comments (lives in agent tooling)
- Mandating a single sandbox/approval posture for all deployments

---

## 4. System Architecture

### 4.1 Components

```
+-------------------+     +------------------+     +-------------------+
|   CLI Entrypoint  |---->|   Application    |---->|   WorkflowStore   |
|   (main())        |     |   (Supervisor)   |     |   (file watcher)  |
+-------------------+     +------------------+     +-------------------+
                                  |
                    +-------------+-------------+
                    |             |             |
              +-----------+ +-----------+ +----------+
              |Orchestrator| |HttpServer | |StatusDash|
              | (poll loop)| | (Ktor)    | |(terminal)|
              +-----------+ +-----------+ +----------+
                    |             |
              +-----------+ +-----------+
              |AgentRunner| | REST API  |
              | (per-issue)| | Dashboard |
              +-----------+ +-----------+
                    |
              +-----+------+
              |    Codex    |
              | AppServer   |
              | (subprocess)|
              +-------------+
```

### 4.2 Components Detail

1. **Workflow Loader / WorkflowStore** - Reads `WORKFLOW.md`, parses YAML front matter + prompt template body. Watches for file changes (polling every 1s based on mtime+size+content hash) and reloads automatically. Keeps last known good config on reload failure.

2. **Config Layer** - Exposes typed getters for all workflow config values. Applies defaults, environment variable indirection (`$VAR`), path expansion (`~`). Validated with a schema.

3. **Issue Tracker Client (Linear)** - GraphQL client that fetches candidate issues, fetches states by IDs (reconciliation), fetches terminal-state issues (startup cleanup). Normalizes payloads into the Issue domain model. Supports pagination.

4. **Tracker Adapter** - Abstraction layer. Dispatches to the appropriate backend based on `tracker.kind` (currently: `linear` or `memory` for testing).

5. **Orchestrator** - The central stateful component. Owns the poll loop, in-memory runtime state, dispatch decisions, retry/backoff scheduling, reconciliation logic, and worker lifecycle management. Single-threaded mutation authority (no concurrent state modification).

6. **Workspace Manager** - Maps issue identifiers to filesystem paths. Creates directories, runs lifecycle hooks, removes workspaces. Enforces safety invariants (path containment, sanitization, symlink detection).

7. **Agent Runner** - Per-issue worker. Creates workspace, builds prompt, launches Codex app-server subprocess, streams events back to orchestrator. Supports multi-turn continuation within a single session.

8. **Codex AppServer Client** - Manages the JSON-RPC-over-stdio protocol with the Codex app-server subprocess. Handles initialization, thread/turn lifecycle, approval auto-responses, dynamic tool execution, timeout management.

9. **Dynamic Tool Handler** - Executes client-side tool calls from Codex. Currently supports `linear_graphql` for raw Linear GraphQL queries.

10. **Status Dashboard** - Terminal UI (ANSI) for real-time orchestrator status. Optional.

11. **HTTP Server** - Ktor server hosting the REST API and serving the React dashboard. Optional, enabled via `--port`.

12. **Prompt Builder** - Renders the workflow prompt template with issue data using a Liquid-compatible template engine with strict variable/filter checking.

13. **Log File Manager** - Configures rotating log files.

### 4.3 Abstraction Layers

1. **Policy Layer** (repo-defined) - `WORKFLOW.md` prompt body and team-specific rules
2. **Configuration Layer** (typed getters) - Parses front matter into typed runtime settings
3. **Coordination Layer** (orchestrator) - Polling, eligibility, concurrency, retries, reconciliation
4. **Execution Layer** (workspace + agent subprocess) - Filesystem lifecycle, coding-agent protocol
5. **Integration Layer** (Linear adapter) - API calls and normalization
6. **Observability Layer** (logs + dashboard + API) - Operator visibility

### 4.4 Supervision Tree

```
RockOpera.Supervisor (one_for_one)
  |-- PubSub (event bus for observability updates)
  |-- TaskSupervisor (for spawning agent worker coroutines)
  |-- WorkflowStore (file watcher GenServer equivalent)
  |-- Orchestrator (main poll loop)
  |-- HttpServer (Ktor, optional)
  |-- StatusDashboard (terminal UI, optional)
```

---

## 5. Domain Model

### 5.1 Issue

Normalized issue record used across orchestration, prompt rendering, and observability.

```kotlin
data class Issue(
    val id: String,                    // Stable tracker-internal UUID
    val identifier: String,            // Human-readable key, e.g. "ABC-123"
    val title: String,
    val description: String?,
    val priority: Int?,                // Lower = higher priority; null sorts last
    val state: String,                 // Current tracker state name
    val branchName: String?,
    val url: String?,
    val assigneeId: String?,
    val labels: List<String>,          // Normalized to lowercase
    val blockedBy: List<BlockerRef>,
    val assignedToWorker: Boolean = true,
    val createdAt: Instant?,
    val updatedAt: Instant?
)

data class BlockerRef(
    val id: String?,
    val identifier: String?,
    val state: String?
)
```

### 5.2 Workflow Definition

```kotlin
data class WorkflowDefinition(
    val config: Map<String, Any?>,     // YAML front matter root
    val promptTemplate: String         // Markdown body after front matter, trimmed
)
```

### 5.3 Workspace

```kotlin
data class Workspace(
    val path: String,                  // Absolute filesystem path
    val workspaceKey: String,          // Sanitized issue identifier
    val createdNow: Boolean            // true if directory was just created
)
```

### 5.4 Run Attempt

```kotlin
data class RunAttempt(
    val issueId: String,
    val issueIdentifier: String,
    val attempt: Int?,                 // null for first run, >= 1 for retries
    val workspacePath: String,
    val startedAt: Instant,
    val status: RunStatus,
    val error: String?
)

enum class RunStatus {
    PREPARING_WORKSPACE,
    BUILDING_PROMPT,
    LAUNCHING_AGENT_PROCESS,
    INITIALIZING_SESSION,
    STREAMING_TURN,
    FINISHING,
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    STALLED,
    CANCELED_BY_RECONCILIATION
}
```

### 5.5 Live Session

State tracked while a coding-agent subprocess is running.

```kotlin
data class RunningEntry(
    val workerJob: Job,                // Coroutine job handle
    val identifier: String,
    val issue: Issue,
    val issueId: String,
    val state: String,
    val startedAt: Instant,
    val sessionId: String?,
    val threadId: String?,
    val turnId: String?,
    val codexAppServerPid: String?,
    val lastCodexMessage: Any?,
    val lastCodexEvent: String?,
    val lastCodexTimestamp: Instant?,
    val codexInputTokens: Long = 0,
    val codexOutputTokens: Long = 0,
    val codexTotalTokens: Long = 0,
    val lastReportedInputTokens: Long = 0,
    val lastReportedOutputTokens: Long = 0,
    val lastReportedTotalTokens: Long = 0,
    val turnCount: Int = 0             // Number of turns started in this worker
)
```

### 5.6 Retry Entry

```kotlin
data class RetryEntry(
    val issueId: String,
    val identifier: String,
    val attempt: Int,                  // 1-based
    val dueAtMs: Long,                 // Monotonic clock timestamp
    val timerHandle: ScheduledFuture<*>?,
    val error: String?
)
```

### 5.7 Orchestrator Runtime State

```kotlin
data class OrchestratorState(
    var pollIntervalMs: Long,
    var maxConcurrentAgents: Int,
    var nextPollDueAtMs: Long,
    var pollCheckInProgress: Boolean = false,
    val running: MutableMap<String, RunningEntry> = mutableMapOf(),
    val claimed: MutableSet<String> = mutableSetOf(),
    val retryAttempts: MutableMap<String, RetryEntry> = mutableMapOf(),
    val completed: MutableSet<String> = mutableSetOf(),
    var codexTotals: CodexTotals = CodexTotals(),
    var codexRateLimits: Map<String, Any?>? = null
)

data class CodexTotals(
    var inputTokens: Long = 0,
    var outputTokens: Long = 0,
    var totalTokens: Long = 0,
    var secondsRunning: Double = 0.0
)
```

### 5.8 Identifier Normalization Rules

- **Issue ID** - Use for tracker lookups and internal map keys
- **Issue Identifier** - Use for human-readable logs and workspace naming
- **Workspace Key** - Derived from `issue.identifier`: replace any character NOT in `[A-Za-z0-9._-]` with `_`
- **Normalized Issue State** - Compare after `trim() + lowercase()`
- **Session ID** - Compose as `"<thread_id>-<turn_id>"`

---

## 6. Workflow Specification

### 6.1 File Discovery

Path precedence:
1. Explicit CLI argument path
2. Default: `WORKFLOW.md` in current working directory

If the file cannot be read, return `missing_workflow_file` error.

### 6.2 File Format

`WORKFLOW.md` is a Markdown file with optional YAML front matter.

Parsing rules:
- If file starts with `---`, parse lines until the next `---` as YAML front matter
- Remaining lines become the prompt body
- If front matter is absent, treat entire file as prompt body with empty config map
- YAML front matter must decode to a map/object; non-map YAML is an error
- Prompt body is trimmed before use

### 6.3 Front Matter Schema

Top-level keys: `tracker`, `polling`, `workspace`, `hooks`, `agent`, `codex`, `observability`, `server`

Unknown keys are ignored for forward compatibility.

#### `tracker` (object)

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `kind` | string | required | Currently: `"linear"` |
| `endpoint` | string | `https://api.linear.app/graphql` | For `kind=linear` |
| `api_key` | string/`$VAR` | `$LINEAR_API_KEY` | Env var reference supported |
| `project_slug` | string | required for `kind=linear` | Linear project slug ID |
| `assignee` | string/`$VAR`/null | null | `"me"` resolves to current viewer; literal ID for specific user |
| `active_states` | list/CSV string | `["Todo", "In Progress"]` | |
| `terminal_states` | list/CSV string | `["Closed", "Cancelled", "Canceled", "Duplicate", "Done"]` | |

#### `polling` (object)

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `interval_ms` | integer | `30000` | Dynamic: re-applied at runtime |

#### `workspace` (object)

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `root` | path/`$VAR` | `<system-temp>/rockopera_workspaces` | `~` expanded, `$VAR` expanded |

#### `hooks` (object)

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `after_create` | shell script | null | Runs only when workspace directory is newly created. Failure aborts workspace creation |
| `before_run` | shell script | null | Runs before each agent attempt. Failure aborts the attempt |
| `after_run` | shell script | null | Runs after each agent attempt. Failure logged and ignored |
| `before_remove` | shell script | null | Runs before workspace deletion. Failure logged and ignored |
| `timeout_ms` | integer | `60000` | Applies to all hooks. Non-positive falls back to default |

#### `agent` (object)

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `max_concurrent_agents` | integer | `10` | Dynamic |
| `max_turns` | integer | `20` | Max back-to-back turns per worker session |
| `max_retry_backoff_ms` | integer | `300000` (5 min) | Dynamic |
| `max_concurrent_agents_by_state` | map<string, int> | `{}` | Per-state concurrency limits; state keys normalized |

#### `codex` (object)

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `command` | shell command | `"codex app-server"` | Launched via `bash -lc <command>` |
| `approval_policy` | string/object | `{"reject":{"sandbox_approval":true,"rules":true,"mcp_elicitations":true}}` | Codex AskForApproval value |
| `thread_sandbox` | string | `"workspace-write"` | Codex SandboxMode value |
| `turn_sandbox_policy` | object | See below | Codex SandboxPolicy value |
| `turn_timeout_ms` | integer | `3600000` (1 hour) | Total turn stream timeout |
| `read_timeout_ms` | integer | `5000` | Request/response timeout during startup |
| `stall_timeout_ms` | integer | `300000` (5 min) | `<= 0` disables stall detection |

Default `turn_sandbox_policy`:
```json
{
  "type": "workspaceWrite",
  "writableRoots": ["<expanded_workspace_path>"],
  "readOnlyAccess": {"type": "fullAccess"},
  "networkAccess": false,
  "excludeTmpdirEnvVar": false,
  "excludeSlashTmp": false
}
```

#### `observability` (object)

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `dashboard_enabled` | boolean | `true` | Enable/disable terminal dashboard |
| `refresh_ms` | integer | `1000` | Dashboard data refresh interval |
| `render_interval_ms` | integer | `16` | Minimum re-render interval |

#### `server` (object, extension)

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `port` | integer/null | null | Enables HTTP server; `0` for ephemeral port |
| `host` | string | `"127.0.0.1"` | Bind address |

### 6.4 Prompt Template

The Markdown body of `WORKFLOW.md` is the per-issue prompt template.

- Use a **Liquid-compatible** template engine with strict mode
- Unknown variables must fail rendering
- Unknown filters must fail rendering
- Template input variables:
  - `issue` - object with all normalized issue fields (keys as strings)
  - `attempt` - integer or null (null on first run, integer on retry/continuation)
- If prompt body is empty, use a default: `"You are working on a Linear issue.\n\nIdentifier: {{ issue.identifier }}\nTitle: {{ issue.title }}\n\nBody:\n{% if issue.description %}\n{{ issue.description }}\n{% else %}\nNo description provided.\n{% endif %}"`
- Workflow file parse errors block dispatch; template render errors fail only the affected run

### 6.5 Dynamic Reload

- Watch `WORKFLOW.md` for changes (poll file stat every 1 second: mtime + size + content hash)
- On change, re-read and re-apply config and prompt template without restart
- New config applies to future dispatch, retry scheduling, reconciliation, hook execution, and agent launches
- In-flight agent sessions are NOT automatically restarted
- Invalid reloads keep the last known good config and emit an operator-visible error

---

## 7. Configuration Layer

### 7.1 Resolution Order

1. CLI arguments (e.g., `--port`, `--logs-root`)
2. YAML front matter values
3. Environment variable indirection via `$VAR_NAME`
4. Built-in defaults

### 7.2 Value Coercion

- **Paths**: `~` expanded to home directory; `$VAR` resolved before expansion; strings with `/` or `\` are `Path.expand`'d; bare strings preserved as-is
- **Env references**: `$VAR_NAME` pattern (`$` followed by `[A-Za-z_][A-Za-z0-9_]*`); empty env value treated as missing for secrets
- **CSV values**: `active_states` and `terminal_states` accept both YAML lists and comma-separated strings
- **Integers**: String integers are parsed; invalid values use default
- **State normalization**: `trim() + lowercase()` for all state comparisons

### 7.3 Dispatch Preflight Validation

Checked at startup and before each dispatch cycle:

1. Workflow file can be loaded and parsed
2. `tracker.kind` is present and supported (`"linear"`)
3. `tracker.api_key` is present after `$` resolution
4. `tracker.project_slug` is present
5. `codex.command` is present and non-empty
6. Codex runtime settings (approval_policy, thread_sandbox, turn_sandbox_policy) are valid

If startup validation fails -> fail startup.
If per-tick validation fails -> skip dispatch for that tick, keep reconciliation active.

---

## 8. Orchestrator State Machine

### 8.1 Issue Orchestration States (Internal, NOT tracker states)

1. **Unclaimed** - Issue is not running and has no retry scheduled
2. **Claimed** - Reserved to prevent duplicate dispatch; either Running or RetryQueued
3. **Running** - Worker task exists, tracked in `running` map
4. **RetryQueued** - Worker not running, retry timer exists in `retry_attempts`
5. **Released** - Claim removed (terminal, non-active, missing, or retry completed)

### 8.2 Important Nuance: Multi-Turn Continuation

A successful worker exit does NOT mean the issue is done:
- After each normal turn completion, the worker re-checks the issue state via tracker
- If still active, starts another turn on the **same live thread** (same Codex subprocess), up to `agent.max_turns`
- First turn uses the full rendered task prompt
- Continuation turns send only continuation guidance (NOT the original prompt)
- After the worker exits normally, the orchestrator schedules a **short continuation retry** (1 second) to re-check if the issue still needs work

### 8.3 Run Attempt Phases

1. `PreparingWorkspace`
2. `BuildingPrompt`
3. `LaunchingAgentProcess`
4. `InitializingSession`
5. `StreamingTurn`
6. `Finishing`
7. `Succeeded`
8. `Failed`
9. `TimedOut`
10. `Stalled`
11. `CanceledByReconciliation`

### 8.4 Transition Triggers

| Trigger | Action |
|---------|--------|
| **Poll Tick** | Reconcile -> Validate config -> Fetch candidates -> Sort -> Dispatch |
| **Worker Exit (normal)** | Remove from running, update totals, schedule continuation retry (1s) |
| **Worker Exit (abnormal)** | Remove from running, update totals, schedule exponential backoff retry |
| **Codex Update Event** | Update live session fields, token counters, rate limits |
| **Retry Timer Fired** | Re-fetch candidates, attempt re-dispatch or release claim |
| **Reconciliation State Refresh** | Stop runs whose issue states are terminal or non-active |
| **Stall Timeout** | Kill worker, schedule retry |

---

## 9. Polling, Scheduling, and Reconciliation

### 9.1 Poll Loop

At startup:
1. Validate config
2. Perform startup terminal workspace cleanup
3. Schedule immediate tick
4. Repeat every `polling.interval_ms`

Tick sequence:
1. Reconcile running issues
2. Run dispatch preflight validation
3. Fetch candidate issues from tracker
4. Sort issues by dispatch priority
5. Dispatch eligible issues while slots remain
6. Notify observability consumers

### 9.2 Candidate Selection Rules

An issue is dispatch-eligible only if ALL are true:
- Has `id`, `identifier`, `title`, and `state`
- State is in `active_states` and NOT in `terminal_states`
- Not already in `running`
- Not already in `claimed`
- Global concurrency slots available: `max(max_concurrent_agents - running_count, 0)`
- Per-state concurrency slots available: `max_concurrent_agents_by_state[state]` or global fallback
- If `assignee` is configured, issue must be assigned to matching user
- **Blocker rule for `Todo` state**: Do not dispatch when any blocker is non-terminal

### 9.3 Sorting Order

1. `priority` ascending (1..4 preferred; null/unknown sorts last)
2. `created_at` oldest first
3. `identifier` lexicographic tie-breaker

### 9.4 Retry and Backoff

**Normal continuation retries** (after clean worker exit): fixed delay of **1000ms**.

**Failure-driven retries**: `delay = min(10000 * 2^(attempt - 1), agent.max_retry_backoff_ms)`

Retry entry creation:
1. Cancel any existing retry timer for the same issue
2. Store `attempt`, `identifier`, `error`, `dueAtMs`, timer handle

Retry handling:
1. Fetch active candidate issues
2. Find specific issue by `issue_id`
3. If not found -> release claim
4. If found and eligible -> dispatch if slots available, else requeue with `"no available orchestrator slots"`
5. If found but no longer active -> release claim

### 9.5 Active Run Reconciliation

Runs every tick, two parts:

**Part A: Stall Detection**
- For each running issue, compute `elapsed_ms` since `last_codex_timestamp` (or `started_at` if no events)
- If `elapsed_ms > codex.stall_timeout_ms` -> terminate worker, queue retry
- If `stall_timeout_ms <= 0` -> skip stall detection

**Part B: Tracker State Refresh**
- Fetch current issue states for all running issue IDs
- For each running issue:
  - **Terminal state** -> terminate worker AND clean workspace
  - **Still active** -> update in-memory issue snapshot
  - **Neither active nor terminal** -> terminate worker WITHOUT workspace cleanup
- On state refresh failure -> keep workers running, try next tick

### 9.6 Startup Terminal Workspace Cleanup

On service start:
1. Query tracker for issues in terminal states
2. For each returned issue identifier, remove corresponding workspace directory
3. If fetch fails, log warning and continue startup

---

## 10. Workspace Management

### 10.1 Layout

- Root: `workspace.root` (normalized path)
- Per-issue path: `<workspace.root>/<sanitized_issue_identifier>`
- Workspaces are **reused** across runs for the same issue
- Successful runs do NOT auto-delete workspaces

### 10.2 Creation Algorithm

1. Sanitize identifier: `regex_replace(identifier, /[^A-Za-z0-9._-]/, "_")`
2. Compute path: `workspace_root / workspace_key`
3. If directory exists -> clean temp artifacts (`.elixir_ls`, `tmp`), mark `createdNow = false`
4. If file exists at path (not dir) -> rm and create dir, mark `createdNow = true`
5. If nothing exists -> create dir, mark `createdNow = true`
6. If `createdNow == true` -> run `after_create` hook if configured

### 10.3 Hooks Execution

- Execute via `sh -lc <script>` (or `bash -lc`) with workspace as `cwd`
- Apply `hooks.timeout_ms` timeout
- Hook output truncated in logs (max 2048 bytes)
- Failure semantics:
  - `after_create`: **fatal** to workspace creation
  - `before_run`: **fatal** to current run attempt
  - `after_run`: logged and **ignored**
  - `before_remove`: logged and **ignored**

### 10.4 Workspace Removal

For terminal issue cleanup:
1. Run `before_remove` hook if directory exists
2. `rm -rf` the workspace directory

### 10.5 Safety Invariants (CRITICAL)

**Invariant 1**: Coding agent cwd must be the per-issue workspace path, NOT the source repo.

**Invariant 2**: Workspace path must be inside workspace root.
- Normalize both to absolute paths
- Require workspace path starts with `workspace_root + "/"`
- Reject workspace path == workspace root
- Check for symlink escapes in path components

**Invariant 3**: Workspace key uses only `[A-Za-z0-9._-]`, everything else replaced with `_`.

---

## 11. Agent Runner Protocol

### 11.1 Worker Lifecycle

The agent runner is spawned per-issue as an async task/coroutine:

1. Create/reuse workspace for issue
2. Run `before_run` hook
3. Start Codex app-server session (subprocess)
4. Run turns in a loop (up to `max_turns`):
   a. Build prompt (full prompt for turn 1, continuation for subsequent)
   b. Start turn
   c. Stream and process turn events
   d. On turn success: check issue state, continue if still active
   e. On turn failure: propagate error
5. Stop Codex session (close subprocess)
6. Run `after_run` hook (failure ignored)
7. Return result to orchestrator

### 11.2 Codex App-Server Protocol (JSON-RPC over stdio)

**Subprocess Launch**:
- Command: `bash -lc <codex.command>`
- Working directory: workspace path
- Stdout: line-delimited JSON-RPC messages
- Stderr: diagnostic only (not parsed for protocol)
- Max line size: 10 MB

**Session Startup Handshake** (messages sent in order):

```json
// 1. Initialize request
{"id": 1, "method": "initialize", "params": {
    "clientInfo": {"name": "rockopera-orchestrator", "title": "RockOpera Orchestrator", "version": "0.1.0"},
    "capabilities": {"experimentalApi": true}
}}
// Wait for response

// 2. Initialized notification
{"method": "initialized", "params": {}}

// 3. Thread start request
{"id": 2, "method": "thread/start", "params": {
    "approvalPolicy": "<from config>",
    "sandbox": "<thread_sandbox from config>",
    "cwd": "/absolute/workspace/path",
    "dynamicTools": [<tool_specs>]
}}
// Read thread_id from response: result.thread.id

// 4. Turn start request
{"id": 3, "method": "turn/start", "params": {
    "threadId": "<thread_id>",
    "input": [{"type": "text", "text": "<rendered prompt>"}],
    "cwd": "/absolute/workspace/path",
    "title": "ABC-123: Issue Title",
    "approvalPolicy": "<from config>",
    "sandboxPolicy": "<turn_sandbox_policy from config>"
}}
// Read turn_id from response: result.turn.id
// session_id = "<thread_id>-<turn_id>"
```

### 11.3 Turn Streaming

Read line-delimited messages from stdout until turn terminates:

| Condition | Result |
|-----------|--------|
| `turn/completed` | Success |
| `turn/failed` | Failure |
| `turn/cancelled` | Failure |
| Turn timeout (`turn_timeout_ms`) | Failure |
| Subprocess exit | Failure |

For continuation turns: issue another `turn/start` on the same `threadId`. The subprocess stays alive.

### 11.4 Approval and Tool Call Handling

When `approval_policy == "never"` (auto-approve mode):

| Method | Behavior |
|--------|----------|
| `item/commandExecution/requestApproval` | Auto-respond with `{"decision": "acceptForSession"}` |
| `execCommandApproval` | Auto-respond with `{"decision": "approved_for_session"}` |
| `applyPatchApproval` | Auto-respond with `{"decision": "approved_for_session"}` |
| `item/fileChange/requestApproval` | Auto-respond with `{"decision": "acceptForSession"}` |
| `item/tool/requestUserInput` | Auto-answer with "Approve this Session" if option available, else reply with "non-interactive session" text |
| `item/tool/call` | Execute via DynamicTool handler, return result |
| Input-required methods | Fail the run |

When NOT auto-approve: approval requests return `:approval_required` error and fail the run.

### 11.5 Dynamic Tool: `linear_graphql`

Advertised in `thread/start` via `dynamicTools`:

```json
{
    "name": "linear_graphql",
    "description": "Execute a raw GraphQL query or mutation against Linear using RockOpera's configured auth.",
    "inputSchema": {
        "type": "object",
        "additionalProperties": false,
        "required": ["query"],
        "properties": {
            "query": {"type": "string", "description": "GraphQL query or mutation document"},
            "variables": {"type": ["object", "null"], "description": "Optional GraphQL variables", "additionalProperties": true}
        }
    }
}
```

Execution:
- Extract `query` (required, non-empty string) and `variables` (optional map) from arguments
- Execute against Linear using configured auth
- Return result:
  - Success (no GraphQL errors): `{"success": true, "contentItems": [{"type": "inputText", "text": "<json response>"}]}`
  - Failure: `{"success": false, "contentItems": [{"type": "inputText", "text": "<error json>"}]}`

### 11.6 Emitted Events (to Orchestrator)

Each event includes: `event` type, `timestamp`, `codex_app_server_pid`, optional `usage` map.

Event types:
- `session_started` - session_id, thread_id, turn_id
- `startup_failed` - reason
- `turn_completed` - payload
- `turn_failed` - payload
- `turn_cancelled` - payload
- `turn_ended_with_error` - session_id, reason
- `turn_input_required` - payload
- `approval_auto_approved` - payload, decision
- `approval_required` - payload (only in non-auto-approve mode)
- `tool_call_completed` - payload
- `tool_call_failed` - payload
- `unsupported_tool_call` - payload
- `tool_input_auto_answered` - payload, answer
- `notification` - generic Codex notification
- `other_message` - unrecognized message
- `malformed` - non-JSON line

### 11.7 Continuation Turn Prompt

For turns 2+ within the same worker session (NOT the first turn):

```
Continuation guidance:

- The previous Codex turn completed normally, but the Linear issue is still in an active state.
- This is continuation turn #<N> of <max_turns> for the current agent run.
- Resume from the current workspace and workpad state instead of restarting from scratch.
- The original task instructions and prior turn context are already present in this thread, so do not restate them before acting.
- Focus on the remaining ticket work and do not end the turn while the issue stays active unless you are truly blocked.
```

---

## 12. Issue Tracker Integration (Linear)

### 12.1 Tracker Adapter Interface

```kotlin
interface TrackerAdapter {
    suspend fun fetchCandidateIssues(): Result<List<Issue>>
    suspend fun fetchIssuesByStates(stateNames: List<String>): Result<List<Issue>>
    suspend fun fetchIssueStatesByIds(issueIds: List<String>): Result<List<Issue>>
    suspend fun createComment(issueId: String, body: String): Result<Unit>
    suspend fun updateIssueState(issueId: String, stateName: String): Result<Unit>
}
```

### 12.2 Linear GraphQL Queries

**Candidate Issues Query** (paginated):
```graphql
query SymphonyLinearPoll($projectSlug: String!, $stateNames: [String!]!, $first: Int!, $relationFirst: Int!, $after: String) {
    issues(filter: {project: {slugId: {eq: $projectSlug}}, state: {name: {in: $stateNames}}}, first: $first, after: $after) {
        nodes {
            id, identifier, title, description, priority
            state { name }
            branchName, url
            assignee { id }
            labels { nodes { name } }
            inverseRelations(first: $relationFirst) {
                nodes { type, issue { id, identifier, state { name } } }
            }
            createdAt, updatedAt
        }
        pageInfo { hasNextPage, endCursor }
    }
}
```

**Issues By ID Query** (for reconciliation):
```graphql
query SymphonyLinearIssuesById($ids: [ID!]!, $first: Int!, $relationFirst: Int!) {
    issues(filter: {id: {in: $ids}}, first: $first) {
        nodes { <same fields as above> }
    }
}
```

**Viewer Query** (for `assignee: me` resolution):
```graphql
query SymphonyLinearViewer { viewer { id } }
```

### 12.3 Linear Client Details

- Endpoint: from config (default `https://api.linear.app/graphql`)
- Auth: `Authorization: <token>` header (NOT `Bearer`)
- Content-Type: `application/json`
- Page size: 50
- Network timeout: 30 seconds
- Pagination: follow `pageInfo.hasNextPage` + `endCursor`

### 12.4 Issue Normalization

- `labels` -> extract from `labels.nodes[].name`, lowercase
- `blocked_by` -> from `inverseRelations.nodes` where `type == "blocks"` (case-insensitive)
- `priority` -> integer only; non-integers become null
- `created_at`, `updated_at` -> parse ISO-8601
- `assignee_id` -> from `assignee.id`
- `assigned_to_worker` -> true if no assignee filter, or if assignee matches configured filter

### 12.5 Assignee Filtering

If `tracker.assignee` is configured:
- `"me"` -> resolve via `viewer` query to get current user ID
- Other string -> use as literal assignee ID
- Issues not assigned to the matching user are filtered out

### 12.6 Error Categories

- `missing_linear_api_token`
- `missing_linear_project_slug`
- `linear_api_request` (transport failure)
- `linear_api_status` (non-200 HTTP)
- `linear_graphql_errors`
- `linear_unknown_payload`
- `linear_missing_end_cursor` (pagination error)

---

## 13. Prompt Construction

### 13.1 Inputs

- `workflow.prompt_template` (from WORKFLOW.md body)
- Normalized `issue` object
- Optional `attempt` integer (null for first run, integer for retry/continuation)

### 13.2 Rendering

- Use a **Liquid-compatible** template engine (e.g., [Liqp](https://github.com/bkiers/Liqp) for JVM)
- **Strict variables mode**: unknown variables fail rendering
- **Strict filters mode**: unknown filters fail rendering
- Convert issue object keys to strings for template compatibility
- Preserve nested arrays/maps (labels, blockers) for template iteration
- DateTime values serialized to ISO-8601 strings
- Struct/nested objects recursively converted to string-keyed maps

### 13.3 Failure Semantics

If prompt rendering fails:
- Fail the run attempt immediately
- Orchestrator treats it like any worker failure and applies retry logic

---

## 14. Observability and Logging

### 14.1 Logging Conventions

Required context fields for **issue-related** logs:
- `issue_id` (Linear UUID)
- `issue_identifier` (human key, e.g., `MT-620`)

Required context for **Codex session lifecycle** logs:
- `session_id`

Message formatting:
- Use stable `key=value` pairs
- Include action outcome: `completed`, `failed`, `retrying`
- Include concise failure reason when present
- Avoid logging large raw payloads

### 14.2 Log File

- Rotating disk log file
- Default path: `<cwd>/log/rockopera.log`
- Configurable via `--logs-root <path>`
- Default max bytes: 10 MB
- Default max files: 5
- When running as escript with log file configured, remove default console handler

### 14.3 Token Accounting

- Prefer `thread/tokenUsage/updated.tokenUsage.total` as authoritative absolute total
- Fallback to `info.total_token_usage`
- **Ignore** delta values (`last`, `last_token_usage`) for dashboard totals
- Track per-thread absolute high-water mark
- Compute deltas from `lastReported*` to avoid double-counting
- Accumulate into orchestrator `codexTotals`

### 14.4 Runtime Accounting

- Track cumulative seconds from ended sessions
- Add active session elapsed time at snapshot/render time (from `started_at`)
- No background ticking required

### 14.5 Rate Limit Tracking

- Track latest rate-limit payload from any agent update
- Store in `codexRateLimits` on orchestrator state

---

## 15. HTTP Server and REST API

### 15.1 Enablement

- Start when CLI `--port` argument is provided
- Start when `server.port` is present in WORKFLOW.md front matter
- CLI `--port` overrides `server.port`
- Bind to loopback (`127.0.0.1`) by default
- `port: 0` for ephemeral port

### 15.2 REST API Endpoints

#### `GET /api/v1/state`

Returns system state summary:

```json
{
    "generated_at": "2026-02-24T20:15:30Z",
    "counts": {"running": 2, "retrying": 1},
    "running": [
        {
            "issue_id": "abc123",
            "issue_identifier": "MT-649",
            "state": "In Progress",
            "session_id": "thread-1-turn-1",
            "turn_count": 7,
            "last_event": "turn_completed",
            "last_message": "Working on tests",
            "started_at": "2026-02-24T20:10:12Z",
            "last_event_at": "2026-02-24T20:14:59Z",
            "tokens": {"input_tokens": 1200, "output_tokens": 800, "total_tokens": 2000}
        }
    ],
    "retrying": [
        {
            "issue_id": "def456",
            "issue_identifier": "MT-650",
            "attempt": 3,
            "due_at": "2026-02-24T20:16:00Z",
            "error": "no available orchestrator slots"
        }
    ],
    "codex_totals": {"input_tokens": 5000, "output_tokens": 2400, "total_tokens": 7400, "seconds_running": 1834.2},
    "rate_limits": null
}
```

On error: `{"generated_at": "...", "error": {"code": "snapshot_timeout", "message": "Snapshot timed out"}}`

#### `GET /api/v1/<issue_identifier>`

Returns issue-specific details:

```json
{
    "issue_identifier": "MT-649",
    "issue_id": "abc123",
    "status": "running",
    "workspace": {"path": "/tmp/rockopera_workspaces/MT-649"},
    "attempts": {"restart_count": 1, "current_retry_attempt": 2},
    "running": {
        "session_id": "thread-1-turn-1",
        "turn_count": 7,
        "state": "In Progress",
        "started_at": "2026-02-24T20:10:12Z",
        "last_event": "notification",
        "last_message": "Working on tests",
        "last_event_at": "2026-02-24T20:14:59Z",
        "tokens": {"input_tokens": 1200, "output_tokens": 800, "total_tokens": 2000}
    },
    "retry": null,
    "logs": {"codex_session_logs": []},
    "recent_events": [{"at": "2026-02-24T20:14:59Z", "event": "notification", "message": "Working on tests"}],
    "last_error": null,
    "tracked": {}
}
```

If issue not found: `404` with `{"error": {"code": "issue_not_found", "message": "Issue not found"}}`

#### `POST /api/v1/refresh`

Triggers immediate poll + reconciliation cycle. Response (`202 Accepted`):

```json
{
    "queued": true,
    "coalesced": false,
    "requested_at": "2026-02-24T20:15:30Z",
    "operations": ["poll", "reconcile"]
}
```

If orchestrator unavailable: `503` with `{"error": {"code": "orchestrator_unavailable", "message": "..."}}`

#### Error Handling

- Unsupported methods on defined routes: `405 Method Not Allowed`
- Unknown routes: `404` with `{"error": {"code": "not_found", "message": "Route not found"}}`

### 15.3 Static Assets

The HTTP server also serves:
- `/` -> React SPA (dashboard)
- `/dashboard.css` -> Dashboard stylesheet
- JavaScript dependencies for the frontend

---

## 16. Web Dashboard (React)

### 16.1 Overview

The web dashboard at `/` provides real-time observability. In the reference implementation it uses Phoenix LiveView; for RockOpera, implement as a **React SPA** with **Vite** build that consumes the REST API.

### 16.2 Dashboard Sections

1. **Hero Card** - "RockOpera Observability - Operations Dashboard" with live/offline status indicator

2. **Metric Grid** (4 cards):
   - **Running** - count of active issue sessions
   - **Retrying** - count of issues in retry queue
   - **Total Tokens** - aggregate tokens (with in/out breakdown)
   - **Runtime** - total Codex runtime in `Nm Ns` format (completed + active sessions)

3. **Rate Limits** - Pretty-printed JSON of latest rate-limit snapshot

4. **Running Sessions Table**:
   - Columns: Issue (identifier + link to JSON details), State (color-coded badge), Session (copy ID button), Runtime / turns, Codex update (last event + message + timestamp), Tokens (total + in/out)

5. **Retry Queue Table**:
   - Columns: Issue (identifier + link), Attempt, Due at, Error

### 16.3 Real-Time Updates

- Poll `GET /api/v1/state` on an interval (e.g., every 1 second)
- OR use WebSocket/SSE if implementing push updates
- Runtime counter should tick client-side every second based on `started_at`

### 16.4 State Badge Colors

- Active states (`progress`, `running`, `active`): green/active badge
- Blocked/error states (`blocked`, `error`, `failed`): red/danger badge
- Queued states (`todo`, `queued`, `pending`, `retry`): yellow/warning badge
- Other: neutral badge

---

## 17. CLI Entrypoint

### 17.1 Usage

```
rockopera [--logs-root <path>] [--port <port>] [--i-understand-that-this-will-be-running-without-the-usual-guardrails] [path-to-WORKFLOW.md]
```

### 17.2 Behavior

1. Parse CLI arguments
2. Require `--i-understand-that-this-will-be-running-without-the-usual-guardrails` flag (safety acknowledgement)
3. If no workflow path provided, default to `./WORKFLOW.md`
4. Expand workflow path to absolute
5. Verify file exists
6. Set workflow file path in application config
7. Optionally set `--logs-root` and `--port`
8. Start the application
9. Wait for shutdown (monitor supervisor process)

### 17.3 Safety Banner

Without the acknowledgement flag, print a prominent banner:
```
╭──────────────────────────────────────────────────────────────────────────────────────────────────╮
│                                                                                                  │
│ This RockOpera implementation is a low key engineering preview.                                   │
│ Codex will run without any guardrails.                                                           │
│ RockOpera is not a supported product and is presented as-is.                                     │
│ To proceed, start with `--i-understand-that-this-will-be-running-without-the-usual-guardrails`   │
│                                                                                                  │
╰──────────────────────────────────────────────────────────────────────────────────────────────────╯
```

---

## 18. Failure Model and Recovery

### 18.1 Failure Classes

| Class | Examples |
|-------|----------|
| **Workflow/Config** | Missing WORKFLOW.md, invalid YAML, unsupported tracker kind, missing credentials |
| **Workspace** | Directory creation failure, hook timeout/failure, invalid path |
| **Agent Session** | Startup handshake failure, turn failed/cancelled/timeout, user input requested, subprocess exit, stalled |
| **Tracker** | API transport errors, non-200 HTTP, GraphQL errors, malformed payloads |
| **Observability** | Snapshot timeout, dashboard render errors, log sink failure |

### 18.2 Recovery Behavior

| Failure | Recovery |
|---------|----------|
| Dispatch validation fails | Skip new dispatches, keep service alive, continue reconciliation |
| Worker fails | Exponential backoff retry |
| Worker exits normally | Short continuation retry (1s) |
| Candidate fetch fails | Skip this tick, try next |
| Reconciliation state refresh fails | Keep current workers, retry next tick |
| Dashboard/log failures | Do NOT crash orchestrator |

### 18.3 Restart Recovery

Intentionally **in-memory** for scheduler state. After restart:
- No retry timers restored
- No running sessions assumed recoverable
- Recovery by:
  1. Startup terminal workspace cleanup
  2. Fresh polling of active issues
  3. Re-dispatching eligible work

---

## 19. Security and Safety

### 19.1 Workspace Safety (Mandatory)

- Workspace path MUST remain under configured workspace root
- Coding agent cwd MUST be the per-issue workspace path
- Workspace directory names MUST use sanitized identifiers
- Validate no symlink escapes in workspace path components

### 19.2 Secret Handling

- Support `$VAR` indirection in workflow config
- Do NOT log API tokens or secret values
- Validate presence of secrets without printing them
- Empty env values treated as missing for secrets

### 19.3 Hook Script Safety

- Hooks are fully trusted configuration (from WORKFLOW.md)
- Hooks run inside the workspace directory
- Hook output truncated in logs (max 2048 bytes)
- Hook timeouts required to avoid hanging the orchestrator

---

## 20. Reference Algorithms

### 20.1 Service Startup

```
function startService():
    configureLogging()
    startObservabilityOutputs()
    startWorkflowWatch(onChange = reloadAndReapplyWorkflow)

    state = OrchestratorState(
        pollIntervalMs = config.pollIntervalMs,
        maxConcurrentAgents = config.maxConcurrentAgents
    )

    validation = validateDispatchConfig()
    if validation is not OK:
        failStartup(validation)

    startupTerminalWorkspaceCleanup()
    scheduleTick(delayMs = 0)
    eventLoop(state)
```

### 20.2 Poll-and-Dispatch Tick

```
function onTick(state):
    state = reconcileRunningIssues(state)

    validation = validateDispatchConfig()
    if validation is not OK:
        logValidationError(validation)
        notifyObservers()
        scheduleTick(state.pollIntervalMs)
        return state

    issues = tracker.fetchCandidateIssues()
    if issues failed:
        logTrackerError()
        notifyObservers()
        scheduleTick(state.pollIntervalMs)
        return state

    for issue in sortForDispatch(issues):
        if noAvailableSlots(state): break
        if shouldDispatch(issue, state):
            state = dispatchIssue(issue, state, attempt = null)

    notifyObservers()
    scheduleTick(state.pollIntervalMs)
    return state
```

### 20.3 Dispatch One Issue

```
function dispatchIssue(issue, state, attempt):
    worker = spawnWorker { runAgentAttempt(issue, attempt, orchestratorPid) }

    if worker spawn failed:
        return scheduleRetry(state, issue.id, nextAttempt(attempt), error = "failed to spawn agent")

    state.running[issue.id] = RunningEntry(
        worker, issue,
        sessionId = null, codexAppServerPid = null,
        lastCodexMessage = null, lastCodexEvent = null, lastCodexTimestamp = null,
        codexInputTokens = 0, codexOutputTokens = 0, codexTotalTokens = 0,
        lastReportedInputTokens = 0, lastReportedOutputTokens = 0, lastReportedTotalTokens = 0,
        turnCount = 0, startedAt = now()
    )
    state.claimed.add(issue.id)
    cancelExistingRetry(state, issue.id)
    return state
```

### 20.4 Handle Worker Exit

```
function onWorkerExit(state, issueId, exitReason):
    entry = state.running.remove(issueId)
    if entry is null: return state

    // Accumulate runtime
    runDuration = now() - entry.startedAt
    state.codexTotals.secondsRunning += runDuration.seconds

    // Accumulate final token deltas
    state = accumulateTokenDeltas(state, entry)

    if exitReason is normal:
        // Schedule continuation retry to re-check if issue still needs work
        state = scheduleRetry(state, issueId, attempt = 1, delay = 1000ms)
    else:
        attempt = existingRetryAttempt(state, issueId) ?: 0
        state = scheduleRetry(state, issueId, attempt + 1, backoffDelay(attempt + 1))

    notifyObservers()
    return state
```

### 20.5 Handle Codex Update Event

```
function onCodexWorkerUpdate(state, issueId, message):
    entry = state.running[issueId]
    if entry is null: return state

    entry.lastCodexEvent = message.event
    entry.lastCodexTimestamp = message.timestamp
    entry.lastCodexMessage = message

    if message.event == "session_started":
        entry.sessionId = message.sessionId
        entry.threadId = message.threadId
        entry.turnId = message.turnId
        entry.codexAppServerPid = message.codexAppServerPid
        entry.turnCount += 1

    // Token accounting: prefer absolute totals
    tokenUsage = extractTokenUsage(message)
    if tokenUsage is not null:
        // Only update if absolute total >= last reported (monotonic)
        inputDelta = max(0, tokenUsage.input - entry.lastReportedInputTokens)
        outputDelta = max(0, tokenUsage.output - entry.lastReportedOutputTokens)
        totalDelta = max(0, tokenUsage.total - entry.lastReportedTotalTokens)

        entry.codexInputTokens += inputDelta
        entry.codexOutputTokens += outputDelta
        entry.codexTotalTokens += totalDelta
        entry.lastReportedInputTokens = tokenUsage.input
        entry.lastReportedOutputTokens = tokenUsage.output
        entry.lastReportedTotalTokens = tokenUsage.total

        state.codexTotals.inputTokens += inputDelta
        state.codexTotals.outputTokens += outputDelta
        state.codexTotals.totalTokens += totalDelta

    // Rate limits
    rateLimits = extractRateLimits(message)
    if rateLimits is not null:
        state.codexRateLimits = rateLimits

    notifyObservers()
    return state
```

### 20.6 Token Usage Extraction Priority

```
function extractTokenUsage(message):
    // Priority 1: thread/tokenUsage/updated -> tokenUsage.total
    if message contains "thread/tokenUsage/updated":
        return message.params.tokenUsage.total

    // Priority 2: nested total_token_usage (from codex/event/token_count)
    if message contains "total_token_usage":
        return message.info.total_token_usage

    // Priority 3: generic usage map with input/output/total fields
    usage = message.usage
    if usage is map with (input_tokens OR inputTokens) AND (output_tokens OR outputTokens):
        return normalize(usage)

    return null
```

---

## 21. Technology Stack Mapping

### 21.1 Elixir/OTP -> Kotlin/Ktor Mapping

| Symphony (Elixir/OTP) | RockOpera (Kotlin/Ktor) |
|------------------------|-------------------------|
| GenServer (Orchestrator) | Kotlin coroutine with `Mutex` for state serialization, or `actor` pattern from `kotlinx.coroutines` |
| GenServer (WorkflowStore) | Coroutine-based file watcher with `StateFlow` |
| GenServer (StatusDashboard) | Coroutine with periodic rendering |
| `Task.Supervisor` + `Task.async` | `CoroutineScope` + `launch`/`async` with `SupervisorJob` |
| `Process.monitor` | `Job.invokeOnCompletion` / `Job.join` |
| `send(pid, message)` | Kotlin `Channel` or `SharedFlow` |
| `Port` (subprocess management) | `ProcessBuilder` / `java.lang.Process` |
| Phoenix LiveView | React + Vite SPA consuming REST API |
| Phoenix Router + Controllers | Ktor routing + handlers |
| Bandit HTTP server | Ktor with Netty/CIO engine |
| `Phoenix.PubSub` | Kotlin `SharedFlow` or `BroadcastChannel` |
| ETS / Application env | In-memory `AtomicReference` or companion object state |
| `:logger` | SLF4J + Logback with rotating file appender |
| `Solid` (Liquid templates) | [Liqp](https://github.com/bkiers/Liqp) or similar JVM Liquid engine |
| `YamlElixir` | `snakeyaml` or `jackson-dataformat-yaml` |
| `Jason` | `kotlinx.serialization` or `jackson-module-kotlin` |
| `Req` (HTTP client) | Ktor HttpClient or OkHttp |
| `NimbleOptions` | Custom validation or `konf` library |
| Escript (executable JAR) | Shadow JAR / GraalVM native image / Gradle `application` plugin |
| `mix test` | JUnit 5 + `kotlin.test` |
| `File.stat` + content hash | `java.nio.file.Files.getLastModifiedTime` + `MessageDigest` |

### 21.2 Key Architecture Decisions for Kotlin

**Orchestrator as Actor**: Use a coroutine-based actor pattern where all state mutations go through a single coroutine processing a message channel. This preserves the single-authority serialization guarantee from the Elixir GenServer.

```kotlin
sealed class OrchestratorMessage {
    object Tick : OrchestratorMessage()
    data class WorkerExited(val issueId: String, val result: Result<Unit>) : OrchestratorMessage()
    data class CodexUpdate(val issueId: String, val message: Map<String, Any?>) : OrchestratorMessage()
    data class RetryFired(val issueId: String) : OrchestratorMessage()
    data class SnapshotRequest(val response: CompletableDeferred<OrchestratorSnapshot>) : OrchestratorMessage()
    data class RefreshRequest(val response: CompletableDeferred<RefreshResult>) : OrchestratorMessage()
}
```

**Subprocess Management**: Use `ProcessBuilder` with `redirectErrorStream(false)`. Read stdout line-by-line in a coroutine. Parse each line as JSON.

**Concurrency**: Use `kotlinx.coroutines` structured concurrency. Each agent worker is a child `launch` in a `SupervisorScope`. Worker failures don't propagate to the orchestrator.

### 21.3 Frontend (React/Vite)

- **Build**: Vite with React + TypeScript
- **State management**: React Query or SWR for API polling
- **Styling**: CSS (can port the dashboard styles from the reference)
- **Polling**: `useQuery` with 1-second refetch interval against `/api/v1/state`
- **Static serving**: Ktor serves the built SPA from resources

### 21.4 Project Structure (Suggested)

```
rockopera/
  backend/
    src/main/kotlin/rockopera/
      Application.kt              # Main + supervisor setup
      cli/
        Cli.kt                    # CLI argument parsing
      config/
        Config.kt                 # Typed config getters
        WorkflowLoader.kt         # WORKFLOW.md parser
        WorkflowStore.kt          # File watcher + cache
      orchestrator/
        Orchestrator.kt           # Main poll loop + state machine
        OrchestratorState.kt      # State data classes
      agent/
        AgentRunner.kt            # Per-issue worker
        PromptBuilder.kt          # Liquid template rendering
      codex/
        AppServerClient.kt        # JSON-RPC stdio protocol
        DynamicTool.kt            # Client-side tool execution
      tracker/
        TrackerAdapter.kt         # Interface
        LinearAdapter.kt          # Linear implementation
        LinearClient.kt           # GraphQL HTTP client
        MemoryAdapter.kt          # Test adapter
      workspace/
        WorkspaceManager.kt       # Filesystem operations + hooks
      model/
        Issue.kt
        RetryEntry.kt
        RunningEntry.kt
      observability/
        StatusDashboard.kt        # Terminal UI
        LogFileConfig.kt          # Rotating log setup
      web/
        HttpServer.kt             # Ktor setup
        ObservabilityApi.kt       # REST endpoints
        Presenter.kt              # Snapshot -> JSON projections
    src/main/resources/
      static/                     # Built React SPA
    src/test/kotlin/rockopera/
      ...
    build.gradle.kts
  frontend/
    src/
      App.tsx
      components/
        Dashboard.tsx
        MetricCard.tsx
        RunningSessionsTable.tsx
        RetryQueueTable.tsx
        RateLimitsPanel.tsx
      hooks/
        useOrchestratorState.ts
      types/
        api.ts                    # TypeScript types matching API responses
      styles/
        dashboard.css
    index.html
    vite.config.ts
    package.json
    tsconfig.json
```

### 21.5 Dependencies (Suggested)

**Backend (Gradle/Kotlin)**:
- `io.ktor:ktor-server-core` + `ktor-server-netty`
- `io.ktor:ktor-server-content-negotiation` + `ktor-serialization-kotlinx-json`
- `io.ktor:ktor-client-core` + `ktor-client-cio` (for Linear API)
- `org.jetbrains.kotlinx:kotlinx-coroutines-core`
- `org.jetbrains.kotlinx:kotlinx-serialization-json`
- `com.fasterxml.jackson.dataformat:jackson-dataformat-yaml` (YAML parsing)
- `nl.big-o:liqp` (Liquid template engine)
- `ch.qos.logback:logback-classic` (logging)
- `org.junit.jupiter:junit-jupiter` (testing)

**Frontend (npm)**:
- `react`, `react-dom`
- `@tanstack/react-query` (API polling)
- `vite`
- `typescript`
