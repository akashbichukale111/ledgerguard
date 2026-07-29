# Hosted demo deployment

Free-tier deployment of LedgerGuard: Vercel (console), Render (five services),
a managed Postgres and Kafka, and MongoDB Atlas (read model).

**Nothing in this document has been executed.** No account was created, no
service deployed, no URL obtained. The configuration was written against each
platform's documented contract and the application builds and passes its tests
against it, but the deployment itself is unverified. Where I am unsure, this
document says so rather than reading as though it has been done.

---

## 0. Read this before you start

### On Aiven and Kafka

The plan for this deployment named Aiven's free tier for **both** Postgres and
Kafka. I could not confirm that Aiven's free plan includes Kafka — my search
tool hit its session limit and Aiven's pricing pages return 403 to my fetcher.
My understanding is that **Aiven's free plans cover PostgreSQL, MySQL and
Valkey, and that Kafka is trial-credit only**, but I could not verify it, so
treat that as a caution rather than a fact.

It does not block anything. Every Kafka setting is an environment variable and
the config is plain `SASL_SSL` + `SCRAM-SHA-256`, which is what Aiven, Confluent
Cloud, Redpanda Cloud and Upstash all speak. **Check the Aiven console first**;
if Kafka is not on the free plan there, use another provider and change only the
three environment variables. Nothing in the code cares which one you picked.

If you use a provider whose brokers present a **public** CA (Confluent Cloud,
Upstash, Redpanda Cloud), leave `KAFKA_CA_PEM` unset entirely — the system trust
store handles it. `KAFKA_CA_PEM` exists specifically for Aiven, which signs
brokers with a per-project CA.

### On MongoDB

The read model lives in MongoDB. Skipping it was on the table, but skipping it
means the console has nothing to show — the dashboard, search, the 360 view and
the transaction list are all projections. So the demo uses **MongoDB Atlas M0**,
which is free forever with no card.

If you would rather not create a fourth account, leave `MONGO_URI` unset. The
service still starts, the write path still works, and every projection-backed
endpoint reports itself unavailable with an explicit banner in the console
instead of erroring. That behaviour is covered by `ReadModelAvailabilityTest`.

### What is dropped

| Component | Status | Consequence |
|---|---|---|
| Redis | dropped | Only ever used for rate limiting, which was never implemented. No loss. |
| Zipkin | dropped | No distributed trace UI. Trace IDs are still in the logs. |
| Prometheus / Grafana | dropped | `/actuator/prometheus` is still exposed; nothing scrapes it. |
| MongoDB | Atlas M0, or off | Off = console shows explicit empty states. |

---

## 1. Aiven — PostgreSQL

1. Go to <https://console.aiven.io> → **Sign up**. GitHub sign-in is fastest. No
   card is requested for free plans.
2. **Create service** → **PostgreSQL**.
3. Pick the cloud and region **closest to Render's Oregon region** (`google-us-west1`
   or `aws-us-west-2`). Cross-continent round trips are the single biggest
   avoidable latency in this setup.
4. Under **Service plan**, choose the plan labelled **Free** (`free-1-5gb`). If
   you do not see one, you are on a trial rather than the free tier — check the
   billing page.
5. Name it `ledgerguard-pg` → **Create service**.
6. Wait for status **Running** (2–5 minutes).
7. On the service **Overview**, copy from *Connection information*:
   - Host, Port, User (`avnadmin`), Password, Database (`defaultdb`)

Build the JDBC URL — **the `sslmode=require` is not optional** and is the most
common thing to leave off:

```
jdbc:postgresql://HOST:PORT/defaultdb?sslmode=require
```

> **One database, three services.** The free plan gives a single database, so
> all three backend services share it. They are already configured with separate
> Flyway history tables (`flyway_schema_history_txn`, `_audit`, `_recon`) so
> their migrations are not read as each other's drift. Locally they each have
> their own database; this is a real difference from the documented architecture
> and is called out in the README.

## 2. Aiven — Kafka

**Check first**: does the Kafka service offer a plan labelled *Free*? If not,
skip to §2b.

1. **Create service** → **Apache Kafka**.
2. Same cloud and region as Postgres.
3. Select the free plan → name it `ledgerguard-kafka` → **Create service**.
4. Wait for **Running** (can take 5–10 minutes).
5. **Overview** → set **Authentication method** to **SASL** (Aiven defaults to
   client certificates; the config here uses SASL). You may need to enable
   `kafka_authentication_methods.sasl` under **Advanced configuration**.
6. Copy the **SASL** URI (host:port — note this is a *different port* from the
   certificate-auth one) and download the **CA certificate** (`ca.pem`).
7. **Topics** tab → create these, each with **1 partition** and **replication 2**
   (free-tier minimum):

   ```
   transactions.events.v1
   reconciliation.events.v1
   transactions.events.v1.retry.1
   transactions.events.v1.retry.2
   transactions.events.v1.retry.3
   transactions.events.v1.dlt
   ```

   Create them explicitly — `KAFKA_AUTO_CREATE_TOPICS_ENABLE` is off, and
   `missing-topics-fatal: false` in the cloud profile means a missing topic
   fails quietly at runtime rather than at boot.

8. **Users** tab → note the username and password for SASL.

### 2b. If Aiven Kafka is not free

Use **Confluent Cloud** (Basic cluster, $400 signup credits) or **Redpanda
Cloud** (Serverless free tier). Both give you a bootstrap server, an API key and
an API secret. Set:

- `KAFKA_BOOTSTRAP_SERVERS` = their bootstrap endpoint
- `KAFKA_SASL_MECHANISM` = `PLAIN` (Confluent) or `SCRAM-SHA-256` (Redpanda)
- `KAFKA_SASL_JAAS_CONFIG` = as below, with key/secret as username/password
- **omit `KAFKA_CA_PEM`** — both use public CAs

No code changes. Create the same six topics.

### The JAAS string

This is the one value whose exact shape matters. All on one line, and the
trailing semicolon is required:

```
org.apache.kafka.common.security.scram.ScramLoginModule required username="AVNADMIN_USER" password="THE_PASSWORD";
```

For Confluent (mechanism `PLAIN`), the module class differs:

```
org.apache.kafka.common.security.plain.PlainLoginModule required username="API_KEY" password="API_SECRET";
```

## 3. MongoDB Atlas — read model

Skip if you are running without a read model.

1. <https://www.mongodb.com/cloud/atlas/register> → sign up (no card for M0).
2. **Build a Database** → **M0 FREE** → provider/region closest to Oregon →
   **Create**.
3. **Security → Quickstart**: create a database user, save the password.
4. **Network Access** → **Add IP Address** → **Allow access from anywhere**
   (`0.0.0.0/0`).

   > Render free instances have no static outbound IP, so there is no narrower
   > rule available. This is acceptable for a demo with throwaway data and
   > **would not be** for anything real. Combined with a strong generated
   > password, the exposure is the password's strength.

5. **Database → Connect → Drivers** → copy the connection string. Replace
   `<password>`, and add the database name before the `?`:

   ```
   mongodb+srv://USER:PASSWORD@cluster0.xxxxx.mongodb.net/ledgerguard_read?retryWrites=true&w=majority
   ```

## 4. Render — the five services

Render can create all five from `render.yaml` in one go.

1. <https://dashboard.render.com> → **Sign up** (GitHub sign-in; no card for free).
2. **New +** → **Blueprint**.
3. Connect the GitHub repo → select branch `claude/ledgerguard-master-build-d4bpzz`.
4. Render reads `render.yaml` and lists five services. **Apply**.
5. It now prompts for every variable marked `sync: false`. Fill in:

**`ledgerguard-transaction`, `ledgerguard-query`, `ledgerguard-reconciliation`** —
all three get the same infrastructure values:

| Key | Value |
|---|---|
| `JDBC_DATABASE_URL` | `jdbc:postgresql://HOST:PORT/defaultdb?sslmode=require` |
| `DATABASE_USERNAME` | `avnadmin` |
| `DATABASE_PASSWORD` | Aiven Postgres password |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka SASL host:port |
| `KAFKA_SASL_JAAS_CONFIG` | the JAAS line from §2 |
| `KAFKA_CA_PEM` | full contents of `ca.pem`, `-----BEGIN` through `-----END-----`. Omit for a public-CA provider. |

**`ledgerguard-query`** additionally:

| Key | Value |
|---|---|
| `MONGO_URI` | the Atlas string from §3, or leave blank |

**`ledgerguard-auth`**:

| Key | Value |
|---|---|
| `CORS_ALLOWED_ORIGINS` | your Vercel domain, e.g. `https://ledgerguard.vercel.app`. Only needed for the direct-to-service setup; harmless otherwise. |

**`ledgerguard-gateway`** — leave the three URL variables **blank for now**. You
do not have the URLs yet.

6. Deploy. First builds take **10–20 minutes** each (Maven builds the reactor
   inside Docker). They run in parallel.
7. When the four non-gateway services are live, copy each `.onrender.com` URL
   from its dashboard page.
8. Open **`ledgerguard-gateway` → Environment** and set:

   | Key | Value |
   |---|---|
   | `AUTH_SERVER_URL` | `https://ledgerguard-auth.onrender.com` |
   | `TRANSACTION_SERVICE_URL` | `https://ledgerguard-transaction.onrender.com` |
   | `QUERY_SERVICE_URL` | `https://ledgerguard-query.onrender.com` |

   No trailing slashes. Save — this redeploys the gateway.

9. Verify: `curl https://ledgerguard-gateway.onrender.com/actuator/health`
   → `{"status":"UP"}`. Allow a minute for a cold start.

### If a service will not start

Check its Render log for these, in order of likelihood:

| Log says | Cause |
|---|---|
| `JDBC_DATABASE_URL is required` | Variable not set on that service. |
| `The connection attempt failed` | `sslmode=require` missing from the URL. |
| `unable to find valid certification path` | Aiven Kafka without `KAFKA_CA_PEM`. |
| `Authentication failed` (Kafka) | JAAS string malformed — check the trailing `;`. |
| Killed / restarted with no error | Out of memory. See §7. |
| `read model is NOT configured` (warning) | Expected when `MONGO_URI` is unset. |

## 5. Vercel — the console

1. **Edit `frontend/vercel.json` first** and replace the placeholder with your
   real gateway URL:

   ```json
   "destination": "https://ledgerguard-gateway.onrender.com/api/:path*"
   ```

   Commit and push. Vercel does not interpolate environment variables into
   `vercel.json` rewrites, so this genuinely has to be a literal.

2. <https://vercel.com/signup> → sign in with GitHub.
3. **Add New → Project** → import the repo.
4. Set **Root Directory** to `frontend`. Vercel detects Vite; leave the build
   command and output directory as detected.
5. **Deploy.**

The rewrite keeps the browser on one origin, so no CORS is involved. If you
instead set `VITE_API_BASE_URL` to the gateway URL, calls go cross-origin and
you must configure CORS on the gateway — which is why the rewrite is the
documented path.

## 6. Seed the demo data

```bash
./scripts/seed-demo.sh https://ledgerguard-gateway.onrender.com 40
```

This POSTs through `/api/v1/transactions` — the same endpoint the console uses.
Nothing is written directly to a database. Each transaction travels the real
path: aggregate + outbox in one transaction → Kafka → reconciliation →
projection → console.

The script waits for the stack to wake, logs in through the real auth endpoint,
submits at a paced rate, then prints the dashboard.

**It will report a 0% match rate.** That is correct.
`ReconcileTransactionHandler.externalCandidatesFor` returns an empty list
because no counterparty statement feed exists in this repository, so every
transaction reconciles as `UNMATCHED`. The engine, contract, projection, metrics
and console are all exercised end to end regardless. Seeding fake counterparty
rows to make the number look better is the kind of thing the phase-16 audit
exists to prevent.

## 7. The free tier's real behaviour

**Instances sleep after 15 minutes idle.** The first request then takes 30–60s
to wake the instance, plus JVM start. With the gateway in front, a cold request
can wake *two* instances in sequence. The gateway's timeouts are set to 20s
connect / 90s response for exactly this reason — shorter values turn a normal
cold start into a 504 that reads as a broken demo.

Practically: **hit the URL once and wait a minute before showing anyone.**

**512 MB per instance.** `docker/cloud-entrypoint.sh` sets
`MaxRAMPercentage=55` (not the local 75), SerialGC rather than G1, and caps
metaspace and code cache. At 75% a Spring Boot service with a Kafka consumer and
a JDBC pool gets OOM-killed, and Render reports that only as an unexplained
restart. If you see silent restarts, lower it further before looking elsewhere.

**Connection budget.** Each service uses a 3-connection pool (`DB_POOL_SIZE`),
because three services share one free Postgres. Raising it produces "too many
clients" under load.

**Five services on free hours.** Render's free allowance is shared across
services. Five Java services will consume it faster than you expect. If you run
short, `ledgerguard-gateway` and `ledgerguard-auth` are the cheap ones; dropping
`ledgerguard-reconciliation` leaves everything working except that transactions
stay at `RECEIVED` forever.

## 8. What is honestly different from the documented architecture

| Documented | Hosted demo |
|---|---|
| Three Postgres databases | One shared, separate Flyway history tables |
| Zipkin tracing | None. Trace IDs in logs only. |
| Prometheus + Grafana | Endpoint exposed, nothing scraping |
| Redis | Absent (was only for unimplemented rate limiting) |
| Always-on services | Sleep after 15 min; 30–60s cold start |
| Kafka RF=3, min ISR 2 | RF=2 on free tier, 1 partition per topic |
| Signed short-lived JWT | HTTP Basic, replayable credential in `localStorage` |
| Real counterparty feed | None — every transaction reconciles `UNMATCHED` |

The last two are pre-existing gaps (tasks #25 and #26), not artefacts of hosting.
