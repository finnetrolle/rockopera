interface MetricCardProps {
  label: string
  value: string | number
  subtitle?: string
  variant?: 'active' | 'warning' | 'neutral'
}

export function MetricCard({ label, value, subtitle, variant = 'neutral' }: MetricCardProps) {
  return (
    <div className={`metric-card metric-${variant}`}>
      <div className="metric-label">{label}</div>
      <div className="metric-value">{value}</div>
      {subtitle && <div className="metric-subtitle">{subtitle}</div>}
    </div>
  )
}
