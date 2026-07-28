import { useEffect, useState } from 'react'
import { metricsApi, DashboardMetrics } from '../api/queries'
import '../styles/dashboard.css'

export default function Dashboard() {
  const [metrics, setMetrics] = useState<DashboardMetrics | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    const fetchMetrics = async () => {
      try {
        const response = await metricsApi.dashboard()
        setMetrics(response.data)
      } catch (err) {
        setError('Failed to load metrics')
        console.error(err)
      } finally {
        setLoading(false)
      }
    }

    fetchMetrics()
    const interval = setInterval(fetchMetrics, 5000)
    return () => clearInterval(interval)
  }, [])

  if (loading) return <div className="loading">Loading dashboard...</div>
  if (error) return <div className="error">{error}</div>
  if (!metrics) return <div className="error">No metrics available</div>

  return (
    <div className="dashboard">
      <h1>Operations Dashboard</h1>

      <div className="metrics-grid">
        <div className="metric-card">
          <div className="metric-label">Projection Lag</div>
          <div className="metric-value">{metrics.projectionLag}ms</div>
          <div className="metric-status">
            {metrics.projectionLag < 1000 ? '✓ Healthy' : '⚠ High'}
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
          <div className="metric-label">Consumer Lag</div>
          <div className="metric-value">{metrics.consumerLag}</div>
          <div className="metric-status">
            {metrics.consumerLag < 100 ? '✓ Healthy' : '⚠ High'}
          </div>
        </div>

        <div className="metric-card">
          <div className="metric-label">Transaction Rate</div>
          <div className="metric-value">{metrics.transactionRate.toFixed(1)}/s</div>
          <div className="metric-status">✓ Active</div>
        </div>

        <div className="metric-card">
          <div className="metric-label">Match Rate</div>
          <div className="metric-value">{(metrics.matchRate * 100).toFixed(1)}%</div>
          <div className="metric-status">
            {metrics.matchRate > 0.95 ? '✓ Excellent' : '⚠ Review'}
          </div>
        </div>

        <div className="metric-card">
          <div className="metric-label">Error Rate</div>
          <div className="metric-value">{(metrics.errorRate * 100).toFixed(2)}%</div>
          <div className="metric-status">
            {metrics.errorRate < 0.01 ? '✓ Low' : '⚠ High'}
          </div>
        </div>
      </div>

      <div className="alerts-section">
        <h2>Recent Alerts</h2>
        <div className="alert-list">
          {metrics.dltDepth > 0 && (
            <div className="alert alert-warning">
              ⚠️ {metrics.dltDepth} message(s) in Dead-Letter Topic. Review and replay if needed.
            </div>
          )}
          {metrics.projectionLag > 5000 && (
            <div className="alert alert-warning">
              ⚠️ High projection lag: {metrics.projectionLag}ms. Check Kafka consumer status.
            </div>
          )}
          {metrics.errorRate > 0.05 && (
            <div className="alert alert-error">
              🔴 High error rate: {(metrics.errorRate * 100).toFixed(2)}%. Investigate logs.
            </div>
          )}
          {metrics.dltDepth === 0 &&
            metrics.errorRate < 0.01 &&
            metrics.projectionLag < 1000 && (
              <div className="alert alert-success">
                ✓ All systems operational. No alerts.
              </div>
            )}
        </div>
      </div>
    </div>
  )
}
