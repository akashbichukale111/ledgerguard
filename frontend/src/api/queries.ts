import client from './client'

export interface Transaction {
  transactionId: string
  status: 'PENDING' | 'MATCHED' | 'FAILED'
  amount: string
  currency: string
  timestamp: string
  correlationId: string
  traceId: string
}

export interface DltMessage {
  messageId: string
  topic: string
  partition: number
  offset: number
  reason: string
  stackTraceDigest: string
  timestamp: string
  originalEnvelope: string
}

export interface AuditEntry {
  id: string
  timestamp: string
  actor: string
  action: string
  aggregateId: string
  eventType: string
  details: string
  correlationId: string
}

export interface DashboardMetrics {
  projectionLag: number
  dltDepth: number
  consumerLag: number
  transactionRate: number
  matchRate: number
  errorRate: number
}

// Transaction queries
export const transactionApi = {
  search: (query: string, limit: number = 50) =>
    client.get<Transaction[]>('/transactions/search', {
      params: { q: query, limit },
    }),

  getById: (transactionId: string) =>
    client.get<Transaction>(`/transactions/${transactionId}`),

  getLifecycle: (transactionId: string) =>
    client.get(`/transactions/${transactionId}/lifecycle`),
}

// DLT queries
export const dltApi = {
  list: (limit: number = 20, offset: number = 0) =>
    client.get<DltMessage[]>('/replay/dlt-messages', {
      params: { limit, offset },
    }),

  replay: (originalEnvelope: string) =>
    client.post('/replay/dlt-message', { originalEnvelope }),
}

// Audit trail
export const auditApi = {
  search: (correlationId?: string, actor?: string, limit: number = 50) =>
    client.get<AuditEntry[]>('/audit/entries', {
      params: { correlationId, actor, limit },
    }),

  getByCorrelationId: (correlationId: string) =>
    client.get<AuditEntry[]>(`/audit/entries/${correlationId}`),
}

// Metrics
export const metricsApi = {
  dashboard: () => client.get<DashboardMetrics>('/metrics/dashboard'),

  projectionLag: () => client.get<number>('/metrics/projection-lag'),

  dltDepth: () => client.get<number>('/metrics/dlt-depth'),
}
