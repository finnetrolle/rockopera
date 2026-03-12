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
- An [Anthropic API key](https://console.anthropic.com/) (for Claude Code agent)

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

### 3. Start all services

```bash
docker compose up -d
```

This starts three containers:

| Service | URL | Description |
|---|---|---|
| **Gitea** | http://localhost:3001 | Git server + issue tracker |
| **Backend** | http://localhost:4000 | RockOpera orchestrator API |
| **Frontend** | http://localhost:3000 | Monitoring dashboard |

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
