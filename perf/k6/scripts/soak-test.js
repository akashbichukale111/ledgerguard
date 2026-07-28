import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, getAuthHeaders, generateTransactionId, generateAmount, recordMetrics, errorRate } from '../utils/helpers.js';

export const options = {
  stages: [
    { duration: '5m', target: 100 }, // Ramp-up
    { duration: '30m', target: 100 }, // Sustained load for 30 minutes
    { duration: '5m', target: 0 }, // Ramp-down
  ],
  thresholds: {
    latency: ['p(95)<500', 'p(99)<1000'],
    error_rate: ['rate<0.01'],
  },
};

export default function () {
  const transactionId = generateTransactionId();
  const amount = generateAmount();

  const payload = JSON.stringify({
    transactionId,
    amount,
    currency: 'USD',
    counterparty: `party-${Math.floor(Math.random() * 1000)}`,
    timestamp: new Date().toISOString(),
  });

  const response = http.post(`${BASE_URL}/transactions/ingest`, payload, getAuthHeaders());

  const success = check(response, {
    'soak test status 200-299': (r) => r.status >= 200 && r.status < 300,
  });

  if (!success) {
    errorRate.add(1);
  }

  recordMetrics(response, 'soak_ingest');
  sleep(0.2);
}
