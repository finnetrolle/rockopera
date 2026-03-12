import { useEffect, useRef, useState, useCallback } from 'react'
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

function formatDuration(startIso: string, endIso: string): string {
  const diff = (new Date(endIso).getTime() - new Date(startIso).getTime()) / 1000
  const m = Math.floor(diff / 60)
  const s = Math.floor(diff % 60)
  return `${m}m ${s}s`
}

interface CompletedSession {
  session: RunningSession
  completedAt: string
}

interface Props {
  sessions: RunningSession[]
}

function ActivityLog({ entries }: { entries: string[] }) {
  const logRef = useRef<HTMLUListElement>(null)

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

function CompletedActivityLog({ entries }: { entries: string[] }) {
  if (entries.length === 0) return null

  return (
    <ul className="activity-log">
      {entries.map((entry, i) => (
        <li key={`${i}-${entry.slice(0, 20)}`} className="step-done">
          <span className="dot">{'\u2713'}</span>
          <span>{entry}</span>
        </li>
      ))}
    </ul>
  )
}

const MAX_COMPLETED = 20

export function RunningSessionsTable({ sessions }: Props) {
  const [completed, setCompleted] = useState<CompletedSession[]>([])
  const [expandedIds, setExpandedIds] = useState<Set<string>>(new Set())
  const prevSessionIdsRef = useRef<Set<string>>(new Set())
  const prevSessionsRef = useRef<Map<string, RunningSession>>(new Map())

  useEffect(() => {
    const currentIds = new Set(sessions.map(s => s.issue_id))
    const prevIds = prevSessionIdsRef.current
    const prevSessions = prevSessionsRef.current

    const disappeared: CompletedSession[] = []
    for (const id of prevIds) {
      if (!currentIds.has(id)) {
        const session = prevSessions.get(id)
        if (session) {
          disappeared.push({
            session,
            completedAt: new Date().toISOString()
          })
        }
      }
    }

    if (disappeared.length > 0) {
      setCompleted(prev => [...disappeared, ...prev].slice(0, MAX_COMPLETED))
    }

    prevSessionIdsRef.current = currentIds
    prevSessionsRef.current = new Map(sessions.map(s => [s.issue_id, s]))
  }, [sessions])

  const toggleExpanded = useCallback((id: string) => {
    setExpandedIds(prev => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }, [])

  const clearCompleted = useCallback(() => {
    setCompleted([])
    setExpandedIds(new Set())
  }, [])

  if (sessions.length === 0 && completed.length === 0) return null

  return (
    <section className="table-section">
      <h2>Running Sessions</h2>
      <div className="session-cards">
        {sessions.map(s => (
          <div className="session-card" key={s.issue_id}>
            <div className="session-card-header">
              {s.project_slug && <span className="badge badge-neutral">{s.project_slug}</span>}
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

        {completed.length > 0 && (
          <>
            <div className="completed-header">
              <span className="completed-label">Completed ({completed.length})</span>
              <button className="completed-clear" onClick={clearCompleted}>Clear</button>
            </div>
            {completed.map((c, idx) => {
              const s = c.session
              const key = `${s.issue_id}-${idx}`
              const isExpanded = expandedIds.has(key)
              return (
                <div
                  className={`session-card session-card-completed ${isExpanded ? '' : 'session-card-collapsed'}`}
                  key={key}
                  onClick={() => toggleExpanded(key)}
                >
                  <div className="session-card-header">
                    <span className="collapse-toggle">{isExpanded ? '\u25BC' : '\u25B6'}</span>
                    {s.project_slug && <span className="badge badge-neutral">{s.project_slug}</span>}
                    <span className="issue-id">{s.issue_identifier}</span>
                    <span className="badge badge-done">done</span>
                    <span className="meta">
                      {formatDuration(s.started_at, c.completedAt)} &middot; {s.tokens.total_tokens.toLocaleString()} tok
                      {s.activity_log.length > 0 && ` · ${s.activity_log.length} steps`}
                    </span>
                  </div>
                  {isExpanded && (
                    <div onClick={e => e.stopPropagation()}>
                      <CompletedActivityLog entries={s.activity_log} />
                    </div>
                  )}
                </div>
              )
            })}
          </>
        )}
      </div>
    </section>
  )
}
