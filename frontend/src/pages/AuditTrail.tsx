import { useState } from 'react'
import { auditApi, AuditEntry } from '../api/queries'
import '../styles/audit.css'

export default function AuditTrail() {
  const [correlationId, setCorrelationId] = useState('')
  const [entries, setEntries] = useState<AuditEntry[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const handleSearch = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!correlationId.trim()) return

    setLoading(true)
    setError(null)

    try {
      const response = await auditApi.getByCorrelationId(correlationId)
      setEntries(response.data)
    } catch (err) {
      setError('Failed to load audit trail')
      console.error(err)
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="audit-trail">
      <h1>Audit Trail</h1>

      <form onSubmit={handleSearch} className="search-form">
        <input
          type="text"
          value={correlationId}
          onChange={(e) => setCorrelationId(e.target.value)}
          placeholder="Enter Correlation ID..."
          className="search-input"
        />
        <button type="submit" disabled={loading} className="search-btn">
          {loading ? 'Searching...' : 'Search'}
        </button>
      </form>

      {error && <div className="error">{error}</div>}

      <div className="audit-entries">
        <h2>Audit Events ({entries.length})</h2>
        {entries.length === 0 ? (
          <div className="empty-state">No audit entries found</div>
        ) : (
          <div className="timeline">
            {entries.map((entry) => (
              <div key={entry.chainIndex} className="timeline-item">
                <div className="timeline-marker"></div>
                <div className="timeline-content">
                  <div className="entry-header">
                    <span className="action-badge">{entry.action}</span>
                    <span className="timestamp">
                      {new Date(entry.occurredAt).toLocaleString()}
                    </span>
                  </div>
                  <div className="entry-body">
                    <div className="entry-meta">
                      <span className="meta-item">
                        <strong>Actor:</strong> {entry.actor}
                      </span>
                      <span className="meta-item">
                        <strong>Service:</strong> {entry.service}
                      </span>
                      <span className="meta-item">
                        <strong>Outcome:</strong> {entry.outcome}
                      </span>
                      <span className="meta-item">
                        <strong>Aggregate:</strong> {entry.aggregateId}
                      </span>
                    </div>
                    <div className="entry-details">
                      {/* The chain index and record hash are what make tampering detectable;
                          showing them is the point of an audit view. */}
                      <span className="monospace">#{entry.chainIndex}</span>{' '}
                      <span className="monospace">{entry.recordHash?.slice(0, 16)}…</span>
                    </div>
                  </div>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}
