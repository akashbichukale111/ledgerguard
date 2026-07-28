# Deployment Guide

Production deployment of LedgerGuard with Kubernetes, monitoring, and scaling best practices.

## Pre-Deployment Checklist

- [ ] All CI/CD checks passing
- [ ] Security scan results reviewed (no CRITICAL/HIGH CVEs)
- [ ] Performance baselines validated
- [ ] Database migrations tested
- [ ] Disaster recovery plan in place
- [ ] On-call rotations established
- [ ] Monitoring dashboards created
- [ ] Runbooks for common issues written

## Deployment Topology

### High-Level Architecture

```
┌──────────────────────────────────────────────────────┐
│                  Kubernetes Cluster                  │
│                                                       │
│  ┌────────────────────────────────────────────────┐ │
│  │         Ingress Controller (nginx)             │ │
│  └────────────────────────────────────────────────┘ │
│              ↓ ↓ ↓                                   │
│  ┌────────────────────────────────────────────────┐ │
│  │  Frontend Service    Backend Services          │ │
│  │  ┌──────────────┐   ┌───────────────────────┐ │ │
│  │  │ React SPA    │   │ Transaction Service  │ │ │
│  │  │ (3 replicas) │   │ (3 replicas)         │ │ │
│  │  └──────────────┘   ├───────────────────────┤ │ │
│  │                     │ Reconciliation Svc    │ │ │
│  │                     │ (3 replicas)         │ │ │
│  │                     ├───────────────────────┤ │ │
│  │                     │ Query Service        │ │ │
│  │                     │ (3 replicas)         │ │ │
│  │                     └───────────────────────┘ │ │
│  └────────────────────────────────────────────────┘ │
│              ↓ ↓ ↓                                   │
│  ┌────────────────────────────────────────────────┐ │
│  │   Stateful Services                            │ │
│  │  ┌──────────────┐   ┌────────────────────────┐│ │
│  │  │ PostgreSQL   │   │ Kafka Cluster          ││ │
│  │  │ (1 primary + │   │ (3 brokers)            ││ │
│  │  │  2 replicas) │   │ (3 ZK nodes)           ││ │
│  │  └──────────────┘   └────────────────────────┘│ │
│  └────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────┘
         ↓ ↓ ↓
┌──────────────────────────────────────────────────────┐
│         Observability & Logging Stack                │
│  ┌────────────┐  ┌───────┐  ┌──────────────────┐   │
│  │ Prometheus │  │Grafana│  │ Alertmanager     │   │
│  │ (Metrics)  │  │ (UI)  │  │ (Notifications)  │   │
│  └────────────┘  └───────┘  └──────────────────┘   │
└──────────────────────────────────────────────────────┘
```

## Docker Image Registry

### Building Images

```bash
# Build backend images
for service in transaction-service reconciliation-service query-service; do
  docker build -t ledgerguard/$service:v0.1.0 \
    -f services/$service/Dockerfile .
done

# Build frontend image
docker build -t ledgerguard/frontend:v0.1.0 frontend/
```

### Pushing to Registry

```bash
# Configure Docker credentials
docker login registry.example.com

# Tag and push
docker tag ledgerguard/transaction-service:v0.1.0 \
  registry.example.com/ledgerguard/transaction-service:v0.1.0

docker push registry.example.com/ledgerguard/transaction-service:v0.1.0

# Or via CI/CD (GitHub Actions to ECR, GCR, etc.)
```

## Database Setup

### Migrations

```bash
# Run migrations locally before deployment
mvn clean install -DskipTests

# For production, use Flyway in Spring Boot
# Migrations are applied automatically on startup

# Check migration status
docker-compose exec postgres psql -U postgres -d ledgerguard \
  -c "SELECT * FROM flyway_schema_history ORDER BY execution_time DESC;"
```

### PostgreSQL Configuration (Production)

```ini
# postgresql.conf
max_connections = 200
shared_buffers = 256MB
effective_cache_size = 1GB
maintenance_work_mem = 64MB
checkpoint_completion_target = 0.9
wal_buffers = 16MB
default_statistics_target = 100
random_page_cost = 1.1
effective_io_concurrency = 200
work_mem = 8MB
min_wal_size = 1GB
max_wal_size = 4GB
```

### Backup Strategy

```bash
# Daily backups
pg_dump -U postgres -d ledgerguard > backup-$(date +%Y%m%d).sql

# Weekly full backup to S3
aws s3 cp backup-$(date +%Y%m%d).sql \
  s3://ledgerguard-backups/postgres/

# Test restore monthly
pg_restore -U postgres -d test-restore backup-latest.sql
```

## Kafka Configuration

### Broker Configuration (Production)

```ini
# server.properties
broker.id=1
num.network.threads=8
num.io.threads=8
socket.send.buffer.bytes=102400
socket.receive.buffer.bytes=102400
socket.request.max.bytes=104857600

# Log retention (30 days)
log.retention.hours=720
log.cleanup.policy=delete
compression.type=snappy

# Replication (3 replicas minimum)
default.replication.factor=3
min.insync.replicas=2
```

### Topic Configuration

```bash
# Create topics with replication
kafka-topics.sh --create \
  --bootstrap-server kafka:9092 \
  --topic transactions.events.v1 \
  --partitions 12 \
  --replication-factor 3 \
  --config retention.ms=2592000000  # 30 days
  --config compression.type=snappy

# Verify topic
kafka-topics.sh --describe \
  --bootstrap-server kafka:9092 \
  --topic transactions.events.v1
```

## Kubernetes Deployment

### Prerequisites

- EKS/GKE/AKS cluster running (Kubernetes 1.26+)
- kubectl configured
- Helm 3.0+
- Image registry access

### Deployment Files

Create `k8s/` directory with:

```
k8s/
├── namespace.yaml              Kubernetes namespace
├── configmap.yaml              Application configuration
├── secrets.yaml                Sensitive data (encrypted)
├── postgres/
│   ├── statefulset.yaml        PostgreSQL with persistent volume
│   └── service.yaml
├── kafka/
│   ├── statefulset.yaml        Kafka cluster
│   └── service.yaml
├── services/
│   ├── transaction-service/
│   │   ├── deployment.yaml
│   │   ├── service.yaml
│   │   └── hpa.yaml            Horizontal Pod Autoscaler
│   ├── reconciliation-service/
│   ├── query-service/
│   └── frontend/
├── monitoring/
│   ├── prometheus-deployment.yaml
│   ├── grafana-deployment.yaml
│   └── alertmanager-deployment.yaml
└── ingress.yaml                Ingress controller for HTTP routing
```

### Deploying Services

```bash
# Create namespace
kubectl create namespace ledgerguard

# Apply configurations
kubectl apply -f k8s/configmap.yaml -n ledgerguard
kubectl apply -f k8s/secrets.yaml -n ledgerguard

# Deploy databases and message broker
kubectl apply -f k8s/postgres/statefulset.yaml -n ledgerguard
kubectl apply -f k8s/kafka/statefulset.yaml -n ledgerguard

# Wait for stateful services
kubectl wait --for=condition=Ready pod -l app=postgres -n ledgerguard --timeout=300s
kubectl wait --for=condition=Ready pod -l app=kafka -n ledgerguard --timeout=300s

# Deploy application services
kubectl apply -f k8s/services/transaction-service/ -n ledgerguard
kubectl apply -f k8s/services/reconciliation-service/ -n ledgerguard
kubectl apply -f k8s/services/query-service/ -n ledgerguard
kubectl apply -f k8s/services/frontend/ -n ledgerguard

# Deploy ingress
kubectl apply -f k8s/ingress.yaml -n ledgerguard

# Deploy monitoring
kubectl apply -f k8s/monitoring/ -n ledgerguard
```

### Example Deployment

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: transaction-service
  namespace: ledgerguard
spec:
  replicas: 3
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxUnavailable: 1
      maxSurge: 1
  selector:
    matchLabels:
      app: transaction-service
  template:
    metadata:
      labels:
        app: transaction-service
    spec:
      containers:
      - name: transaction-service
        image: registry.example.com/ledgerguard/transaction-service:v0.1.0
        imagePullPolicy: IfNotPresent
        ports:
        - containerPort: 8080
        env:
        - name: SPRING_DATASOURCE_URL
          valueFrom:
            configMapKeyRef:
              name: app-config
              key: database-url
        - name: SPRING_DATASOURCE_PASSWORD
          valueFrom:
            secretKeyRef:
              name: app-secrets
              key: db-password
        - name: SPRING_KAFKA_BOOTSTRAP_SERVERS
          valueFrom:
            configMapKeyRef:
              name: app-config
              key: kafka-brokers
        resources:
          requests:
            memory: "512Mi"
            cpu: "250m"
          limits:
            memory: "1Gi"
            cpu: "1000m"
        livenessProbe:
          httpGet:
            path: /actuator/health
            port: 8080
          initialDelaySeconds: 40
          periodSeconds: 30
          timeoutSeconds: 3
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 8080
          initialDelaySeconds: 20
          periodSeconds: 10
          timeoutSeconds: 3
---
apiVersion: v1
kind: Service
metadata:
  name: transaction-service
  namespace: ledgerguard
spec:
  type: ClusterIP
  selector:
    app: transaction-service
  ports:
  - port: 80
    targetPort: 8080
---
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: transaction-service-hpa
  namespace: ledgerguard
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: transaction-service
  minReplicas: 3
  maxReplicas: 10
  metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 70
  - type: Resource
    resource:
      name: memory
      target:
        type: Utilization
        averageUtilization: 80
```

## Health Checks & Monitoring

### Application Health Endpoints

```bash
# Liveness probe (is service running?)
GET /actuator/health/live

# Readiness probe (is service ready for traffic?)
GET /actuator/health/readiness

# Metrics (Prometheus format)
GET /actuator/prometheus
```

### Prometheus Scrape Config

```yaml
scrape_configs:
  - job_name: 'ledgerguard'
    kubernetes_sd_configs:
    - role: pod
      namespaces:
        names:
        - ledgerguard
    relabel_configs:
    - source_labels: [__meta_kubernetes_pod_label_app]
      action: keep
      regex: '.*-service'
    - source_labels: [__meta_kubernetes_pod_container_port_number]
      action: keep
      regex: '8080'
```

### Key Metrics to Monitor

```
# Transaction Processing
rate(ledgerguard_transaction_ingest_count[5m])   # txn/sec ingestion rate
histogram_quantile(0.95, ledgerguard_transaction_ingest_duration_ms)  # p95 latency

# Reconciliation
rate(ledgerguard_matching_count{outcome="matched"}[5m])  # match rate
ledgerguard_dlt_depth_count  # DLT message backlog

# Infrastructure
container_memory_usage_bytes  # Pod memory usage
container_cpu_usage_seconds_total  # CPU usage
kubelet_volume_stats_used_bytes  # Persistent volume usage

# Database
pg_stat_statements_avg_exec_time  # Query latency
pg_connections_total{state="active"}  # Active connections

# Kafka
kafka_brokers_online  # Broker availability
kafka_consumer_group_lag_sum  # Consumer lag
```

### Grafana Dashboards

Create dashboards for:
- **System**: CPU, memory, disk usage per pod
- **Application**: Ingestion rate, match rate, error rate, latency percentiles
- **Database**: Connection count, query time, transaction latency
- **Kafka**: Broker health, consumer lag, topic throughput
- **Business**: Transactions matched, DLT depth, reconciliation status

## Scaling Strategy

### Horizontal Scaling (Pod Replicas)

```bash
# Auto-scale based on CPU/memory
kubectl autoscale deployment transaction-service \
  --min=3 --max=10 --cpu-percent=70 -n ledgerguard

# Or manually scale
kubectl scale deployment transaction-service \
  --replicas=5 -n ledgerguard
```

### Vertical Scaling (Pod Resources)

Update deployment resource requests/limits:

```yaml
resources:
  requests:
    memory: "1Gi"      # Minimum guaranteed
    cpu: "500m"
  limits:
    memory: "2Gi"      # Maximum allowed
    cpu: "1000m"
```

### Database Scaling

PostgreSQL scaling is harder (stateful). Options:

1. **Read Replicas**: For read-heavy workloads (projections)
   - Replicate to standby instances
   - Route read queries to replicas
   - Primary handles writes

2. **Connection Pooling**: PgBouncer in front of PostgreSQL
   - Reduces connection overhead
   - Enables higher concurrent users

3. **Sharding**: Split by transaction ID ranges (future, if needed)

### Kafka Scaling

Kafka auto-scales within limits:

```bash
# Add broker to cluster
kafka-broker-api-versions --bootstrap-server new-broker:9092

# Reassign partitions (rebalance load)
kafka-reassign-partitions.sh --bootstrap-server kafka:9092 \
  --topics transactions.events.v1 --generate
```

## Disaster Recovery

### Backup Policy

```bash
# PostgreSQL: Daily incremental, weekly full
0 2 * * * /scripts/backup-postgres.sh  # 2 AM daily

# Kafka: Retention policy (30 days in topic)
# Plus off-cluster backup to S3 weekly

# Configuration: Git-based (always recoverable)
```

### Recovery Procedures

#### Database Failure

```bash
# Restore from backup
pg_restore -d ledgerguard backup-20240115.sql

# Verify data integrity
SELECT COUNT(*) FROM transactions;
SELECT MAX(created_at) FROM transactions;
```

#### Kafka Broker Failure

```bash
# Kafka self-heals with replication (min.insync.replicas=2)
# Monitor with:
kafka-replica-verification.sh --broker-list kafka:9092 \
  --topic-white-list ".*"
```

#### Service Crash

```bash
# Kubernetes automatically restarts pod
kubectl get pods -n ledgerguard -w

# Check restart count
kubectl describe pod transaction-service-0 -n ledgerguard
```

### Test Disaster Recovery Monthly

```bash
# Simulate database failure
1. Take production backup
2. Restore to test database
3. Verify data completeness
4. Test transaction ingestion on restored DB
5. Validate reconciliation logic
```

## Security in Production

### Network Policies

```yaml
# Restrict inter-pod traffic
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: ledgerguard-network-policy
  namespace: ledgerguard
spec:
  podSelector: {}
  policyTypes:
  - Ingress
  - Egress
  ingress:
  - from:
    - namespaceSelector:
        matchLabels:
          name: ledgerguard
  egress:
  - to:
    - namespaceSelector:
        matchLabels:
          name: ledgerguard
  - to:
    - podSelector:
        matchLabels:
          k8s-app: kube-dns
    ports:
    - protocol: UDP
      port: 53
```

### Secrets Management

```bash
# Use Kubernetes Secrets (encrypted in etcd)
kubectl create secret generic app-secrets \
  --from-literal=db-password=... \
  --from-literal=jwt-secret=... \
  -n ledgerguard

# Or use external secret manager (AWS Secrets Manager, Vault)
```

### TLS/SSL

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: ledgerguard-ingress
  namespace: ledgerguard
  annotations:
    cert-manager.io/cluster-issuer: "letsencrypt-prod"
spec:
  tls:
  - hosts:
    - api.example.com
    secretName: ledgerguard-tls
  rules:
  - host: api.example.com
    http:
      paths:
      - path: /
        backend:
          service:
            name: frontend
            port:
              number: 80
```

## Runbooks

### Service Degradation

**Symptoms**: High latency (p95 > 1000ms), 5xx errors

```
1. Check metrics dashboard in Grafana
2. Identify bottleneck:
   - High CPU: Scale horizontally (increase replicas)
   - High memory: Check for memory leak (pod restart)
   - High database lag: Check query performance
   - High Kafka lag: Check consumer processing rate
3. Temporary: Scale pods or reduce traffic (rate limit)
4. Permanent: Optimize code or upgrade resources
```

### Database Connection Pool Exhaustion

**Symptoms**: "Too many connections" error, hanging requests

```
1. Check active connections:
   SELECT count(*) FROM pg_stat_activity;
2. Kill long-running queries:
   SELECT pg_terminate_backend(pid) FROM pg_stat_activity 
   WHERE usename = 'postgres' AND state = 'idle';
3. Increase pool size in services (HikariCP)
4. Add read replicas to distribute load
```

### Message Processing Backlog (High DLT Depth)

**Symptoms**: DLT message count increasing, reconciliation lag growing

```
1. Check Kafka consumer lag:
   kafka-consumer-groups --bootstrap-server kafka:9092 --group query-service-group --describe
2. Scale query-service replicas:
   kubectl scale deployment query-service --replicas=5 -n ledgerguard
3. Investigate root cause:
   - Are messages unprocessable? (corrupt data)
   - Is processing slow? (bad performance)
   - Is consumer stuck? (restart pod)
4. Replay messages from DLT after fix
```

## Performance Tuning

### JVM Tuning (Per Service)

```bash
# In Dockerfile
ENTRYPOINT ["java", \
  "-XX:+UseG1GC",                           # Low-pause GC
  "-XX:MaxGCPauseMillis=200",              # Target 200ms pauses
  "-XX:+ParallelRefProcEnabled",           # Faster reference processing
  "-XX:+UnlockExperimentalVMOptions",      # Experimental flags
  "-XX:MaxInlineLevel=15",                 # Inline more aggressively
  "-XX:+TieredCompilation",                # Adaptive JIT
  "-XX:+TieredCompilationLevelExecTime",   # Respect compilation levels
  "-Xmx1g", "-Xms1g",                      # Heap size (adjust per service)
  "-Dspring.profiles.active=prod",
  "-jar", "app.jar"]
```

### Database Query Optimization

```sql
-- Add indexes for frequently queried columns
CREATE INDEX idx_transactions_correlation_id 
ON transactions(correlation_id);

CREATE INDEX idx_audit_entries_timestamp 
ON audit_entries(timestamp DESC);

-- Monitor slow queries
SET log_min_duration_statement = 1000;  -- Log queries > 1s
```

### Kafka Tuning

```ini
# Consumer configuration (application.yml)
spring.kafka.consumer:
  max-poll-records: 500           # Batch more records
  fetch-min-bytes: 10485760       # Wait for 10MB
  fetch-max-wait-ms: 1000         # Or 1 second
  session-timeout-ms: 30000       # 30s session timeout
```

## Cost Optimization

### Development vs Production Configurations

| Resource | Development | Production |
|----------|-------------|-----------|
| Replicas | 1 | 3+ |
| Persistence | Local disk | Managed EBS/GCS |
| Monitoring | Basic | Full stack |
| Backups | On-demand | Automated daily |
| Cost/month | ~$50 | ~$500-1000 |

### Cost Reduction Strategies

1. **Use spot instances** (AWS Spot, GCP Preemptibles)
   - 70% cheaper but can be evicted
   - Use for non-critical services only

2. **Vertical pod autoscaling** (Goldpinger, Vertical Pod Autoscaler)
   - Right-size resource requests

3. **Reserved instances** (1-3 year commitment)
   - 30-50% discount for baseline capacity

4. **Multi-region** (only if needed)
   - Significant cost increase
   - Plan carefully

## Conclusion

Production deployment requires:
- ✓ Automated CI/CD (GitHub Actions)
- ✓ Infrastructure-as-Code (Kubernetes manifests)
- ✓ Monitoring & alerting (Prometheus, Grafana, Alertmanager)
- ✓ Backup & disaster recovery strategy
- ✓ Runbooks for on-call engineers
- ✓ Capacity planning & scaling strategy

For questions or issues, reach out to the platform team.
