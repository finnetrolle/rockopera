interface Props {
  rateLimits: Record<string, unknown> | null
}

export function RateLimitsPanel({ rateLimits }: Props) {
  if (!rateLimits) return null

  return (
    <section className="table-section">
      <h2>Rate Limits</h2>
      <pre className="rate-limits-json">
        {JSON.stringify(rateLimits, null, 2)}
      </pre>
    </section>
  )
}
