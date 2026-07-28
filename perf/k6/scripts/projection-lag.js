import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, getAuthHeaders, recordMetrics, errorRate } from '../utils/helpers.js';

export const options = {
  vus: 20,
  duration: '10m',
  thresholds: {
    latency: ['p(95)<200', 'p(99)<500'],
    error_rate: ['rate<0.01'],
  },
};

export default function () {
  const response = http.get(`${BASE_URL}/metrics/projection-lag`, getAuthHeaders());

  const success = check(response, {
    'projection lag endpoint status 200': (r) => r.status === 200,
    'response contains lag value': (r) => r.body.includes('lag') || r.body.includes('value'),
  });

  if (!success) {
    errorRate.add(1);
  }

  recordMetrics(response, 'projection_lag_query');

  // Parse lag value and check it's within acceptable bounds (< 5 seconds under normal load)
  try {
    const data = JSON.parse(response.body);
    check(data, {
      'projection lag under 5 seconds': () => data.lag < 5000,
    });
  } catch (e) {
    console.error(`Failed to parse lag response: ${e}`);
  }

  sleep(1);
}
