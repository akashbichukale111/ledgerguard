import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, getAuthHeaders, generateTransactionId, generateAmount, recordMetrics, errorRate } from '../utils/helpers.js';

export const options = {
  stages: [
    { duration: '2m', target: 50 }, // Ramp-up to 50 VUs
    { duration: '2m', target: 300 }, // Spike to 300 VUs (6x increase)
    { duration: '3m', target: 300 }, // Hold spike
    { duration: '2m', target: 50 }, // Scale down
    { duration: '1m', target: 0 }, // Ramp-down
  ],
  thresholds: {
    latency: ['p(95)<1000', 'p(99)<2000'],
    error_rate: ['rate<0.05'],
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
    'spike test status 200-299': (r) => r.status >= 200 && r.status < 300,
  });

  if (!success) {
    errorRate.add(1);
  }

  recordMetrics(response, 'spike_ingest');
  sleep(0.1);
}
