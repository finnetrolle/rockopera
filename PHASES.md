# RockOpera Phases — Agent Configuration Guide

RockOpera uses a **phases** system to define different types of agents. Each phase describes what an agent does, when it triggers, and how it transitions issues between states.

## How It Works

The orchestrator polls the issue tracker for issues in `active_states`. When an issue is dispatched, `AgentRunner` looks up which phase matches the issue's current state. The phase controls the agent's behavior: what command to run, what prompt to use, whether to create a PR, parse verdicts, etc.

If no phases are defined, RockOpera falls back to a default coding agent — full backward compatibility.

## Configuration

Phases are defined in the `phases:` section of your `WORKFLOW.md` YAML front matter.

### Minimal Example

```yaml
---
tracker:
  kind: gitea
  endpoint: $GITEA_URL
  api_key: $GITEA_TOKEN
  project_slug: $GITEA_PROJECT_SLUG
  active_states: [todo, review]
  terminal_states: [done, closed]

phases:
  coding:
    trigger_states: [todo]
    creates_pr: true
    on_success: review
    label_on_start: in-progress

  review:
    trigger_states: [review]
    needs_pr_diff: true
    verdict_based: true
    on_approved: done
    on_changes_requested: todo
---
You are working on issue {{ issue.identifier }}: {{ issue.title }}

{{ issue.description }}
```

### Full Example with Custom Prompts and Commands

```yaml
phases:
  coding:
    trigger_states: [todo]
    command: "claude -p --output-format stream-json --dangerously-skip-permissions"
    creates_pr: true
    on_success: review
    on_failure: todo
    label_on_start: in-progress

  review:
    trigger_states: [review]
    command: "claude -p --output-format stream-json --dangerously-skip-permissions"
    needs_pr_diff: true
    verdict_based: true
    on_approved: testing
    on_changes_requested: todo
    prompt_template: |
      You are a code reviewer for issue {{ issue.identifier }}.

      Issue: {{ issue.title }}
      Description: {{ issue.description }}

      PR #{{ pr.number }}: {{ pr.title }}
      Diff:
      ```
      {{ pr.diff }}
      ```

      Review the changes. Use gitea_api tool to comment on the PR.
      End with VERDICT:APPROVED or VERDICT:CHANGES_REQUESTED.

  testing:
    trigger_states: [testing]
    command: "bash -c 'cd /workspace && npm test 2>&1'"
    on_success: done
    on_failure: todo
    prompt_template: "not used — command runs tests directly"
```

## PhaseConfig Reference

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `trigger_states` | list of strings | required | Issue states that activate this phase |
| `command` | string | `agent.command` | Shell command to run. Falls back to the global `agent.command` |
| `prompt_template` | string | WORKFLOW.md body | Liquid template for the agent prompt. Falls back to the main prompt after the YAML front matter |
| `creates_pr` | bool | `false` | Create a git branch, commit changes, push, and open a PR in Gitea |
| `needs_pr_diff` | bool | `false` | Find the open PR for this issue and inject its diff into the prompt via `pr.*` template variables |
| `verdict_based` | bool | `false` | Parse agent output for `VERDICT:APPROVED` or `VERDICT:CHANGES_REQUESTED` and transition accordingly |
| `on_success` | string | `"done"` | Label to set when the phase completes successfully (non-verdict phases) |
| `on_failure` | string | null | Label to set on failure. If null, the issue stays in its current state and retries with exponential backoff |
| `on_approved` | string | `"done"` | Label to set when verdict is APPROVED (verdict-based phases only) |
| `on_changes_requested` | string | `"todo"` | Label to set when verdict is CHANGES_REQUESTED (verdict-based phases only) |
| `label_on_start` | string | null | Label to add when the phase starts (e.g., `"in-progress"`). Removed on completion |

## Template Variables

All prompts are Liquid templates with access to:

### Always available

| Variable | Description |
|----------|-------------|
| `issue.id` | Issue ID (Gitea issue number) |
| `issue.identifier` | Display identifier (e.g., `#42`) |
| `issue.title` | Issue title |
| `issue.description` | Issue body text |
| `issue.state` | Current workflow state |
| `issue.priority` | Priority number (if set) |
| `issue.labels` | List of label names |
| `issue.url` | URL to the issue |
| `attempt` | Retry attempt number, or `false` on first run |

### Available when `needs_pr_diff: true`

| Variable | Description |
|----------|-------------|
| `pr.number` | PR number |
| `pr.title` | PR title |
| `pr.diff` | Full diff of the PR (unified diff format) |

## Environment Variables

Every agent process receives these environment variables:

| Variable | Description |
|----------|-------------|
| `ROCKOPERA_ISSUE_ID` | Issue ID |
| `ROCKOPERA_ISSUE_IDENTIFIER` | Display identifier |
| `ROCKOPERA_ISSUE_TITLE` | Issue title |
| `ROCKOPERA_ISSUE_URL` | Issue URL (if available) |
| `ROCKOPERA_PHASE` | Name of the current phase |
| `ROCKOPERA_PR_NUMBER` | PR number (if `needs_pr_diff: true`) |

## State Flow

Issues move between states via labels. The orchestrator only picks up issues whose state is in `active_states`. Terminal states cause the orchestrator to stop tracking the issue.

```
                    +------ CHANGES_REQUESTED ------+
                    |                               |
                    v                               |
todo ──> [coding agent] ──> review ──> [reviewer agent] ──> done
              |                                         |
              +──── on_failure (retry) ─────────────────+
```

### Important Rules

1. Every state that a phase uses as `trigger_states` must be in `active_states` in the tracker config. Otherwise the orchestrator won't fetch those issues.

2. The `on_success`, `on_approved`, `on_changes_requested` labels should either be another phase's `trigger_states` (to continue the pipeline) or a `terminal_states` value (to finish).

3. If `on_failure` is null, the issue stays in its current state and the orchestrator retries with exponential backoff. If `on_failure` is set, the issue transitions to that state immediately — useful for sending failed reviews back to coding.

4. `label_on_start` is cosmetic — it marks the issue as actively being worked on. It's automatically removed when the phase completes or fails.

## Recipes

### Coding only (no review)

```yaml
phases:
  coding:
    trigger_states: [todo]
    creates_pr: true
    on_success: done
```

### Coding + Review

```yaml
phases:
  coding:
    trigger_states: [todo]
    creates_pr: true
    on_success: review
    label_on_start: in-progress
  review:
    trigger_states: [review]
    needs_pr_diff: true
    verdict_based: true
    on_approved: done
    on_changes_requested: todo
```

### Coding + Review + Testing

```yaml
phases:
  coding:
    trigger_states: [todo]
    creates_pr: true
    on_success: review
    label_on_start: in-progress
  review:
    trigger_states: [review]
    needs_pr_diff: true
    verdict_based: true
    on_approved: testing
    on_changes_requested: todo
  testing:
    trigger_states: [testing]
    command: "bash run_tests.sh"
    on_success: done
    on_failure: todo
```

### Different AI models per phase

```yaml
phases:
  coding:
    trigger_states: [todo]
    command: "claude -p --model claude-sonnet-4-6 --output-format stream-json --dangerously-skip-permissions"
    creates_pr: true
    on_success: review
  review:
    trigger_states: [review]
    command: "claude -p --model claude-opus-4-6 --output-format stream-json --dangerously-skip-permissions"
    needs_pr_diff: true
    verdict_based: true
    on_approved: done
    on_changes_requested: todo
```

### Concurrency limits per phase

Use `max_concurrent_agents_by_state` in the `agent` section to limit how many agents run per state:

```yaml
agent:
  max_concurrent_agents: 5
  max_concurrent_agents_by_state:
    todo: 3
    review: 2
```

This runs at most 3 coding agents and 2 reviewers simultaneously.
