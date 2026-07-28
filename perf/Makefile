.PHONY: help test-transaction test-reconciliation test-projection test-e2e test-spike test-soak test-all clean results-dir

BASE_URL ?= http://localhost:8080/api/v1
AUTH_TOKEN ?= test-jwt-token
K6_VUS ?= 50
K6_DURATION ?= 5m

help:
	@echo "k6 Performance Test Suite"
	@echo ""
	@echo "Usage: make [target] [BASE_URL=url] [AUTH_TOKEN=token]"
	@echo ""
	@echo "Targets:"
	@echo "  test-transaction      Run transaction ingestion load test (50 VUs, 5 min)"
	@echo "  test-reconciliation   Run reconciliation query test (30 VUs, 5 min)"
	@echo "  test-projection       Run projection lag queries (20 VUs, 10 min)"
	@echo "  test-e2e              Run end-to-end scenario test (10 VUs, 5 min)"
	@echo "  test-spike            Run spike test (6x surge to 300 VUs)"
	@echo "  test-soak             Run soak test (100 VUs for 30 min)"
	@echo "  test-all              Run all test scenarios sequentially"
	@echo "  clean                 Remove test results"
	@echo "  results-dir           Create results directory"
	@echo ""
	@echo "Environment variables:"
	@echo "  BASE_URL              Backend API base URL (default: http://localhost:8080/api/v1)"
	@echo "  AUTH_TOKEN            JWT auth token (default: test-jwt-token)"

results-dir:
	mkdir -p k6/results

test-transaction: results-dir
	@echo "Running transaction ingestion load test..."
	k6 run --vus $(K6_VUS) --duration $(K6_DURATION) \
		--out json=k6/results/transaction-$$(date +%s).json \
		-e BASE_URL=$(BASE_URL) -e AUTH_TOKEN=$(AUTH_TOKEN) \
		k6/scripts/transaction-ingestion.js

test-reconciliation: results-dir
	@echo "Running reconciliation matching test..."
	k6 run --out json=k6/results/reconciliation-$$(date +%s).json \
		-e BASE_URL=$(BASE_URL) -e AUTH_TOKEN=$(AUTH_TOKEN) \
		k6/scripts/reconciliation-matching.js

test-projection: results-dir
	@echo "Running projection lag test..."
	k6 run --out json=k6/results/projection-$$(date +%s).json \
		-e BASE_URL=$(BASE_URL) -e AUTH_TOKEN=$(AUTH_TOKEN) \
		k6/scripts/projection-lag.js

test-e2e: results-dir
	@echo "Running end-to-end flow test..."
	k6 run --out json=k6/results/e2e-$$(date +%s).json \
		-e BASE_URL=$(BASE_URL) -e AUTH_TOKEN=$(AUTH_TOKEN) \
		k6/scripts/end-to-end-flow.js

test-spike: results-dir
	@echo "Running spike test (load surge to 300 VUs)..."
	k6 run --out json=k6/results/spike-$$(date +%s).json \
		-e BASE_URL=$(BASE_URL) -e AUTH_TOKEN=$(AUTH_TOKEN) \
		k6/scripts/spike-test.js

test-soak: results-dir
	@echo "Running soak test (30 minute sustained load)..."
	k6 run --out json=k6/results/soak-$$(date +%s).json \
		-e BASE_URL=$(BASE_URL) -e AUTH_TOKEN=$(AUTH_TOKEN) \
		k6/scripts/soak-test.js

test-all: test-transaction test-reconciliation test-projection test-e2e test-spike test-soak
	@echo "All performance tests completed. Results in k6/results/"

clean:
	rm -rf k6/results/*
	@echo "Cleaned up test results"
