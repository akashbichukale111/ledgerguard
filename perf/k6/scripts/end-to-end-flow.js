import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, getAuthHeaders, generateTransactionId, generateAmount, recordMetrics, errorRate } from '../utils/helpers.js';

export const options = {
  vus: 10,
  duration: '5m',
  thresholds: {
    latency: ['p(95)<2000', 'p(99)<5000'],
    error_rate: ['rate<0.01'],
  },
};

export default function () {
  const transactionId = generateTransactionId();
  const amount = generateAmount();
  const currency = 'USD';

  // Step 1: Ingest transaction
  const ingestPayload = JSON.stringify({
    transactionId,
    amount,
    currency,
    counterparty: `party-${Math.floor(Math.random() * 1000)}`,
    timestamp: new Date().toISOString(),
  });

  const ingestResponse = http.post(`${BASE_URL}/transactions/ingest`, ingestPayload, getAuthHeaders());

  const ingestSuccess = check(ingestResponse, {
    'E2E: ingest status 200-299': (r) => r.status >= 200 && r.status < 300,
  });

  if (!ingestSuccess) {
    errorRate.add(1);
    return;
  }

  recordMetrics(ingestResponse, 'e2e_ingest');
  sleep(0.5);

  // Step 2: Query transaction details
  const getResponse = http.get(
    `${BASE_URL}/transactions/${transactionId}`,
    getAuthHeaders()
  );

  const getSuccess = check(getResponse, {
    'E2E: query status 200': (r) => r.status === 200,
    'E2E: response contains amount': (r) => r.body.includes(amount),
  });

  if (!getSuccess && getResponse.status !== 404) {
    errorRate.add(1);
  }

  recordMetrics(getResponse, 'e2e_query');
  sleep(1);

  // Step 3: Check reconciliation status
  const reconcileResponse = http.get(
    `${BASE_URL}/transactions/${transactionId}/reconciliation`,
    getAuthHeaders()
  );

  const reconcileSuccess = check(reconcileResponse, {
    'E2E: reconcile status 200-299 or 404': (r) => r.status >= 200 && r.status < 300 || r.status === 404,
  });

  if (!reconcileSuccess) {
    errorRate.add(1);
  }

  recordMetrics(reconcileResponse, 'e2e_reconcile');
  sleep(2);
}
