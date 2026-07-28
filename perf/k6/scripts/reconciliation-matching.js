import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, getAuthHeaders, recordMetrics, errorRate } from '../utils/helpers.js';

export const options = {
  vus: 30,
  duration: '5m',
  thresholds: {
    latency: ['p(95)<1000', 'p(99)<2000'],
    error_rate: ['rate<0.01'],
  },
};

export default function () {
  // Simulate querying reconciliation status for existing transactions
  const transactionId = `tx-seed-${Math.floor(Math.random() * 10000)}`;

  const response = http.get(
    `${BASE_URL}/transactions/${transactionId}/reconciliation`,
    getAuthHeaders()
  );

  const success = check(response, {
    'reconciliation query status 200-299': (r) => r.status >= 200 && r.status < 300 || r.status === 404,
    'response contains status field': (r) => r.body.includes('status') || r.status === 404,
  });

  if (!success && response.status !== 404) {
    errorRate.add(1);
  }

  recordMetrics(response, 'reconciliation_query');
  sleep(0.5);
}
