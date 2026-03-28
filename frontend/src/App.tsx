import { useOrchestratorState } from './hooks/useOrchestratorState'
import { useLlmProfiles } from './hooks/useLlmProfiles'
import { MetricCard } from './components/MetricCard'
import { RunningSessionsTable } from './components/RunningSessionsTable'
import { RetryQueueTable } from './components/RetryQueueTable'
import { RateLimitsPanel } from './components/RateLimitsPanel'
import { LlmProfilePanel } from './components/LlmProfilePanel'

function formatRuntime(seconds: number): string {
  const m = Math.floor(seconds / 60)
  const s = Math.floor(seconds % 60)
  return `${m}m ${s}s`
}

export default function App() {
  const { data, isLoading, isError } = useOrchestratorState()
  const llmProfiles = useLlmProfiles()

  const isLive = !!data && !data.error

  return (
    <div className="dashboard">
      <div className="hero-card">
        <h1>RockOpera Observability</h1>
        <p>Operations Dashboard</p>
        <span className={`status-indicator ${isLive ? 'live' : 'offline'}`}>
          {isLive ? 'LIVE' : 'OFFLINE'}
        </span>
      </div>

      {isLoading && <p className="loading">Loading...</p>}
      {isError && <p className="error">Failed to connect to orchestrator</p>}

      <LlmProfilePanel
        data={llmProfiles.data}
        isLoading={llmProfiles.isLoading}
        isError={llmProfiles.isError}
        isSwitching={llmProfiles.isSwitching}
        errorMessage={llmProfiles.switchError?.message}
        onSwitch={llmProfiles.switchProfile}
      />

      {data && !data.error && (
        <>
          <div className="metric-grid">
            <MetricCard label="Running" value={data.counts.running} variant="active" />
            <MetricCard label="Retrying" value={data.counts.retrying} variant="warning" />
            <MetricCard
              label="Total Tokens"
              value={data.agent_totals.total_tokens.toLocaleString()}
              subtitle={`In: ${data.agent_totals.input_tokens.toLocaleString()} / Out: ${data.agent_totals.output_tokens.toLocaleString()}`}
            />
            <MetricCard
              label="Runtime"
              value={formatRuntime(data.agent_totals.seconds_running)}
            />
          </div>

          <RateLimitsPanel rateLimits={data.rate_limits} />
          <RunningSessionsTable sessions={data.running} />
          <RetryQueueTable entries={data.retrying} />
        </>
      )}
    </div>
  )
}
