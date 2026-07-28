import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, getAuthHeaders, generateTransactionId, generateAmount, recordMetrics, errorRate } from '../utils/helpers.js';

export const options = {
  vus: 50,
  duration: '5m',
  thresholds: {
    latency: ['p(95)<500', 'p(99)<1000'],
    throughput: [],
    error_rate: ['rate<0.01'],
  },
};

export default function () {
  const transactionId = generateTransactionId();
  const amount = generateAmount();
  const currency = 'USD';
  const counterparty = `party-${Math.floor(Math.random() * 1000)}`;

  const payload = JSON.stringify({
    transactionId,
    amount,
    currency,
    counterparty,
    timestamp: new Date().toISOString(),
  });

  const response = http.post(`${BASE_URL}/transactions/ingest`, payload, getAuthHeaders());

  const success = check(response, {
    'ingestion status 200-299': (r) => r.status >= 200 && r.status < 300,
    'transaction ID in response': (r) => r.body.includes(transactionId),
  });

  if (!success) {
    errorRate.add(1);
  }

  recordMetrics(response, 'transaction_ingest');
  sleep(0.1);
}
