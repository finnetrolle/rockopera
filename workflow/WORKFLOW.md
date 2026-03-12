---
tracker:
  kind: gitea
  endpoint: $GITEA_URL
  api_key: $GITEA_TOKEN
  # Single repo (legacy): project_slug: $GITEA_PROJECT_SLUG
  # Multi-repo: use 'projects' list instead of 'project_slug'
  projects:
    - slug: $GITEA_PROJECT_SLUG
  assignee: $TRACKER_ASSIGNEE
  active_states: [open, todo, in-progress, review]
  terminal_states: [done, closed]
polling:
  interval_ms: $POLL_INTERVAL_MS
agent:
  max_concurrent_agents: $MAX_CONCURRENT_AGENTS
  max_turns: 1
  command: "claude -p --verbose --output-format stream-json --dangerously-skip-permissions"
  turn_timeout_ms: 3600000
  stall_timeout_ms: 300000
phases:
  coding:
    trigger_states: [open, todo]
    creates_pr: true
    on_success: review
    label_on_start: in-progress
  review:
    trigger_states: [review]
    needs_pr_diff: true
    verdict_based: true
    on_approved: done
    on_changes_requested: todo
server:
  port: 4000
  host: "0.0.0.0"
---
You are working on a Gitea issue.

Identifier: {{ issue.identifier }}
Title: {{ issue.title }}

Body:
{% if issue.description %}
{{ issue.description }}
{% else %}
No description provided.
{% endif %}

{% if attempt %}
This is retry attempt #{{ attempt }}.
{% endif %}
