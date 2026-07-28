import { useEffect, useState } from 'react'
import { dltApi, DltMessage } from '../api/queries'
import '../styles/dlt.css'

export default function DltExplorer() {
  const [messages, setMessages] = useState<DltMessage[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [selectedMessage, setSelectedMessage] = useState<DltMessage | null>(null)

  useEffect(() => {
    const fetchDlt = async () => {
      try {
        const response = await dltApi.list(50)
        setMessages(response.data)
      } catch (err) {
        setError('Failed to load DLT messages')
        console.error(err)
      } finally {
        setLoading(false)
      }
    }

    fetchDlt()
  }, [])

  const handleReplay = async (message: DltMessage) => {
    try {
      await dltApi.replay(message.originalEnvelope)
      alert('Message replayed successfully')
      window.location.reload()
    } catch (err) {
      alert('Failed to replay message')
      console.error(err)
    }
  }

  if (loading) return <div className="loading">Loading DLT messages...</div>
  if (error) return <div className="error">{error}</div>

  return (
    <div className="dlt-explorer">
      <h1>Dead-Letter Topic Explorer</h1>

      <div className="dlt-content">
        <div className="message-list">
          <h2>Messages ({messages.length})</h2>
          {messages.length === 0 ? (
            <div className="empty-state">No messages in DLT</div>
          ) : (
            <table className="message-table">
              <thead>
                <tr>
                  <th>Topic</th>
                  <th>Partition</th>
                  <th>Offset</th>
                  <th>Reason</th>
                  <th>Timestamp</th>
                  <th>Action</th>
                </tr>
              </thead>
              <tbody>
                {messages.map((msg) => (
                  <tr key={`${msg.partition}-${msg.offset}`}>
                    <td>{msg.topic}</td>
                    <td>{msg.partition}</td>
                    <td>{msg.offset}</td>
                    <td title={msg.reason}>{msg.reason.substring(0, 30)}...</td>
                    <td>{new Date(msg.timestamp).toLocaleString()}</td>
                    <td>
                      <button
                        className="replay-btn"
                        onClick={() => {
                          setSelectedMessage(msg)
                          handleReplay(msg)
                        }}
                      >
                        Replay
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>

        {selectedMessage && (
          <div className="message-details">
            <h2>Message Details</h2>
            <div className="detail-group">
              <label>Topic:</label>
              <span>{selectedMessage.topic}</span>
            </div>
            <div className="detail-group">
              <label>Partition:</label>
              <span>{selectedMessage.partition}</span>
            </div>
            <div className="detail-group">
              <label>Offset:</label>
              <span>{selectedMessage.offset}</span>
            </div>
            <div className="detail-group">
              <label>Reason:</label>
              <span>{selectedMessage.reason}</span>
            </div>
            <div className="detail-group">
              <label>Stack Trace Digest:</label>
              <span className="monospace">{selectedMessage.stackTraceDigest}</span>
            </div>
            <div className="detail-group">
              <label>Envelope Preview:</label>
              <pre className="envelope-preview">
                {selectedMessage.originalEnvelope.substring(0, 200)}...
              </pre>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
