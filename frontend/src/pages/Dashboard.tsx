import { useEffect, useState } from 'react'
import { metricsApi, DashboardMetrics } from '../api/queries'
import '../styles/dashboard.css'

/** Sub-second lag is normal; beyond this the read model is visibly behind. */
const LAG_WARNING_MS = 1000
const LAG_ALERT_MS = 5000

export default function Dashboard() {
  const [metrics, setMetrics] = useState<DashboardMetrics | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false

    const fetchMetrics = async () => {
      try {
        const response = await metricsApi.dashboard()
        if (!cancelled) {
          setMetrics(response.data)
          setError(null)
        }
      } catch {
        // A transient poll failure should not blank out the last good reading.
        if (!cancelled) setError('Failed to load metrics')
      } finally {
        if (!cancelled) setLoading(false)
      }
    }

    fetchMetrics()
    const interval = setInterval(fetchMetrics, 5000)
    return () => {
      cancelled = true
      clearInterval(interval)
    }
  }, [])

  if (loading) return <div className="loading">Loading dashboard...</div>
  if (error && !metrics) return <div className="error">{error}</div>
  if (!metrics) return <div className="error">No metrics available</div>

  return (
    <div className="dashboard">
      <h1>Operations Dashboard</h1>
      {error && <div className="alert alert-warning">{error} — showing last known values.</div>}

      <div className="metrics-grid">
        <div className="metric-card">
          <div className="metric-label">Projection Lag</div>
          <div className="metric-value">{metrics.projectionLagMillis}ms</div>
          <div className="metric-status">
            {metrics.projectionLagMillis < LAG_WARNING_MS ? '✓ Healthy' : '⚠ High'}
          </div>
        </div>

        <div className="metric-card">
          <div className="metric-label">DLT Depth</div>
          <div className="metric-value">{metrics.dltDepth}</div>
          <div className="metric-status">
            {metrics.dltDepth === 0 ? '✓ Empty' : '⚠ Review needed'}
          </div>
        </div>

        <div className="metric-card">
          <div className="metric-label">Transactions</div>
          <div className="metric-value">{metrics.transactionCount}</div>
          <div className="metric-status">in the read model</div>
        </div>

        <div className="metric-card">
          <div className="metric-label">Last Hour</div>
          <div className="metric-value">{metrics.transactionsLastHour}</div>
          <div className="metric-status">of {metrics.sampleSize} sampled</div>
        </div>

        <div className="metric-card">
          <div className="metric-label">Audit Chain</div>
          <div className="metric-value">{metrics.auditChainLength}</div>
          <div className="metric-status">entries</div>
        </div>
      </div>

      <p className="metrics-note">
        Lag and last-hour counts are computed from the {metrics.sampleSize} most recent
        transactions, not the full collection. Consumer lag, match rate and error rate are not
        shown: nothing in this service can measure them yet.
      </p>

      <div className="alerts-section">
        <h2>Alerts</h2>
        <div className="alert-list">
          {metrics.dltDepth > 0 && (
            <div className="alert alert-warning">
              ⚠️ {metrics.dltDepth} message(s) in the dead-letter topic. Review and replay if needed.
            </div>
          )}
          {metrics.projectionLagMillis > LAG_ALERT_MS && (
            <div className="alert alert-warning">
              ⚠️ High projection lag: {metrics.projectionLagMillis}ms. Check Kafka consumer status.
            </div>
          )}
          {metrics.dltDepth === 0 && metrics.projectionLagMillis < LAG_WARNING_MS && (
            <div className="alert alert-success">✓ No alerts on the signals being measured.</div>
          )}
        </div>
      </div>
    </div>
  )
}
