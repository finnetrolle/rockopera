# RockOpera

**Autonomous coding agent orchestrator** — a long-running service that polls an issue tracker, dispatches AI coding agents to work on tasks, manages code reviews, and provides real-time observability.

RockOpera continuously monitors your issue tracker (Gitea or Linear), creates isolated workspaces for each issue, launches [Claude Code](https://docs.anthropic.com/en/docs/claude-code) agents, and manages the full lifecycle: coding → PR creation → automated code review → approval/revision.

## Features

- 🔄 **Automated workflow** — Polls issue tracker, dispatches agents, creates PRs, runs code reviews
- 🏗️ **Multi-phase pipelines** — Configurable phases (coding, review, etc.) with label-based state transitions
- 🔍 **Automated code review** — AI-powered PR reviews with inline comments posted directly to Gitea
- 📊 **Real-time dashboard** — Web UI showing running agents, token usage, retry queues
- 🔁 **Retry with backoff** — Automatic exponential backoff on failures
- ⚡ **Concurrent agents** — Run multiple agents in parallel with configurable concurrency limits
- 📝 **Workflow-as-code** — All configuration lives in a single `WORKFLOW.md` file with YAML front matter
- 🔃 **Hot reload** — `WORKFLOW.md` changes are picked up without restarting the service

## Architecture

```
┌─────────────┐     ┌──────────────┐     ┌─────────────┐
│   Gitea /    │◄───►│   Backend    │◄───►│ Claude Code │
│   Linear     │     │  (Kotlin)    │     │   Agent     │
└─────────────┘     └──────┬───────┘     └─────────────┘
                           │
                    ┌──────┴───────┐
                    │   Frontend   │
                    │ (React/Vite) │
                    └──────────────┘
```

- **Backend** — Kotlin + Ktor. Orchestrator, agent runner, tracker adapters, REST API
- **Frontend** — React + TypeScript + Vite. Real-time dashboard
- **Gitea** — Self-hosted Git + issue tracker (included in Docker Compose)

## Quick Start (Docker Compose)

### Prerequisites

- [Docker](https://docs.docker.com/get-docker/) and [Docker Compose](https://docs.docker.com/compose/install/)
- One of the following for the Claude Code agent:
  - an Anthropic-compatible endpoint (`ANTHROPIC_BASE_URL` + `ANTHROPIC_AUTH_TOKEN`)
  - or an OpenAI-like endpoint routed through LiteLLM (`docker compose --profile llm-gateway`)

### 1. Clone the repository

```bash
git clone https://github.com/your-org/rockopera.git
cd rockopera
```

### 2. Configure environment

```bash
cp .env.example .env
```

Edit `.env` and set your values:

| Variable | Description | Default |
|---|---|---|
| `GITEA_TOKEN` | Gitea API token | *(required)* |
| `GITEA_PROJECT_SLUG` | Repository in `owner/repo` format | `rockopera/myproject` |
| `GITEA_URL` | Gitea URL (internal Docker network) | `http://gitea:3000` |
| `GITEA_PORT` | Gitea external port (browser access) | `3001` |
| `GITEA_ROOT_URL` | Gitea root URL for links | `http://localhost:3001` |
| `TRACKER_ASSIGNEE` | Filter issues by assignee (`me` or username) | *(empty = all)* |
| `POLL_INTERVAL_MS` | Polling interval in milliseconds | `30000` |
| `MAX_CONCURRENT_AGENTS` | Max parallel agents | `10` |
| `BACKEND_PORT` | Backend API port | `4000` |
| `FRONTEND_PORT` | Dashboard UI port | `3000` |

For predefined UI-switchable profiles, put provider secrets and upstream model IDs in `.env`:

```dotenv
ANTHROPIC_MODEL=sonnet
ANTHROPIC_AUTH_TOKEN=your_glm_token
ANTHROPIC_BASE_URL=https://api.z.ai/api/anthropic
ANTHROPIC_DEFAULT_SONNET_MODEL=glm-5

GLM_ANTHROPIC_AUTH_TOKEN=your_glm_token
GLM_ANTHROPIC_BASE_URL=https://api.z.ai/api/anthropic
GLM_SONNET_MODEL=glm-5

LITELLM_MASTER_KEY=sk-rockopera-local
OPENAI_LIKE_API_BASE=https://your-openai-like-provider.example/v1
OPENAI_LIKE_API_KEY=your_provider_key
OPENAI_LIKE_SONNET_MODEL=openai/gpt-4.1
OPENAI_LIKE_OPUS_MODEL=openai/o3
OPENAI_LIKE_HAIKU_MODEL=openai/gpt-4.1-mini
```

The global `ANTHROPIC_*` variables above remain the startup default for backward compatibility. Then define switchable profiles in `WORKFLOW.md` under `agent.llm_profiles`. The default repository workflow already includes ready-to-switch `glm`, `gpt41`, and `o3` examples.

### 3. Start all services

```bash
docker compose up -d
```

If you use an OpenAI-like provider via LiteLLM, start with the gateway profile:

```bash
docker compose --profile llm-gateway up -d
```

This starts three containers by default. With the `llm-gateway` profile enabled, it also starts LiteLLM for Claude Code:

| Service | URL | Description |
|---|---|---|
| **Gitea** | http://localhost:3001 | Git server + issue tracker |
| **Backend** | http://localhost:4000 | RockOpera orchestrator API |
| **Frontend** | http://localhost:3000 | Monitoring dashboard |
| **LiteLLM** | *(internal only)* | Optional LLM gateway for OpenAI-like providers |

### 4. Set up Gitea

1. Open http://localhost:3001 and complete the initial setup
2. Create a user account
3. Create a repository (e.g., `myproject`)
4. Go to **User Settings → Applications** and generate an API token
5. Update `.env` with the token and `GITEA_PROJECT_SLUG=your-user/myproject`
6. Restart: `docker compose restart backend`

### 5. Create issues

Create issues in Gitea with labels matching your workflow states (e.g., `todo`, `review`). RockOpera will automatically pick them up and start working.

## Local Development

### Prerequisites

- JDK 21+
- Node.js 18+
- [Claude Code CLI](https://docs.anthropic.com/en/docs/claude-code) installed globally

### Backend

```bash
cd backend
./gradlew build
```

Run directly:

```bash
java -jar build/libs/rockopera-0.1.0.jar \
  --i-understand-that-this-will-be-running-without-the-usual-guardrails \
  --port 4000 \
  ../workflow/WORKFLOW.md
```

Or via Gradle:

```bash
./gradlew run --args="--i-understand-that-this-will-be-running-without-the-usual-guardrails --port 4000 ../workflow/WORKFLOW.md"
```

### Frontend

```bash
cd frontend
npm install
npm run dev
```

The dashboard will be available at http://localhost:5173 (Vite dev server).

## Workflow Configuration

All runtime behavior is defined in [`workflow/WORKFLOW.md`](workflow/WORKFLOW.md) — a Markdown file with YAML front matter:

```yaml
---
tracker:
  kind: gitea                          # "gitea" or "linear"
  endpoint: $GITEA_URL
  api_key: $GITEA_TOKEN
  project_slug: $GITEA_PROJECT_SLUG
  assignee: $TRACKER_ASSIGNEE
  active_states: [open, todo, in-progress, review]
  terminal_states: [done, closed]
polling:
  interval_ms: 30000
agent:
  max_concurrent_agents: 10
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
...
```

## Using OpenAI-like model providers

Claude Code does not talk to raw OpenAI Chat Completions endpoints directly. It expects either an Anthropic Messages endpoint (`/v1/messages`), or Bedrock / Vertex equivalents. For OpenAI-like providers, the supported pattern is:

1. Put LiteLLM in front of the provider.
2. Point Claude Code to LiteLLM with `ANTHROPIC_BASE_URL=http://litellm:4000`.
3. Map Claude aliases (`sonnet`, `opus`, `haiku`) to LiteLLM model names with `ANTHROPIC_DEFAULT_*_MODEL`.

This repository includes an optional `litellm` Docker Compose profile and [`gateway/start-litellm.sh`](gateway/start-litellm.sh) that generates a minimal LiteLLM config from `.env`.

For the predefined UI-switchable profiles in [`workflow/WORKFLOW.md`](workflow/WORKFLOW.md), the mapping is:

```dotenv
LITELLM_MASTER_KEY=sk-rockopera-local
OPENAI_LIKE_API_BASE=https://api.openai-like.example/v1
OPENAI_LIKE_API_KEY=secret
OPENAI_LIKE_SONNET_MODEL=openai/gpt-4.1
OPENAI_LIKE_OPUS_MODEL=openai/o3
```

Then start:

```bash
docker compose --profile llm-gateway up -d
```

The current direct `GLM-5` setup still works unchanged if your proxy already exposes an Anthropic-compatible API.

## Predefined LLM Profiles In UI

The dashboard can switch between predefined profiles without restarting the backend. The intended flow is:

1. Before startup, define provider credentials and upstream models in `.env`.
2. Before startup, define the actual switchable profiles in `agent.llm_profiles` inside [`workflow/WORKFLOW.md`](workflow/WORKFLOW.md).
3. Start RockOpera.
4. In the web UI, use the `LLM Profile` panel to switch the active profile.

Example workflow snippet:

```yaml
agent:
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
```

If `agent.llm_profiles.default` is omitted, RockOpera starts on the legacy global `ANTHROPIC_*` environment and keeps backward compatibility with existing single-profile setups. Profile switches apply to new runs only. Agents that are already running keep the profile they started with.

### Phases

| Phase | Description |
|---|---|
| **coding** | Triggered by `open`/`todo` labels. Agent writes code, commits, pushes, creates a PR. Transitions to `review`. |
| **review** | Triggered by `review` label. Agent reviews the PR diff, posts inline comments and a review verdict on the PR, and comments on the issue. Transitions to `done` (approved) or `todo` (changes requested). |

### Phase Options

| Option | Type | Description |
|---|---|---|
| `trigger_states` | `list` | Labels that trigger this phase |
| `creates_pr` | `bool` | Whether to commit, push, and create a PR |
| `needs_pr_diff` | `bool` | Fetch PR diff and pass to agent |
| `verdict_based` | `bool` | Parse agent output for APPROVED/CHANGES_REQUESTED verdict |
| `on_success` | `string` | Label to add on success |
| `on_approved` | `string` | Label to add when review approves |
| `on_changes_requested` | `string` | Label to add when review requests changes |
| `on_failure` | `string` | Label to add on failure |
| `label_on_start` | `string` | Label to add when phase starts |
| `command` | `string` | Override agent command for this phase |
| `prompt_template` | `string` | Override prompt template for this phase |

## API

### `GET /api/v1/state`

Returns the current orchestrator state: running agents, retry queue, token usage, rate limits.

### `POST /api/v1/refresh`

Triggers an immediate polling tick.

## CLI Options

```
rockopera [OPTIONS] [WORKFLOW_PATH]

Arguments:
  WORKFLOW_PATH          Path to WORKFLOW.md (default: ./WORKFLOW.md)

Options:
  --i-understand-that-this-will-be-running-without-the-usual-guardrails
                         Required safety acknowledgment flag
  --port PORT            HTTP server port (overrides WORKFLOW.md config)
  --logs-root DIR        Directory for log files
```

## Project Structure

```
rockopera/
├── backend/                    # Kotlin backend
│   └── src/main/kotlin/rockopera/
│       ├── Application.kt      # Entrypoint
│       ├── agent/              # Agent runner, prompt builder
│       ├── cli/                # CLI argument parsing
│       ├── config/             # Workflow config loading
│       ├── model/              # Domain models
│       ├── observability/      # Dashboard, logging
│       ├── orchestrator/       # Core orchestrator (state machine)
│       ├── tracker/            # Gitea & Linear adapters
│       ├── web/                # HTTP server, REST API
│       └── workspace/          # Workspace management
├── frontend/                   # React dashboard
│   └── src/
│       ├── components/         # UI components
│       ├── hooks/              # React hooks
│       ├── styles/             # CSS
│       └── types/              # TypeScript types
├── workflow/
│   └── WORKFLOW.md             # Workflow configuration + prompt
├── docker-compose.yml          # Full stack deployment
├── .env.example                # Environment template
└── README.md
```

## Tech Stack

| Component | Technology |
|---|---|
| Backend | Kotlin 2.1, Ktor 3.1, Coroutines |
| Frontend | React, TypeScript, Vite |
| Agent | Claude Code CLI |
| Issue Tracker | Gitea (self-hosted) or Linear |
| Build | Gradle (Shadow JAR), npm |
| Runtime | JDK 21, Node.js |
| Deployment | Docker Compose |

## License

This project is provided as-is for engineering preview purposes.
