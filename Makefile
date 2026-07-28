# ---------------------------------------------------------------------------
# LedgerGuard
#
# `make verify` runs the same checks CI runs. If it passes locally and fails in
# CI, that is a bug in this Makefile, not an acceptable difference.
# ---------------------------------------------------------------------------

SHELL := /bin/bash
.DEFAULT_GOAL := help

COMPOSE      := docker compose
MVN          := ./mvnw
MVN_FALLBACK := mvn

# Prefer the wrapper when it exists so everyone builds with the same Maven.
MAVEN := $(shell [ -x ./mvnw ] && echo ./mvnw || echo mvn)

.PHONY: help
help: ## Show this help
	@grep -hE '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) \
	  | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'

# --- environment -----------------------------------------------------------

.PHONY: env
env: ## Create .env from .env.example if it does not exist
	@if [ ! -f .env ]; then \
	  cp .env.example .env; \
	  echo "created .env from .env.example — placeholder credentials, local use only"; \
	else \
	  echo ".env already exists, leaving it alone"; \
	fi

# --- stack -----------------------------------------------------------------

.PHONY: up
up: env ## Start the full stack and wait for health
	$(COMPOSE) --profile full up -d --wait
	@$(MAKE) --no-print-directory ps

.PHONY: up-infra
up-infra: env ## Start infrastructure only (run services from an IDE)
	$(COMPOSE) --profile infra up -d --wait
	@$(MAKE) --no-print-directory ps

.PHONY: up-core
up-core: env ## Start the minimum viable demo stack
	$(COMPOSE) --profile core up -d --wait
	@$(MAKE) --no-print-directory ps

.PHONY: down
down: ## Stop the stack, keep volumes
	$(COMPOSE) --profile full --profile core --profile infra down --remove-orphans

.PHONY: ps
ps: ## Show container status and health
	@$(COMPOSE) ps --format 'table {{.Service}}\t{{.Status}}\t{{.Ports}}'

.PHONY: logs
logs: ## Tail logs (SERVICE=name to narrow)
	$(COMPOSE) logs -f --tail=100 $(SERVICE)

.PHONY: migrate
migrate: ## Apply database migrations without starting the services
	$(COMPOSE) --profile migrate run --rm flyway-txn
	$(COMPOSE) --profile migrate run --rm flyway-audit

.PHONY: stats
stats: ## One-shot memory and CPU usage per container
	@docker stats --no-stream \
	  --format 'table {{.Name}}\t{{.MemUsage}}\t{{.MemPerc}}\t{{.CPUPerc}}'

# --- build and test --------------------------------------------------------

.PHONY: build
build: ## Compile and package without running tests
	$(MAVEN) -B clean package -DskipTests -DskipITs

.PHONY: test
test: ## Unit tests only (fast inner loop)
	$(MAVEN) -B test

.PHONY: verify
verify: ## Everything CI runs: format, lint, unit + integration tests
	$(MAVEN) -B clean verify

.PHONY: format
format: ## Apply code formatting
	$(MAVEN) -B spotless:apply

# --- demo and performance --------------------------------------------------

.PHONY: demo
demo: ## Run the full demo sequence with narration (Phase 14)
	@if [ -x scripts/demo/run-all.sh ]; then \
	  scripts/demo/run-all.sh; \
	else \
	  echo "demo scripts land in Phase 14 — not yet implemented"; exit 1; \
	fi

.PHONY: perf
perf: ## Run k6 performance scenarios (Phase 12)
	@if [ -d perf ] && [ -n "$$(ls -A perf/*.js 2>/dev/null)" ]; then \
	  k6 run perf/smoke.js; \
	else \
	  echo "k6 scenarios land in Phase 12 — not yet implemented"; exit 1; \
	fi

# --- cleanup ---------------------------------------------------------------

.PHONY: clean
clean: ## Remove build output
	$(MAVEN) -B clean
	@rm -rf frontend/dist frontend/node_modules/.vite

.PHONY: clean-all
clean-all: down ## Remove build output AND volumes — DESTROYS local data
	$(COMPOSE) --profile full --profile core --profile infra --profile migrate \
	  down -v --remove-orphans
	$(MAVEN) -B clean
