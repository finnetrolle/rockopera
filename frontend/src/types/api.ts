export interface OrchestratorState {
  generated_at: string
  counts: { running: number; retrying: number }
  running: RunningSession[]
  retrying: RetryEntry[]
  agent_totals: AgentTotals
  rate_limits: Record<string, unknown> | null
  error?: { code: string; message: string }
}

export interface RunningSession {
  issue_id: string
  issue_identifier: string
  state: string
  project_slug: string
  session_id: string | null
  turn_count: number
  last_event: string | null
  last_message: string | null
  started_at: string
  last_event_at: string | null
  tokens: TokenUsage
  activity_log: string[]
}

export interface RetryEntry {
  issue_id: string
  issue_identifier: string
  project_slug: string
  attempt: number
  due_at: string
  error: string | null
}

export interface AgentTotals {
  input_tokens: number
  output_tokens: number
  total_tokens: number
  seconds_running: number
}

export interface TokenUsage {
  input_tokens: number
  output_tokens: number
  total_tokens: number
}
