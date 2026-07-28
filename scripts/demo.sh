#!/bin/bash
# LedgerGuard End-to-End Demo Script
# Demonstrates transaction ingestion, reconciliation, and audit trail

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# Configuration
API_BASE_URL="${API_BASE_URL:-http://localhost:8080/api/v1}"
AUTH_TOKEN="${AUTH_TOKEN:-test-jwt-token}"
DEMO_SPEED="${DEMO_SPEED:-1}"  # Seconds between requests

# Helper functions
log_section() {
  echo -e "${BLUE}==== $1 ====${NC}"
}

log_success() {
  echo -e "${GREEN}✓ $1${NC}"
}

log_error() {
  echo -e "${RED}✗ $1${NC}"
}

log_info() {
  echo -e "${YELLOW}ℹ $1${NC}"
}

pause() {
  sleep "$DEMO_SPEED"
}

call_api() {
  local method=$1
  local endpoint=$2
  local data=$3

  if [ -z "$data" ]; then
    curl -s -X "$method" \
      -H "Authorization: Bearer $AUTH_TOKEN" \
      -H "Content-Type: application/json" \
      "$API_BASE_URL$endpoint"
  else
    curl -s -X "$method" \
      -H "Authorization: Bearer $AUTH_TOKEN" \
      -H "Content-Type: application/json" \
      -d "$data" \
      "$API_BASE_URL$endpoint"
  fi
}

# Check prerequisites
check_prerequisites() {
  log_section "Checking Prerequisites"

  if ! command -v curl &> /dev/null; then
    log_error "curl not found"
    exit 1
  fi
  log_success "curl installed"

  if ! command -v jq &> /dev/null; then
    log_error "jq not found (install with: brew install jq)"
    exit 1
  fi
  log_success "jq installed"

  # Check API connectivity
  if ! curl -s -f "$API_BASE_URL/metrics/dashboard" \
    -H "Authorization: Bearer $AUTH_TOKEN" > /dev/null 2>&1; then
    log_error "Cannot reach API at $API_BASE_URL"
    log_info "Make sure services are running (docker-compose up -d)"
    exit 1
  fi
  log_success "API is reachable at $API_BASE_URL"
}

# Demo: Transaction Ingestion
demo_ingestion() {
  log_section "Demo 1: Transaction Ingestion"

  local tx_id="demo-tx-$(date +%s)"
  local amount="1234.56"

  log_info "Ingesting transaction: $tx_id"

  local payload=$(cat <<EOF
{
  "transactionId": "$tx_id",
  "amount": "$amount",
  "currency": "USD",
  "counterparty": "Bank of America",
  "timestamp": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
}
EOF
)

  local response=$(call_api POST "/transactions/ingest" "$payload")

  echo "$response" | jq '.' 2>/dev/null || echo "$response"

  if echo "$response" | grep -q "$tx_id"; then
    log_success "Transaction ingested successfully"
    INGESTED_TX_ID="$tx_id"
  else
    log_error "Transaction ingestion failed"
    log_info "Response: $response"
  fi

  pause
}

# Demo: Transaction Search
demo_search() {
  log_section "Demo 2: Transaction Search"

  if [ -z "$INGESTED_TX_ID" ]; then
    log_error "No transaction to search (run ingestion demo first)"
    return
  fi

  log_info "Searching for transaction: $INGESTED_TX_ID"

  local response=$(call_api GET "/transactions/search?query=$INGESTED_TX_ID&limit=10")

  echo "$response" | jq '.' 2>/dev/null || echo "$response"

  if echo "$response" | grep -q "$INGESTED_TX_ID"; then
    log_success "Transaction found in search results"
  else
    log_error "Transaction not found in search results"
  fi

  pause
}

# Demo: Transaction Details (360 View)
demo_transaction_360() {
  log_section "Demo 3: Transaction 360-Degree View"

  if [ -z "$INGESTED_TX_ID" ]; then
    log_error "No transaction to view (run ingestion demo first)"
    return
  fi

  log_info "Fetching transaction details: $INGESTED_TX_ID"

  local response=$(call_api GET "/transactions/$INGESTED_TX_ID")

  echo "$response" | jq '.' 2>/dev/null || echo "$response"

  if echo "$response" | grep -q "PENDING\|MATCHED"; then
    log_success "Transaction 360 view retrieved"
  else
    log_info "Note: Transaction status may not be available immediately"
  fi

  pause
}

# Demo: Dashboard Metrics
demo_metrics() {
  log_section "Demo 4: Dashboard Metrics"

  log_info "Fetching real-time metrics"

  local response=$(call_api GET "/metrics/dashboard")

  echo "$response" | jq '{
    projectionLag: .projectionLag,
    dltDepth: .dltDepth,
    transactionRate: .transactionRate,
    matchRate: .matchRate,
    errorRate: .errorRate
  }' 2>/dev/null || echo "$response"

  log_success "Metrics retrieved"

  pause
}

# Demo: Audit Trail
demo_audit_trail() {
  log_section "Demo 5: Audit Trail"

  log_info "Fetching recent audit entries"

  local response=$(call_api GET "/audit/entries?limit=5")

  echo "$response" | jq 'sort_by(.timestamp) | reverse | .[0:3] | .[] | {
    timestamp,
    actor,
    action,
    aggregateId
  }' 2>/dev/null || echo "$response"

  log_success "Audit trail retrieved"

  pause
}

# Demo: DLT Explorer (browse dead-letter messages)
demo_dlt_explorer() {
  log_section "Demo 6: Dead-Letter Topic (DLT) Explorer"

  log_info "Checking dead-lettered messages"

  local response=$(call_api GET "/replay/dlt-messages?limit=5")

  if echo "$response" | grep -q "\\[\\]"; then
    log_info "No messages in DLT (this is good!)"
  else
    echo "$response" | jq '.[0:3] | .[] | {
      messageId,
      topic,
      reason,
      timestamp
    }' 2>/dev/null || echo "$response"
    log_success "DLT messages retrieved"
  fi

  pause
}

# Demo: Pagination
demo_pagination() {
  log_section "Demo 7: Pagination and Filtering"

  log_info "Fetching transactions (page 1, limit 3)"

  local response=$(call_api GET "/transactions/search?limit=3&offset=0")

  echo "$response" | jq '.[] | {
    transactionId,
    status,
    amount
  }' 2>/dev/null | head -20 || echo "$response"

  log_success "Pagination works"

  pause
}

# Demo: Reconciliation Status
demo_reconciliation() {
  log_section "Demo 8: Reconciliation Status"

  if [ -z "$INGESTED_TX_ID" ]; then
    log_error "No transaction to reconcile (run ingestion demo first)"
    return
  fi

  log_info "Checking reconciliation status for: $INGESTED_TX_ID"

  local response=$(call_api GET "/transactions/$INGESTED_TX_ID/reconciliation")

  echo "$response" | jq '.' 2>/dev/null || echo "$response"

  log_info "Note: Reconciliation may be in progress or pending"

  pause
}

# Summary
show_summary() {
  log_section "Demo Complete!"

  cat <<EOF
${GREEN}You've successfully demonstrated:${NC}

  1. ${YELLOW}Transaction Ingestion${NC}
     - REST API for accepting transactions
     - Automatic deduplication
     - Outbox pattern for reliability

  2. ${YELLOW}Transaction Search${NC}
     - Query service projections
     - Full-text search support
     - Pagination and filtering

  3. ${YELLOW}360-Degree Transaction View${NC}
     - Complete transaction lifecycle
     - Related events and timeline
     - Status tracking

  4. ${YELLOW}Real-Time Metrics${NC}
     - Projection lag monitoring
     - DLT depth tracking
     - Transaction rate monitoring
     - Match rate percentage

  5. ${YELLOW}Audit Trail${NC}
     - Immutable event log
     - Actor and action tracking
     - Timestamp verification
     - Correlation IDs for tracing

  6. ${YELLOW}Dead-Letter Topic (DLT)${NC}
     - Browse failed messages
     - Error reason tracking
     - Message replay capability

  7. ${YELLOW}Advanced Features${NC}
     - Pagination and filtering
     - Sorting and search
     - Rate limiting and throttling

  8. ${YELLOW}Reconciliation Engine${NC}
     - Automatic transaction matching
     - Saga pattern for reliability
     - Error handling and retry logic

${BLUE}Next Steps:${NC}
  - Open http://localhost:3000 to see the operations console
  - Try ingesting multiple transactions
  - Monitor reconciliation status
  - Check Grafana at http://localhost:3001 for dashboards
  - Review logs: docker-compose logs -f query-service

${BLUE}Documentation:${NC}
  - README.md - Quick start and architecture
  - CONTRIBUTING.md - Development guidelines
  - DEPLOYMENT.md - Production deployment
  - docs/adr/ - Architecture decisions

EOF
}

# Main
main() {
  clear

  echo -e "${BLUE}"
  cat <<'EOF'
╔════════════════════════════════════════════════════════════╗
║                                                            ║
║              🏦 LedgerGuard End-to-End Demo 🏦             ║
║                                                            ║
║       Real-Time Financial Reconciliation Platform         ║
║                                                            ║
╚════════════════════════════════════════════════════════════╝
EOF
  echo -e "${NC}\n"

  check_prerequisites

  demo_ingestion
  demo_search
  demo_transaction_360
  demo_metrics
  demo_audit_trail
  demo_dlt_explorer
  demo_pagination
  demo_reconciliation

  show_summary
}

# Run main
main
