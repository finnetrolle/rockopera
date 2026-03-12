import { useEffect, useRef } from 'react'
import type { RunningSession } from '../types/api'

function stateBadgeClass(state: string): string {
  const s = state.toLowerCase()
  if (['progress', 'running', 'active'].some(k => s.includes(k))) return 'badge-active'
  if (['blocked', 'error', 'failed'].some(k => s.includes(k))) return 'badge-danger'
  if (['todo', 'queued', 'pending', 'retry'].some(k => s.includes(k))) return 'badge-warning'
  return 'badge-neutral'
}

function elapsedSince(isoDate: string): string {
  const diff = (Date.now() - new Date(isoDate).getTime()) / 1000
  const m = Math.floor(diff / 60)
  const s = Math.floor(diff % 60)
  return `${m}m ${s}s`
}

interface Props {
  sessions: RunningSession[]
}

function ActivityLog({ entries }: { entries: string[] }) {
  const logRef = useRef<HTMLUListElement>(null)

  // Auto-scroll to bottom when new entries are appended (length changes).
  // Intentionally using length as dependency — only scroll on new entries, not re-renders.
  useEffect(() => {
    if (logRef.current) {
      logRef.current.scrollTop = logRef.current.scrollHeight
    }
  }, [entries.length])

  if (entries.length === 0) return null

  return (
    <ul className="activity-log" ref={logRef}>
      {entries.map((entry, i) => {
        const isLast = i === entries.length - 1
        return (
          <li key={`${i}-${entry.slice(0, 20)}`} className={isLast ? 'step-active' : 'step-done'}>
            <span className="dot">{isLast ? '\u25CF' : '\u2713'}</span>
            <span>{entry}</span>
          </li>
        )
      })}
    </ul>
  )
}

export function RunningSessionsTable({ sessions }: Props) {
  if (sessions.length === 0) return null

  return (
    <section className="table-section">
      <h2>Running Sessions</h2>
      <div className="session-cards">
        {sessions.map(s => (
          <div className="session-card" key={s.issue_id}>
            <div className="session-card-header">
              <a href={`/api/v1/${s.issue_identifier}`} className="issue-id">{s.issue_identifier}</a>
              <span className={`badge ${stateBadgeClass(s.state)}`}>{s.state}</span>
              <span className="meta">
                {elapsedSince(s.started_at)} &middot; {s.tokens.total_tokens.toLocaleString()} tok
                {s.activity_log.length > 0 && ` · ${s.activity_log.length} steps`}
              </span>
            </div>
            <ActivityLog entries={s.activity_log} />
          </div>
        ))}
      </div>
    </section>
  )
}
