#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Seeds the hosted demo with transactions by POSTing them through the real API.
#
# WHY IT WORKS THIS WAY
#
# Nothing here writes to a database. Every transaction is submitted to the same
# endpoint the console uses, so each one travels the full path:
#
#   POST /api/v1/transactions
#     -> transaction-service validates it, writes the aggregate and an outbox
#        row in ONE transaction
#     -> the outbox poller publishes TransactionReceived to Kafka
#     -> reconciliation-service consumes it, runs the matching engine, publishes
#        TransactionReconciled
#     -> query-service projects both into the read model
#     -> the console reads the projection
#
# That means the demo data exercises the system rather than decorating it. If
# any link is broken, seeding surfaces it instead of hiding it behind a
# hardcoded array in the frontend -- which is the whole point.
#
# USAGE
#   ./scripts/seed-demo.sh https://your-gateway.onrender.com [COUNT]
#
#   LG_USER / LG_PASS override the demo credentials
#   (default: operations/operations -- see UserCatalog).
#
# The amounts, currencies and counterparties below are shaped to produce a
# spread of outcomes rather than 40 identical rows. See the note on match rate
# at the bottom -- it will be 0% and that is not a bug.
# ---------------------------------------------------------------------------
set -euo pipefail

BASE_URL="${1:-}"
COUNT="${2:-40}"
# Matches UserCatalog. Seeding needs a role that can both POST a transaction and
# read the dashboard; `operations` is the least-privileged role that can do both.
LG_USER="${LG_USER:-operations}"
LG_PASS="${LG_PASS:-operations}"

if [[ -z "$BASE_URL" ]]; then
    echo "usage: $0 <base-url> [count]" >&2
    echo "  e.g. $0 https://ledgerguard-gateway.onrender.com 40" >&2
    exit 2
fi
BASE_URL="${BASE_URL%/}"

command -v curl >/dev/null || { echo "curl is required" >&2; exit 1; }
command -v python3 >/dev/null || { echo "python3 is required (for UUIDs and JSON)" >&2; exit 1; }

# --- 1. Wake the stack ------------------------------------------------------
#
# Render free instances sleep after 15 minutes idle and take 30-60s to wake.
# Seeding into a sleeping stack produces a wall of timeouts that look like
# failures, so wait for a real answer first.
echo "==> Waking services (free instances sleep; this can take a minute)"
for attempt in $(seq 1 30); do
    if curl -fsS --max-time 20 "$BASE_URL/actuator/health" >/dev/null 2>&1; then
        echo "    gateway is up"
        break
    fi
    if [[ $attempt -eq 30 ]]; then
        echo "    gateway did not answer after 30 attempts -- check Render logs" >&2
        exit 1
    fi
    printf '    still waking (attempt %d/30)\r' "$attempt"
    sleep 10
done

# --- 2. Log in through the real endpoint ------------------------------------
#
# The credential is obtained the same way the console obtains it, rather than
# being assembled here. If auth is broken, seeding stops at the same place a
# user would.
echo "==> Logging in as $LG_USER"
LOGIN_BODY=$(python3 -c '
import json, sys
print(json.dumps({"username": sys.argv[1], "password": sys.argv[2]}))' "$LG_USER" "$LG_PASS")

LOGIN_RESPONSE=$(curl -fsS --max-time 60 -X POST "$BASE_URL/api/v1/auth/login" \
    -H 'Content-Type: application/json' \
    -d "$LOGIN_BODY") || { echo "login failed -- is auth-server deployed and reachable?" >&2; exit 1; }

CREDENTIAL=$(printf '%s' "$LOGIN_RESPONSE" | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')
SCHEME=$(printf '%s' "$LOGIN_RESPONSE" | python3 -c 'import json,sys; print(json.load(sys.stdin)["scheme"])')
echo "    authenticated"

# --- 3. Submit -------------------------------------------------------------
echo "==> Submitting $COUNT transactions through POST /api/v1/transactions"

CURRENCIES=(USD USD USD EUR EUR GBP JPY)
COUNTERPARTIES=(
    "ACME-TRADING" "NORTHWIND-LLC" "GLOBEX-CAPITAL" "INITECH-BANK"
    "UMBRELLA-FIN" "STARK-TREASURY" "WAYNE-CLEARING"
)
REFS=(INV SETTL FX PAYROLL REFUND DIV)

submitted=0
failed=0

for i in $(seq 1 "$COUNT"); do
    # A distinct idempotency key per submission. Reusing one would exercise the
    # replay path and create a single transaction, which is a different demo.
    IDEMPOTENCY_KEY=$(python3 -c 'import uuid; print(uuid.uuid4())')

    currency=${CURRENCIES[$(( RANDOM % ${#CURRENCIES[@]} ))]}
    counterparty=${COUNTERPARTIES[$(( RANDOM % ${#COUNTERPARTIES[@]} ))]}
    ref_prefix=${REFS[$(( RANDOM % ${#REFS[@]} ))]}

    # Amount as a decimal STRING with two places, never a JSON number (ADR-0009):
    # a float would lose exactness before the service ever parsed it.
    amount=$(python3 -c '
import random
whole = random.choice([random.randint(50, 999), random.randint(1000, 25000)])
print(f"{whole}.{random.randint(0, 99):02d}")')

    # Spread value dates over the past three weeks so the dashboard's time
    # window and the engine's date tolerance both have something to work with.
    value_date=$(python3 -c '
import datetime, random
print((datetime.date.today() - datetime.timedelta(days=random.randint(0, 20))).isoformat())')

    reference="${ref_prefix}-$(python3 -c 'import random; print(random.randint(100000, 999999))')"

    # Every field SubmitTransactionRequest marks @NotBlank/@NotNull is present. A
    # partial body is rejected with 400 before it reaches the domain, which would
    # make this script look like an infrastructure failure when it is a contract
    # mismatch.
    BODY=$(python3 -c '
import json, random, sys
amount, currency, reference, counterparty, value_date = sys.argv[1:6]
direction = random.choice(["DEBIT", "CREDIT"])
# A debit and a credit account, distinct, so the balanced pair the write path
# creates has two real sides.
book = f"ACCT-{random.randint(1000, 9999)}"
print(json.dumps({
    "reference": reference,
    "amount": amount,
    "currency": currency,
    "direction": direction,
    "counterpartyId": counterparty,
    "debitAccount": book if direction == "DEBIT" else f"{counterparty}-NOSTRO",
    "creditAccount": f"{counterparty}-NOSTRO" if direction == "DEBIT" else book,
    "valueDate": value_date,
    "settlementSystem": random.choice(["SWIFT", "SEPA", "FEDWIRE", "CHAPS"]),
}))' "$amount" "$currency" "$reference" "$counterparty" "$value_date")

    if curl -fsS --max-time 60 -X POST "$BASE_URL/api/v1/transactions" \
        -H 'Content-Type: application/json' \
        -H "Authorization: $SCHEME $CREDENTIAL" \
        -H "Idempotency-Key: $IDEMPOTENCY_KEY" \
        -d "$BODY" >/dev/null 2>&1; then
        submitted=$(( submitted + 1 ))
    else
        failed=$(( failed + 1 ))
        echo "    submission $i failed (continuing)" >&2
    fi

    printf '    %d/%d submitted, %d failed\r' "$submitted" "$COUNT" "$failed"

    # Paced deliberately. The free-tier broker and a 3-connection pool are the
    # constraint; firing 40 concurrent writes produces pool timeouts that look
    # like application bugs.
    sleep 0.4
done

echo
echo "==> Submitted $submitted, failed $failed"

# --- 4. Let the pipeline drain ---------------------------------------------
echo "==> Waiting for the events to flow through reconciliation and projection"
sleep 20

DASHBOARD=$(curl -fsS --max-time 60 "$BASE_URL/api/v1/metrics/dashboard" \
    -H "Authorization: $SCHEME $CREDENTIAL" 2>/dev/null || echo '{}')

echo
echo "Dashboard now reports:"
printf '%s' "$DASHBOARD" | python3 -c '
import json, sys
try:
    d = json.load(sys.stdin)
except Exception:
    print("  (could not read the dashboard -- check query-service logs)"); raise SystemExit

if not d:
    print("  (empty response)"); raise SystemExit

if not d.get("readModelAvailable", True):
    print("  READ MODEL NOT AVAILABLE -- MONGO_URI is unset or Mongo is unreachable.")
    print("  The transactions above were still accepted and published; they just")
    print("  cannot be displayed. See docs/deployment.md.")
    raise SystemExit

print(f"  transactions in read model : {d.get(\"transactionCount\")}")
print(f"  reconciled                 : {d.get(\"reconciledCount\")}")
print(f"  pending                    : {d.get(\"pendingCount\")}")
print(f"  audit chain length         : {d.get(\"auditChainLength\")}")
rate = d.get("matchRate")
print(f"  match rate                 : {\"-- (nothing reconciled yet)\" if rate is None else f\"{rate:.0%}\"}")
'

cat <<'NOTE'

A 0% match rate here is CORRECT, not a failure.

ReconcileTransactionHandler.externalCandidatesFor returns an empty list because
no counterparty statement feed exists in this repository. With nothing to match
against, every transaction reconciles as UNMATCHED. The engine, the contract,
the projection, the metrics and the console are all exercised end to end
regardless -- the candidate source is the one seam a real feed plugs into.

Seeding fake counterparty rows to make the number look better is exactly the
kind of thing the phase-16 audit was written to stop.
NOTE
