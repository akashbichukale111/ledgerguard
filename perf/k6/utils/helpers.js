import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter, Gauge, Rate } from 'k6/metrics';

export const latency = new Trend('latency', { unit: 'ms', isTime: true });
export const throughput = new Counter('throughput');
export const activeVus = new Gauge('active_vus');
export const errorRate = new Rate('error_rate');

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080/api/v1';
export const AUTH_TOKEN = __ENV.AUTH_TOKEN || 'test-jwt-token';

export function getAuthHeaders() {
  return {
    headers: {
      'Authorization': `Bearer ${AUTH_TOKEN}`,
      'Content-Type': 'application/json',
    },
  };
}

export function generateTransactionId() {
  return `tx-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
}

export function generateAmount() {
  return (Math.random() * 10000 + 1).toFixed(2);
}

export function parseJsonResponse(response, name) {
  const success = check(response, {
    [`${name} status is 200-299`]: (r) => r.status >= 200 && r.status < 300,
    [`${name} body is not empty`]: (r) => r.body.length > 0,
  });

  if (!success) {
    errorRate.add(1);
  }

  try {
    return JSON.parse(response.body);
  } catch (e) {
    console.error(`Failed to parse JSON: ${e}`);
    errorRate.add(1);
    return null;
  }
}

export function recordMetrics(response, operationName) {
  latency.add(response.timings.duration, { operation: operationName });
  throughput.add(1);
}
