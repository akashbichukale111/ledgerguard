import { useEffect, useState } from 'react'
import { metricsApi, DashboardMetrics } from '../api/queries'
import '../styles/dashboard.css'

/** Sub-second lag is normal; beyond this the read model is visibly behind. */
const LAG_WARNING_MS = 1000
const LAG_ALERT_MS = 5000

/** Below this share of reconciled transactions closing automatically, something is off. */
const MATCH_RATE_WARNING = 0.95
const ERROR_RATE_ALERT = 0.05

/**
 * Renders a rate, or an explicit "no data yet" when nothing has reconciled.
 *
 * A null rate is not zero. Printing "0.0%" for an empty population would tell an operator the
 * system is failing when in fact it has not finished anything yet.
 */
function percent(rate: number | null): string {
  return rate === null ? '—' : `${(rate * 100).toFixed(1)}%`
}

/**
 * Shown when query-service reports the read model is unavailable.
 *
 * Deliberately not styled as an error: nothing has failed. The hosted demo can run without a
 * projection store, and an operator needs to know the panels are blank because the data cannot be
 * read — not because no transactions exist.
 */
function ReadModelBanner() {
  return (
    <div className="read-model-banner" role="status">
      <strong>Read model not available.</strong> Projections are served from MongoDB, which is not
      configured or not reachable in this environment. Transactions are still being accepted and
      published — the write path is unaffected — but they cannot be displayed here. Counts below are
      not zero readings; they are absent readings.
    </div>
  )
}

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
      {!metrics.readModelAvailable && <ReadModelBanner />}

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

        <div className="metric-card">
          <div className="metric-label">Match Rate</div>
          <div className="metric-value">{percent(metrics.matchRate)}</div>
          <div className="metric-status">
            {metrics.matchRate === null
              ? 'nothing reconciled yet'
              : metrics.matchRate >= MATCH_RATE_WARNING
                ? '✓ Healthy'
                : '⚠ Below target'}
          </div>
        </div>

        <div className="metric-card">
          <div className="metric-label">Needs Review</div>
          <div className="metric-value">{percent(metrics.reviewRate)}</div>
          <div className="metric-status">
            {metrics.reviewRate === null ? 'nothing reconciled yet' : 'awaiting an analyst'}
          </div>
        </div>

        <div className="metric-card">
          <div className="metric-label">Unmatched</div>
          <div className="metric-value">{percent(metrics.errorRate)}</div>
          <div className="metric-status">
            {metrics.errorRate === null
              ? 'nothing reconciled yet'
              : metrics.errorRate < ERROR_RATE_ALERT
                ? '✓ Low'
                : '⚠ High'}
          </div>
        </div>

        <div className="metric-card">
          <div className="metric-label">In Flight</div>
          <div className="metric-value">{metrics.pendingCount}</div>
          <div className="metric-status">of {metrics.transactionCount} total</div>
        </div>
      </div>

      <p className="metrics-note">
        Lag and last-hour counts are computed from the {metrics.sampleSize} most recent
        transactions, not the full collection. The three rates are exact counts over the whole
        collection, divided by the {metrics.reconciledCount} transactions that have reached a
        terminal outcome — not by all {metrics.transactionCount}, so a backlog does not read as a
        failure. Consumer lag is still not shown: nothing in this service can measure it.
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
          {metrics.errorRate !== null && metrics.errorRate >= ERROR_RATE_ALERT && (
            <div className="alert alert-error">
              🔴 {percent(metrics.errorRate)} of reconciled transactions found no counterpart.
            </div>
          )}
          {metrics.dltDepth === 0 &&
            metrics.projectionLagMillis < LAG_WARNING_MS &&
            (metrics.errorRate === null || metrics.errorRate < ERROR_RATE_ALERT) && (
              <div className="alert alert-success">✓ No alerts on the signals being measured.</div>
            )}
        </div>
      </div>
    </div>
  )
}
