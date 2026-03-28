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
  llm_profiles:
    items:
      - id: glm
        label: GLM-5
        env:
          ANTHROPIC_MODEL: sonnet
          ANTHROPIC_AUTH_TOKEN: $GLM_ANTHROPIC_AUTH_TOKEN
          ANTHROPIC_BASE_URL: $GLM_ANTHROPIC_BASE_URL
          ANTHROPIC_DEFAULT_SONNET_MODEL: $GLM_SONNET_MODEL
      - id: gpt41
        label: GPT-4.1
        env:
          ANTHROPIC_MODEL: sonnet
          ANTHROPIC_AUTH_TOKEN: $LITELLM_MASTER_KEY
          ANTHROPIC_BASE_URL: http://litellm:4000
          ANTHROPIC_DEFAULT_SONNET_MODEL: rockopera-sonnet
      - id: o3
        label: o3
        env:
          ANTHROPIC_MODEL: opus
          ANTHROPIC_AUTH_TOKEN: $LITELLM_MASTER_KEY
          ANTHROPIC_BASE_URL: http://litellm:4000
          ANTHROPIC_DEFAULT_OPUS_MODEL: rockopera-opus
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
{% if review_comments.size > 0 %}

Previous review feedback that MUST be addressed:
{% for comment in review_comments %}
---
[{{ comment.author }}{% if comment.createdAt %} at {{ comment.createdAt }}{% endif %}]:
{{ comment.body }}
{% endfor %}
---
IMPORTANT: You MUST fix ALL the issues mentioned in the review feedback above. Edit the files to address each comment. Do NOT just read files — make the actual changes.
{% endif %}
