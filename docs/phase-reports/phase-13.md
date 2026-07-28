# Phase 13: CI/CD and Image Hardening

**Status**: ✅ CI/CD PIPELINE COMPLETE

**Acceptance Gate**: ✅ GitHub Actions workflows configured; Docker images hardened; security scanning enabled
- Build pipeline: Unit tests, integration tests (Docker required), code quality checks
- Docker images: Multi-stage builds, unprivileged users, minimal JRE footprint
- Security scanning: Trivy vulnerability scanning, OWASP dependency check, secret detection
- Artifact management: Test reports, Docker images, performance results
- Performance tests: Integrated as optional workflow (manual trigger or main branch push)

## What Was Built

### 1. GitHub Actions CI/CD Workflows

**Technology Stack**:
- **Platform**: GitHub Actions (native CI/CD)
- **Language Runtimes**: JDK 21 (Temurin), Node.js 20
- **Build Tool**: Maven 3.8+ (cached)
- **Security Scanners**: Trivy, OWASP Dependency Check, SpotBugs, TruffleHog
- **Artifact Storage**: GitHub Artifacts (30-day retention)

**Workflow Directory**:
```
.github/workflows/
├── build-and-test.yml          Main build pipeline (unit + integration tests)
├── docker-build-scan.yml       Docker image building and Trivy scanning
├── performance-tests.yml       k6 load testing (optional)
└── security-scan.yml           SAST, dependencies, secrets, license compliance
```

#### 1.1 Build and Test Workflow (build-and-test.yml)

**Trigger**: Push to master/main/claude/* branches, PRs to master/main
**Duration**: ~30 minutes total
**Timeout**: 30 minutes per job

**Jobs**:

##### build-test Job
- **Steps**:
  1. Checkout code
  2. Set up JDK 21 (with Maven cache)
  3. Run unit tests: `mvn clean test -DskipITs`
  4. Run integration tests: `mvn test -DskipUnitTests` (continue on error)
  5. Upload test results as artifact
  6. Publish test report to GitHub

- **Caching**: Maven dependencies cached in GitHub Actions cache
- **Artifacts**: Surefire + Failsafe reports (30-day retention)
- **Failure Mode**: Unit test failure blocks build; integration test failure is reported but doesn't block

**Output**:
- Test counts: e.g., "44 passed, 0 failed"
- Coverage: JUnit report linked in PR/commit
- Artifacts: `TEST-*.xml` files downloadable from Actions

##### code-quality Job (Parallel)
- **Steps**:
  1. Checkout code
  2. Set up JDK 21
  3. Check code formatting: `mvn spotless:check`
  4. Run architecture tests: `mvn test -Dtest=*ArchTest`
  5. Run dependency security: `mvn org.owasp:dependency-check-maven:check`

- **Failures**: Formatting check fails build; architecture test fails reported; dependency check is advisory
- **Output**: Spotless violations printed to console; arch test results

##### frontend-build Job (Parallel)
- **Steps**:
  1. Checkout code
  2. Set up Node.js 20
  3. Install dependencies: `npm ci`
  4. Type check: `npm run type-check`
  5. Build: `npm run build`
  6. Upload dist artifact

- **Caching**: npm dependencies cached
- **Artifacts**: `frontend/dist/` (30-day retention)
- **Failure Mode**: Type check or build failure blocks

#### 1.2 Docker Build and Scan Workflow (docker-build-scan.yml)

**Trigger**: Push to master/main/claude/* with changes in services/, frontend/, or Dockerfile
**Duration**: ~30-45 minutes
**Services Built**: transaction-service, reconciliation-service, query-service, frontend

**Job Matrix**:

```yaml
strategy:
  matrix:
    service:
      - transaction-service
      - reconciliation-service
      - query-service
```

**Steps per Service**:
1. Checkout code
2. Set up JDK 21
3. Build service JAR: `mvn clean package -pl services/{service}`
4. Set up Docker Buildx
5. Build Docker image (multi-stage)
6. Scan with Trivy: `aquasecurity/trivy-action`
7. Upload SARIF results to GitHub Security tab

**Trivy Scanning**:
- Severity filter: CRITICAL, HIGH
- Format: SARIF (GitHub-compatible)
- Output: `trivy-{service}.sarif` uploaded to Security → Code scanning

**Image Features**:
- Multi-stage build: Build in JDK image, run in minimal JRE
- Non-root user: `appuser` (UID 1000)
- Permissions: JAR is read-only (500), app directory 700
- JVM tuning: `-XX:+UseG1GC -XX:MaxRAMPercentage=75.0`
- Health check: Curl to `/actuator/health`

#### 1.3 Security Scan Workflow (security-scan.yml)

**Trigger**: Push to master/main, every PR, nightly schedule (2 AM UTC)
**Duration**: ~20 minutes
**Timeout**: 20 minutes per job

**Jobs**:

##### dependency-check Job
- **Steps**:
  1. Checkout code
  2. Set up JDK 21
  3. Run OWASP Dependency Check: `mvn org.owasp:dependency-check-maven:check`
  4. Upload HTML report

- **Report**: `target/dependency-check-report.html` (downloadable)
- **Failures**: Advisory (continue-on-error: true)

##### code-scanning Job
- **Steps**:
  1. Checkout code
  2. Set up JDK 21
  3. Build for analysis: `mvn clean compile`
  4. Run SpotBugs: `mvn spotbugs:check`

- **Output**: SpotBugs report (static analysis for potential bugs)
- **Failures**: Advisory

##### secret-scanning Job
- **Steps**:
  1. Checkout code with full history
  2. Run TruffleHog: Scan for secrets, API keys, credentials
  3. Output debug JSON

- **Scanning**: Diffs and all files for entropy-based secret detection
- **Tool**: TruffleHog by Truffle Security
- **Output**: JSON with confidence scores

##### license-compliance Job
- **Steps**:
  1. Checkout code
  2. Set up JDK 21
  3. Check license compliance: `mvn license:check`
  4. Generate license report: `mvn license:aggregate-report`
  5. Upload report

- **Report**: HTML license report (dependencies and their licenses)
- **Compliance Check**: Ensures no GPL/incompatible licenses in dependencies

#### 1.4 Performance Tests Workflow (performance-tests.yml)

**Trigger**: Push to master/main (optional); manual workflow dispatch
**Duration**: ~45 minutes
**Services Started**: transaction-service, Postgres, Kafka

**Steps**:
1. Checkout code
2. Set up JDK 21
3. Start services (Postgres, Kafka, Java services)
4. Wait for Kafka readiness
5. Build all services
6. Start transaction-service in background
7. Set up k6
8. Run transaction ingestion load test
9. Check results and upload JSON

**Optional**: Can be disabled for fast CI (only build-and-test runs)

**Output**: `perf/results.json` with metrics

### 2. Docker Image Hardening

#### 2.1 Multi-Stage Build Pattern

All backend services use identical Dockerfile pattern:

```dockerfile
# Build stage: JDK Alpine (heavy)
FROM eclipse-temurin:21-jdk-alpine AS build
RUN mvn clean package ...

# Runtime stage: JRE Alpine (minimal)
FROM eclipse-temurin:21-jre-alpine
COPY --from=build ...
```

**Benefits**:
- **Size**: Final image ~300MB (vs 600MB+ with JDK)
- **Attack Surface**: No compiler, build tools in runtime
- **Supply Chain**: No source code in container

#### 2.2 Security Hardening Details

##### Non-Root User
```dockerfile
RUN addgroup -g 1000 appuser && \
    adduser -D -u 1000 -G appuser appuser
USER appuser
```

**Rationale**: Container breakout defaults to `appuser` (UID 1000), not root

##### Restrictive Permissions
```dockerfile
RUN chmod 500 app.jar && chmod 700 /app
```

**Rationale**:
- JAR is read-only (500): No accidental modification
- App directory is execute-only (700): Only appuser can access

##### Health Check
```dockerfile
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD wget -q -O- http://localhost:8080/actuator/health || exit 1
```

**Rationale**: Kubernetes/orchestrators use health check to restart unhealthy containers

##### JVM Tuning for Containers
```dockerfile
ENTRYPOINT ["java", \
  "-XX:+UseG1GC",              # Garbage collector optimized for low latency
  "-XX:MaxRAMPercentage=75.0", # Use 75% of container memory limit
  "-Dspring.profiles.active=docker", # Docker-specific Spring profile
  "-jar", "app.jar"]
```

**Rationale**:
- G1GC: Lower pause times, better for containerized workloads
- RAM limit: Respects container memory constraints (read from cgroup)
- Docker profile: Spring can apply Docker-specific configs

#### 2.3 Frontend Image Hardening

**Dockerfile**:
```dockerfile
# Build: Node.js Alpine (heavy)
FROM node:20-alpine AS build
RUN npm ci && npm run build

# Runtime: Nginx Alpine (minimal)
FROM nginx:1.27-alpine
COPY --from=build /build/dist /usr/share/nginx/html
```

**Security**:
- Non-root: Nginx runs as `nginx` user (UID 100)
- Permissions: HTML files 755 (readable, executable)
- Nginx config: Custom security headers

##### Nginx Security Headers
```nginx
add_header Strict-Transport-Security "max-age=31536000; includeSubDomains";
add_header X-Content-Type-Options "nosniff";
add_header X-Frame-Options "DENY";
add_header X-XSS-Protection "1; mode=block";
```

**Rationale**:
- HSTS: Force HTTPS for 1 year
- X-Content-Type-Options: Prevent MIME-type sniffing
- X-Frame-Options: Prevent clickjacking
- XSS Protection: Enable browser XSS filter

##### API Proxy
```nginx
location /api/ {
    proxy_pass http://api:8080;
    # Set headers for backend to identify client
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
}
```

**Rationale**: Frontend proxies `/api/*` calls to backend, enabling SPA on same domain

### 3. Workflow Execution Scenarios

#### Scenario 1: Developer Push to Feature Branch

**Branch**: `claude/feature-xyz`
**Trigger**: Push to branch name matching `claude/**`
**Workflows Run**:
- ✓ build-and-test (unit + integration tests)
- ✓ docker-build-scan (builds images, scans with Trivy)

**Result**: PR checks show test results + Trivy scan results

#### Scenario 2: Push to Main Branch

**Branch**: `main` or `master`
**Trigger**: Merge PR or direct push
**Workflows Run**:
- ✓ build-and-test
- ✓ docker-build-scan
- ✓ security-scan (dependency check, SpotBugs, secrets, licenses)
- ✓ performance-tests (if enabled)

**Result**: All security checks pass; performance baseline captured

#### Scenario 3: Pull Request

**Event**: PR opened/updated
**Trigger**: Changes to master/main
**Workflows Run**:
- ✓ build-and-test
- ✓ docker-build-scan (if Dockerfile/service changes)

**Result**: PR shows "All checks passed" or lists failures

#### Scenario 4: Scheduled Security Scan (Nightly)

**Time**: 2 AM UTC daily
**Workflows Run**:
- ✓ security-scan only (dependency-check, SpotBugs, secrets, license)

**Result**: Email notification if vulnerabilities found

#### Scenario 5: Manual Performance Test Trigger

**Method**: GitHub UI → Actions → Performance Tests → Run workflow
**Trigger**: Selectable branch, manual dispatch
**Duration**: ~45 minutes
**Output**: k6 results uploaded as artifact

### 4. CI/CD Best Practices Implemented

#### 4.1 Caching

**Maven**:
```yaml
uses: actions/setup-java@v4
with:
  cache: maven  # ~/.m2/repository cached
```

**NPM**:
```yaml
uses: actions/setup-node@v4
with:
  cache: 'npm'
  cache-dependency-path: frontend/package-lock.json
```

**Benefit**: First build ~5 min, subsequent ~2 min (skip dependency download)

#### 4.2 Fail-Fast Strategy

**Blocking Failures**:
- Unit tests fail
- Code formatting (Spotless) fails
- Frontend TypeScript type check fails
- Frontend build fails

**Non-Blocking (Advisory)**:
- Integration tests (may need Docker)
- Architecture tests (informational)
- Dependency Check (tracked separately)
- SpotBugs (static analysis)
- TruffleHog (can have false positives)

**Rationale**: Don't block merge on warnings; fail on correctness

#### 4.3 Artifact Retention

**Test Reports**: 30 days (can download from Actions page)
**Docker Images**: Not stored in GitHub Actions (pushed to registry in future phase)
**Coverage Reports**: 30 days

#### 4.4 Parallel Job Execution

**build-test.yml**:
```yaml
jobs:
  build-test: ...
  code-quality: ...      # Runs in parallel
  frontend-build: ...    # Runs in parallel
```

**docker-build-scan.yml**:
```yaml
strategy:
  matrix:
    service: [transaction, reconciliation, query]  # 3 containers in parallel
```

**Benefit**: Full pipeline completes in ~30 min (not ~90 min if sequential)

#### 4.5 Artifact Staging

**Multi-stage builds**:
- Maven: Only package JAR (no target/ directory in final image)
- Node: Only dist/ (no node_modules/ in final image)

**Benefit**: Minimal image size, no build artifacts shipped

### 5. Trivy Vulnerability Scanning

**Scanner**: aquasecurity/trivy (OCI image scanner)
**Scan Coverage**:
- OS packages (Alpine Linux packages)
- Java dependencies (Maven - future improvement with SBOM)
- Known CVE database (NVD, GitHub advisories)

**Severity Levels**:
- CRITICAL: Exploitable CVEs in running context
- HIGH: Privilege escalation, remote code execution
- MEDIUM: Denial of service, information disclosure
- LOW: Minor vulnerabilities

**Workflow**:
1. Build image: `docker build -t ledgerguard/service:latest .`
2. Scan with Trivy: `trivy image ledgerguard/service:latest`
3. Export SARIF: GitHub-compatible format
4. Upload: GitHub Security → Code scanning tab

**Result**: Each scan produces report like:
```
ledgerguard/transaction-service:latest (alpine 3.18.4)

Found 3 vulnerabilities
  CRITICAL: CVE-2023-1234 in openssl (1.1.1w)
  HIGH: CVE-2023-5678 in zlib (1.2.13)
  MEDIUM: CVE-2023-9999 in ca-certificates
```

### 6. Running Workflows Locally

#### Simulate GitHub Actions Locally with `act`

```bash
# Install act (https://github.com/nektos/act)
brew install act

# Run build-and-test locally
cd /home/user/ledgerguard
act push -j build-test -l

# Run docker-build-scan for one service
act push -j build-backend-images --matrix service=transaction-service -l

# List all workflows
act -l
```

**Benefit**: Test workflow changes without pushing to GitHub

#### Manual Execution

```bash
# Unit tests only (no Docker required)
mvn clean test -DskipITs

# Unit + integration tests (requires Docker)
mvn clean test

# Frontend build
cd frontend && npm ci && npm run build

# Docker image build
docker build -t ledgerguard/transaction-service:latest \
  -f services/transaction-service/Dockerfile .

# Trivy scan
trivy image ledgerguard/transaction-service:latest
```

### 7. Docker Compose for Local Development

**File**: `docker-compose-full.yml`

**Services** (3-minute startup):
- PostgreSQL (port 5432)
- Zookeeper (port 2181)
- Kafka (port 9092)
- Transaction Service (port 8081)
- Reconciliation Service (port 8082)
- Query Service (port 8083)
- Frontend (port 3000)
- Prometheus (port 9090)
- Grafana (port 3001)

**Usage**:

```bash
# Start all services
docker-compose -f docker-compose-full.yml up -d

# Build services first (if changed)
docker-compose -f docker-compose-full.yml up -d --build

# View logs
docker-compose -f docker-compose-full.yml logs -f transaction-service

# Shutdown
docker-compose -f docker-compose-full.yml down

# Shutdown + clean volumes
docker-compose -f docker-compose-full.yml down -v
```

**Health Checks**: Each service includes healthcheck; use `docker-compose ps` to see status

### 8. Security Scanning Summary

#### OWASP Dependency Check
- **Purpose**: Identify known vulnerable dependencies
- **Scope**: Maven dependencies (pom.xml)
- **Report**: HTML report with CVE links
- **Frequency**: Every build (main branch)

#### SpotBugs
- **Purpose**: Static analysis for common Java bugs
- **Detects**: Null pointer dereferences, SQL injection risks, etc.
- **Report**: XML report (GitHub Actions parses)
- **Frequency**: Code quality job

#### TruffleHog
- **Purpose**: Secret scanning (API keys, credentials)
- **Detects**: Entropy-based patterns (API keys, private keys)
- **False Positives**: Can flag generated keys or test fixtures
- **Frequency**: Every build (main branch) + nightly

#### Trivy
- **Purpose**: Container image vulnerability scanning
- **Detects**: OS package CVEs (Alpine Linux)
- **Report**: SARIF format (GitHub Code Scanning tab)
- **Frequency**: On Docker image changes

### 9. GitHub Security Integration

#### Code Scanning Tab

Location: **GitHub → Repository → Security → Code scanning**

**Alerts**:
- Trivy findings (image vulnerabilities)
- SARIF uploads (SAST results)
- Severity filter: CRITICAL, HIGH

**Management**:
- Dismiss with reason (false positive, accepted risk)
- Fix + close
- Track by severity/type

#### Secret Scanning

Location: **GitHub → Repository → Security → Secret scanning**

**TruffleHog findings**:
- Entropy score (0-100)
- False positives: dismiss with reason
- Real secrets: revoke immediately

### 10. Files

**New** (Phase 13):
- `.github/workflows/build-and-test.yml`: Main CI pipeline
- `.github/workflows/docker-build-scan.yml`: Docker building + Trivy scanning
- `.github/workflows/security-scan.yml`: SAST, dependencies, secrets, licenses
- `.github/workflows/performance-tests.yml`: Optional k6 load tests
- `services/transaction-service/Dockerfile`: Multi-stage JRE image
- `services/reconciliation-service/Dockerfile`: Multi-stage JRE image
- `services/query-service/Dockerfile`: Multi-stage JRE image
- `frontend/Dockerfile`: Node build → Nginx runtime
- `frontend/nginx.conf`: Security headers, SPA routing, API proxy
- `.dockerignore`: Exclude unnecessary files from Docker build
- `docker-compose-full.yml`: Full stack (DB, Kafka, services, monitoring)
- `docs/phase-reports/phase-13.md`: This documentation

## Workflow Execution Checklist

### Pre-CI Setup

- [ ] GitHub repository created and initialized
- [ ] Branch protections configured (require status checks)
- [ ] GitHub Actions enabled (default in public repos)
- [ ] Docker buildx available (used in docker-build-scan)

### First Run

- [ ] Push to `master` or `main` branch
- [ ] Navigate to **Actions** tab
- [ ] Verify workflows appear:
  - [ ] build-and-test (green checkmark)
  - [ ] docker-build-scan (green checkmark)
  - [ ] security-scan (green checkmark)

### PR Workflow

- [ ] Create feature branch: `git checkout -b claude/feature-xyz`
- [ ] Make changes
- [ ] Push branch
- [ ] Open PR to master/main
- [ ] GitHub shows:
  - [ ] build-and-test: Running
  - [ ] docker-build-scan: Running
- [ ] Wait for ✓ green checks
- [ ] Merge PR

### Security Checks

- [ ] GitHub Security tab shows no CRITICAL findings
- [ ] Trivy scan results accessible (Code scanning tab)
- [ ] Dependency Check report reviewed for HIGH/CRITICAL
- [ ] TruffleHog findings reviewed (dismiss false positives)

## Performance Impact

### Build Times (Cold Cache)

| Stage | Duration |
|-------|----------|
| Maven build | ~5 min |
| Docker build (1 service) | ~3 min |
| Frontend build | ~2 min |
| Trivy scan (1 image) | ~1 min |
| Spotless check | ~1 min |
| Integration tests (with Docker) | ~10 min |
| **Total (full pipeline)** | **~30 min** |

### Artifact Storage

| Artifact | Size | Retention |
|----------|------|-----------|
| Test reports (XML) | ~5 MB | 30 days |
| Docker images (not stored) | N/A | - |
| Frontend dist | ~2 MB | 30 days |
| K6 results | ~1 MB | 30 days |

### Cost Estimate (GitHub Actions Free Tier)

- Free tier: 2,000 minutes/month per repo
- Pipeline: ~30 min per run
- Typical usage: 2-3 runs/day
- **Monthly**: 60-90 runs = 1,800-2,700 minutes
- **Cost**: Free tier sufficient for small team; paid minutes at ~$0.30/min if exceeded

## Next Steps (Phase 14: Demo & Documentation)

1. **README.md**: Quick start guide, architecture overview
2. **CONTRIBUTING.md**: Development setup, branch strategy
3. **DEPLOYMENT.md**: Production deployment steps
4. **Demo Script**: Simulate end-to-end transaction flow
5. **Screenshots**: Key UI components (dashboard, DLT explorer)

## Summary

Phase 13 establishes a production-ready CI/CD pipeline with GitHub Actions. Four workflows handle build/test, Docker scanning, security, and optional performance testing. All backend and frontend images are hardened with multi-stage builds, non-root users, and restrictive permissions. Security scanning integrates with GitHub's native code scanning (Trivy SARIF uploads). Docker Compose enables local full-stack development.

**Ready for Phase 14: Demo Scripts, Screenshots, README.**

