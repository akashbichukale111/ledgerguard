# Contributing to LedgerGuard

Thank you for your interest in contributing! This document outlines our development practices, workflow, and expectations.

## Code of Conduct

Be respectful, inclusive, and professional. We're building software for financial institutions; trust and accuracy matter.

## Development Setup

### Prerequisites

- JDK 21 (OpenJDK Temurin recommended)
- Maven 3.8+
- Docker & Docker Compose
- Node.js 20+
- Git

### Initial Setup

```bash
# Clone repository
git clone https://github.com/akashbichukale111/ledgerguard.git
cd ledgerguard

# Verify build
mvn clean verify -DskipITs

# Start infrastructure
docker-compose -f docker-compose-full.yml up -d

# Install frontend dependencies
cd frontend && npm ci
```

## Branch Strategy

### Branch Naming

```
feature/description          New feature or enhancement
bugfix/description           Bug fix
refactor/description         Code refactoring
docs/description             Documentation only
perf/description             Performance improvement
security/description         Security fix or hardening
```

**Examples**:
- `feature/dlt-replay-api`
- `bugfix/transaction-dedup-race`
- `refactor/projection-caching`
- `docs/deployment-guide`

### Main Branches

- **`main`** or **`master`**: Production-ready code
  - Protected branch (requires PR review)
  - All CI/CD checks must pass
  - Tagged with semantic versions (v0.1.0, v0.2.0, etc.)

- **Development branches**: Feature/bugfix branches off `main`
  - Deleted after merge
  - 1 feature per branch (avoid mixing concerns)

## Git Workflow

### 1. Create Feature Branch

```bash
git checkout -b feature/my-feature
```

### 2. Make Changes

```bash
# Work on feature, make small logical commits
git add <files>
git commit -m "Descriptive message"
```

### 3. Commit Message Format

```
<type>: <subject>

<body>

<footer>
```

**Types**:
- `feat`: New feature
- `fix`: Bug fix
- `refactor`: Code restructuring (no behavior change)
- `perf`: Performance improvement
- `test`: Test additions/modifications
- `docs`: Documentation updates
- `style`: Code formatting (not logic changes)
- `chore`: Build, CI, dependencies

**Subject**:
- Imperative mood ("add" not "added")
- Lowercase, no period
- < 50 characters

**Body**:
- Wrap at 72 characters
- Explain *why*, not *what* (code shows what)
- Reference related issues: "Fixes #123"

**Example**:
```
feat: Add DLT replay capability to query service

Implement POST /api/v1/replay/dlt-message endpoint to allow operators
to replay dead-lettered messages. Uses causation ID to track replayed
messages through the event stream.

Fixes #456
```

### 4. Keep Branch Updated

```bash
git fetch origin
git rebase origin/main    # or git merge origin/main
```

### 5. Push and Create PR

```bash
git push -u origin feature/my-feature
```

Create Pull Request on GitHub with:
- Clear title matching commit message
- Detailed description of changes
- Reference related issues
- Screenshots (for UI changes)

## Code Style

### Java

#### Naming Conventions

```java
// Classes: PascalCase
public class TransactionService { }
public class RbacMatrix { }

// Methods: camelCase
public void processTransaction() { }
public boolean hasPermissionLevel(Role required) { }

// Constants: UPPER_SNAKE_CASE
public static final String TRACE_ID = "traceId";
private static final int RETRY_LIMIT = 3;

// Variables: camelCase
String transactionId = "tx-123";
boolean isMatched = true;
```

#### Code Format

Run Spotless to auto-format:

```bash
mvn spotless:apply
```

Enforced in CI; code formatting failures block merge.

#### Comments

- No redundant comments ("count++" doesn't need explanation)
- Explain *why*, not *what*
- Use `// TODO: reason` for deferred work
- Javadoc on public APIs (not internal helpers)

```java
// Good: explains intent
// Retry up to 3 times to handle transient network errors
private static final int RETRY_LIMIT = 3;

// Bad: states the obvious
int count = 0;  // Initialize count to zero
```

#### Error Handling

Use checked exceptions for recoverable errors; unchecked for programming errors.

```java
// Recoverable: checked exception
throw new TransactionNotFoundException("Transaction not found: " + id);

// Programming error: unchecked exception
throw new IllegalArgumentException("Amount cannot be negative");
```

### TypeScript / React

```typescript
// Components: PascalCase
export function Dashboard() { }
export function TransactionSearchForm() { }

// Hooks: camelCase with 'use' prefix
export function useTransactions() { }
export function useAuth() { }

// Types: PascalCase
interface Transaction {
  transactionId: string;
  amount: string;
}

type DashboardMetrics = { /* ... */ };

// Variables: camelCase
const transactionId = "tx-123";
let isLoading = false;
```

### Configuration

- **Tabs**: 2 spaces (JavaScript), 4 spaces (Java)
- **Line length**: 100 characters (hard limit)
- **No trailing whitespace**
- **No unused imports**

## Testing

### Coverage Requirements

- All new code must have unit tests
- Integration tests for critical paths (retry ladder, audit trail)
- Minimum 70% coverage on new code

### Running Tests

```bash
# Unit tests (fast, no Docker)
mvn clean test -DskipITs

# Integration tests (requires Docker)
mvn clean test

# Specific test
mvn test -Dtest=TransactionServiceTest

# Coverage report
mvn clean verify

# Open report
open target/site/jacoco/index.html
```

### Test Structure

```java
public class RbacMatrixTest {
  // Setup
  private RbacMatrix matrix;

  @BeforeEach
  void setUp() {
    matrix = new RbacMatrix();
  }

  @Nested
  @DisplayName("Permission checks")
  class PermissionChecks {
    @Test
    void adminHasAllPermissions() {
      assertTrue(matrix.canPerform(Role.ADMIN, Operation.DLT_REPLAY));
    }

    @Test
    void operatorCannotManageRoles() {
      assertFalse(matrix.canPerform(Role.OPERATIONS, Operation.ROLE_MANAGE));
    }
  }
}
```

**Best Practices**:
- One assertion per test (or related assertions)
- Descriptive test names (`adminHasAllPermissions` not `test1`)
- Mock external dependencies
- Use `@Nested` for logical grouping
- @DisplayName for human-readable test output

## Code Review

### Before Submitting

- [ ] Branch is up to date with `main`
- [ ] All tests pass: `mvn clean verify`
- [ ] Code formatted: `mvn spotless:apply`
- [ ] No security issues: `mvn org.owasp:dependency-check-maven:check`
- [ ] Commit messages follow format
- [ ] PR description is clear and references issues

### During Review

**Reviewer expectations**:
- Understand the problem being solved
- Check logic correctness and edge cases
- Verify test coverage
- Ensure code style consistency
- Catch security/performance issues

**Author expectations**:
- Respond to all comments (resolve or discuss)
- Make requested changes; don't argue about style
- Request re-review after updates
- Don't merge until approved

**Approval criteria**:
- ✓ 1+ maintainer approval
- ✓ All CI checks passing
- ✓ No conflicting changes

## Architecture Decisions

Major changes require an Architecture Decision Record (ADR).

### When to Write an ADR

- Framework/library choices (Spring vs Quarkus)
- Architectural patterns (CQRS, Event Sourcing)
- API design changes
- Technology stack modifications

### ADR Format

**File**: `docs/adr/NNNN-short-title.md`

```markdown
# ADR-NNNN: Short Title

**Status**: Proposed | Accepted | Deprecated

## Problem

Context and problem statement.

## Decision

What was decided and why.

## Consequences

Positive and negative impacts.

## Alternatives Considered

Other options and why they were rejected.
```

**Example**: See [ADR-0013: Hexagonal Layering](docs/adr/0013-hexagonal-layering.md)

## Performance Considerations

### Benchmarking

Performance-sensitive changes should include benchmarks:

```bash
cd perf
make test-transaction BASE_URL=http://localhost:8080/api/v1
```

**Baseline targets**:
- Transaction ingestion: p95 < 500ms
- Reconciliation queries: p95 < 1000ms
- Projection lag: < 5 seconds

### Optimization Guidelines

1. **Measure first**: Profile with JFR or k6 before optimizing
2. **Profile hotspots**: Use async-profiler to find bottlenecks
3. **Cache wisely**: Only cache high-hit-rate, cheap-to-invalidate data
4. **Batch operations**: Reduce round-trips to database/Kafka
5. **Avoid premature optimization**: Simple code beats micro-optimized code

## Documentation

### Code Documentation

- Public API methods: Javadoc with examples
- Complex logic: Inline comments explaining *why*
- Configuration: Document with example values
- Deprecations: Note alternatives and timeline

### User Documentation

Update [README.md](README.md) and relevant phase reports when:
- Adding new API endpoints
- Changing deployment steps
- Modifying configuration
- Adding architectural layers

### Phase Reports

Add to `docs/phase-reports/phase-NN.md` when completing a phase:
- What was built
- Design decisions
- Test results
- Known limitations
- Next steps

## Security Guidelines

### Code Security

- Never commit secrets (API keys, passwords, tokens)
- Validate all user input
- Use parameterized queries (no SQL injection)
- Don't log sensitive data (SSNs, card numbers)
- Use HTTPS in production
- Implement rate limiting on public APIs

### Dependency Security

Run security checks before merge:

```bash
mvn org.owasp:dependency-check-maven:check
```

Update vulnerable dependencies immediately (blocking issue if CRITICAL).

### Secret Handling

- Use environment variables for secrets (not `.env` in repo)
- GitHub Actions uses Secrets (Settings → Secrets)
- Never log secrets even in trace mode
- Rotate secrets regularly in production

## Deployment

### Pre-Merge Checklist

- [ ] All tests pass
- [ ] Security scan passes (no CRITICAL/HIGH)
- [ ] Code review approved
- [ ] Performance not degraded (if relevant)
- [ ] Documentation updated

### Release Process

Handled by maintainers:

1. Create release branch: `release/v0.2.0`
2. Update version in `pom.xml`
3. Document changes in release notes
4. Tag commit: `git tag v0.2.0`
5. Push tag to trigger Docker build
6. Publish release on GitHub

## Getting Help

### Questions

- **Architecture questions**: Open a discussion or ADR
- **Setup issues**: Check README and DEPLOYMENT.md first
- **Bug reports**: Open an issue with reproduction steps
- **Feature requests**: Open an issue with use case

### Mentoring

New contributors welcome! Ask for help:
- On PR reviews if you don't understand feedback
- In discussions for guidance
- Comment on issues if you're interested

Experienced contributors: please review junior PRs promptly and be encouraging.

## Commit Authorship

Include co-authored commits for pair programming:

```bash
git commit -m "Implement matching algorithm

Co-Authored-By: Jane Doe <jane@example.com>"
```

## Continuous Improvement

We track improvement through:
- Phase reports (what was built)
- Architecture decision records (why decisions were made)
- Performance baselines (is it getting faster or slower?)
- Test coverage trends (are we maintaining quality?)

Suggest improvements to development process in discussions.

---

**Thank you for contributing to LedgerGuard!**
