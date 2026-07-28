import { useState } from 'react'
import { transactionApi, Transaction } from '../api/queries'
import '../styles/search.css'

export default function TransactionSearch() {
  const [query, setQuery] = useState('')
  const [results, setResults] = useState<Transaction[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [selectedTx, setSelectedTx] = useState<Transaction | null>(null)

  const handleSearch = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!query.trim()) return

    setLoading(true)
    setError(null)

    try {
      const response = await transactionApi.search(query)
      setResults(response.data)
    } catch (err) {
      setError('Search failed')
      console.error(err)
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="transaction-search">
      <h1>Transaction Search</h1>

      <form onSubmit={handleSearch} className="search-form">
        <input
          type="text"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Search by transaction ID, amount, or date..."
          className="search-input"
        />
        <button type="submit" disabled={loading} className="search-btn">
          {loading ? 'Searching...' : 'Search'}
        </button>
      </form>

      {error && <div className="error">{error}</div>}

      <div className="search-results">
        <div className="results-list">
          <h2>Results ({results.length})</h2>
          {results.length === 0 ? (
            <div className="empty-state">No transactions found</div>
          ) : (
            <table className="results-table">
              <thead>
                <tr>
                  <th>Transaction ID</th>
                  <th>Status</th>
                  <th>Amount</th>
                  <th>Timestamp</th>
                  <th>Trace ID</th>
                </tr>
              </thead>
              <tbody>
                {results.map((tx) => (
                  <tr
                    key={tx.transactionId}
                    onClick={() => setSelectedTx(tx)}
                    className={selectedTx?.transactionId === tx.transactionId ? 'selected' : ''}
                  >
                    <td>{tx.transactionId}</td>
                    <td>
                      <span className={`status status-${tx.status.toLowerCase()}`}>
                        {tx.status}
                      </span>
                    </td>
                    <td>
                      {tx.amount} {tx.currency}
                    </td>
                    <td>{new Date(tx.occurredAt).toLocaleString()}</td>
                    <td className="trace-id">{tx.reference}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>

        {selectedTx && (
          <div className="details-panel">
            <h2>Transaction Details</h2>
            <div className="detail-group">
              <label>Transaction ID:</label>
              <span>{selectedTx.transactionId}</span>
            </div>
            <div className="detail-group">
              <label>Status:</label>
              <span className={`status status-${selectedTx.status.toLowerCase()}`}>
                {selectedTx.status}
              </span>
            </div>
            <div className="detail-group">
              <label>Amount:</label>
              <span>
                {selectedTx.amount} {selectedTx.currency}
              </span>
            </div>
            <div className="detail-group">
              <label>Timestamp:</label>
              <span>{new Date(selectedTx.occurredAt).toLocaleString()}</span>
            </div>
            <div className="detail-group">
              <label>Correlation ID:</label>
              <span className="monospace">{selectedTx.correlationId}</span>
            </div>
            <div className="detail-group">
              <label>Reference:</label>
              <span className="monospace">{selectedTx.reference}</span>
            </div>
            <div className="detail-group">
              <label>Lifecycle:</label>
              <span>
                {selectedTx.timeline?.length
                  ? selectedTx.timeline.map((step) => step.stage).join(' → ')
                  : 'no stages recorded'}
              </span>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
