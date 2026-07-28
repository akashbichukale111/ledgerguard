import client from './client'

/**
 * Types mirror what the query-service controllers actually return. Where a field the console
 * once expected has no source behind it, it is absent here rather than optional — an optional
 * field invites a component to render a blank where there is no measurement at all.
 */

export interface LifecycleStep {
  stage: string
  service: string
  occurredAt: string
  processedAt: string
  status: string
  detail: string
}

export interface Transaction {
  transactionId: string
  /** Projection status. Free-form: it follows the event stream, not a closed enum. */
  status: string
  amount: string
  currency: string
  reference: string
  direction: string
  counterpartyId: string
  occurredAt: string
  updatedAt: string
  correlationId: string | null
  timeline: LifecycleStep[]
}

export interface DltMessage {
  messageId: string
  topic: string
  partition: number
  offset: number
  reason: string
  stackTraceDigest: string | null
  attemptCount: number
  firstFailedAt: string | null
  timestamp: string
  replayedAt: string | null
  originalEnvelope: string
}

export interface AuditEntry {
  chainIndex: number
  eventId: string
  occurredAt: string
  actor: string
  actorRole: string
  action: string
  service: string
  aggregateType: string | null
  aggregateId: string | null
  outcome: string
  correlationId: string
  recordHash: string
}

/**
 * Dashboard figures.
 *
 * Rates are nullable by design: null means nothing has reconciled yet, which is a different
 * answer from 0% and must render differently. Consumer lag is still absent — the service has no
 * Kafka admin client, so it could only be invented. See DashboardMetricsService.
 */
export interface DashboardMetrics {
  projectionLagMillis: number
  dltDepth: number
  transactionCount: number
  transactionsLastHour: number
  auditChainLength: number
  /** Transactions that reached a terminal outcome — the denominator of every rate below. */
  reconciledCount: number
  /** Still in flight. Published so a backlog is distinguishable from failures. */
  pendingCount: number
  matchRate: number | null
  reviewRate: number | null
  errorRate: number | null
  /** How many documents the lag and window figures were computed from. Rates are NOT sampled. */
  sampleSize: number
}

export const transactionApi = {
  search: (query: string, limit: number = 50) =>
    client.get<Transaction[]>('/transactions/search', {
      params: { q: query, limit },
    }),

  getById: (transactionId: string) =>
    client.get<Transaction>(`/transactions/${transactionId}`),

  getLifecycle: (transactionId: string) =>
    client.get<LifecycleStep[]>(`/transactions/${transactionId}/lifecycle`),

  byCorrelation: (correlationId: string) =>
    client.get<Transaction[]>(`/transactions/by-correlation/${correlationId}`),
}

export const dltApi = {
  list: (limit: number = 20, offset: number = 0) =>
    client.get<DltMessage[]>('/replay/dlt-messages', {
      params: { limit, offset },
    }),

  replay: (originalEnvelope: string) =>
    client.post<{ causationId: string; message: string }>('/replay/dlt-message', {
      originalEnvelope,
    }),
}

export const auditApi = {
  search: (correlationId?: string, actor?: string, limit: number = 50) =>
    client.get<AuditEntry[]>('/audit/entries', {
      params: { correlationId, actor, limit },
    }),

  getByCorrelationId: (correlationId: string) =>
    client.get<AuditEntry[]>(`/audit/entries/${correlationId}`),

  verify: () => client.get('/audit/verify'),
}

export const metricsApi = {
  dashboard: () => client.get<DashboardMetrics>('/metrics/dashboard'),

  projectionLag: () => client.get<number>('/metrics/projection-lag'),

  dltDepth: () => client.get<number>('/metrics/dlt-depth'),
}
