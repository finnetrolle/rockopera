import type { RetryEntry } from '../types/api'

interface Props {
  entries: RetryEntry[]
}

export function RetryQueueTable({ entries }: Props) {
  if (entries.length === 0) return null

  return (
    <section className="table-section">
      <h2>Retry Queue</h2>
      <table>
        <thead>
          <tr>
            <th>Issue</th>
            <th>Attempt</th>
            <th>Due At</th>
            <th>Error</th>
          </tr>
        </thead>
        <tbody>
          {entries.map(e => (
            <tr key={e.issue_id}>
              <td>
                <a href={`/api/v1/${e.issue_identifier}`}>{e.issue_identifier}</a>
              </td>
              <td>{e.attempt}</td>
              <td>{new Date(e.due_at).toLocaleTimeString()}</td>
              <td className="error-text">{e.error ?? '-'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  )
}
