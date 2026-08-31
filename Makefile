SHELL := /bin/bash
.SHELLFLAGS := -eu -o pipefail -c
.DEFAULT_GOAL := help

.PHONY: help doctor install build build-paper build-web check check-production verify ci format up restart restart-paper restart-web down status health logs console command smoke clean
.NOTPARALLEL: verify ci smoke restart restart-paper restart-web

help: ## Show the available development commands.
	@awk 'BEGIN { FS = ":.*## "; printf "Dirt MCP development commands:\n\n" } /^[a-zA-Z_-]+:.*## / { printf "  %-20s %s\n", $$1, $$2 }' $(MAKEFILE_LIST)

doctor: ## Verify every tool required by development and CI.
	@command -v java >/dev/null || { printf 'A Java launcher is required.\n' >&2; exit 1; }
	@command -v node >/dev/null || { printf 'Node.js is required.\n' >&2; exit 1; }
	@command -v pnpm >/dev/null || { printf 'pnpm is required.\n' >&2; exit 1; }
	@command -v overmind >/dev/null || { printf 'Overmind 2.5.1 is required.\n' >&2; exit 1; }
	@command -v tmux >/dev/null || { printf 'tmux is required by Overmind.\n' >&2; exit 1; }
	@command -v docker >/dev/null || { printf 'Docker with Compose is required.\n' >&2; exit 1; }
	@command -v shellcheck >/dev/null || { printf 'ShellCheck 0.9.0 or newer is required.\n' >&2; exit 1; }
	@command -v actionlint >/dev/null || { printf 'actionlint 1.7.12 or newer is required.\n' >&2; exit 1; }
	@docker compose version >/dev/null || { printf 'The Docker Compose plugin is required.\n' >&2; exit 1; }
	@docker info >/dev/null 2>&1 || { printf 'The Docker daemon is not available.\n' >&2; exit 1; }
	@if ! ./gradlew -q javaToolchains | grep -Eq 'Language Version:[[:space:]]+25'; then \
	  printf 'Gradle could not resolve the Java 25 toolchain required by Paper.\n' >&2; exit 1; fi
	@node -e 'const manifest = require("./package.json"); const expected = manifest.devEngines.runtime.version; if (process.versions.node !== expected) { console.error(`Expected Node.js $${expected}, found $${process.version}.`); process.exit(1); }'
	@expected="$$(node -p 'require("./package.json").devEngines.packageManager.version')"; actual="$$(pnpm --version)"; \
	  [[ "$$actual" == "$$expected" ]] || { printf 'Expected pnpm %s, found %s.\n' "$$expected" "$$actual" >&2; exit 1; }
	@[[ "$$(overmind --version)" == 'Overmind version 2.5.1' ]] || { overmind --version >&2; printf 'Overmind 2.5.1 is required.\n' >&2; exit 1; }
	@shellcheck_version="$$(shellcheck --version | awk '/^version:/ { print $$2 }')"; \
	  if [[ "$$(printf '%s\n%s\n' 0.9.0 "$$shellcheck_version" | sort -V | head -n 1)" != 0.9.0 ]]; then \
	    printf 'Expected ShellCheck 0.9.0 or newer, found %s.\n' "$$shellcheck_version" >&2; exit 1; fi
	@actionlint_version="$$(actionlint -version | awk 'NR == 1 { print $$1 }')"; \
	  if [[ "$$(printf '%s\n%s\n' 1.7.12 "$$actionlint_version" | sort -V | head -n 1)" != 1.7.12 ]]; then \
	    printf 'Expected actionlint 1.7.12 or newer, found %s.\n' "$$actionlint_version" >&2; exit 1; fi
	@printf 'Java launcher: '; java -version 2>&1 | head -n 1
	@printf 'Paper toolchain: Java 25\n'
	@printf 'Node.js: %s\n' "$$(node --version)"
	@printf 'pnpm: %s\n' "$$(pnpm --version)"
	@printf 'Gradle: '; ./gradlew --version | awk '/^Gradle / { print $$2; exit }'
	@printf 'Overmind: %s\n' "$$(overmind --version | awk '{ print $$3 }')"
	@printf 'tmux: %s\n' "$$(tmux -V | awk '{ print $$2 }')"
	@printf 'Docker Compose: %s\n' "$$(docker compose version --short)"
	@printf 'ShellCheck: %s\n' "$$(shellcheck --version | awk '/^version:/ { print $$2 }')"
	@printf 'actionlint: %s\n' "$$(actionlint -version | awk 'NR == 1 { print $$1 }')"

install: ## Install the locked Node.js dependencies.
	pnpm install --frozen-lockfile

build: build-paper build-web ## Build the Paper plugin and web/MCP service.

build-paper: ## Build and validate the Paper plugin artifact.
	./gradlew :paper-plugin:assemble

build-web: install ## Build the dashboard and web/MCP service.
	pnpm run build

check: install ## Run every offline build, test, lint, format, and contract gate.
	./gradlew build
	pnpm run check

check-production: export DIRT_AUTH_SECRET_FILE := /dev/null
check-production: export DIRT_BRIDGE_TOKEN_FILE := /dev/null
check-production: export DIRT_CONTROL_TOKEN_FILE := /dev/null
check-production: export DIRT_PUBLIC_HOST := dirt.example.com
check-production: ## Validate every production Compose profile and image.
	docker compose --profile '*' config --quiet
	docker compose build --pull
	docker run --rm --entrypoint /bin/sh dirt-mcp-web:local -c 'test -r /app/LICENSE'
	docker compose run --rm --no-deps caddy caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile

verify: check smoke ## Run the complete incremental local gate, including live smoke coverage.

ci: doctor clean verify check-production ## Run the clean complete gate used by continuous integration.
	@node scripts/dev.mjs down

format: install ## Apply every repository formatter.
	./gradlew spotlessApply
	pnpm run format

up: ## Start or reuse the complete Paper and web/MCP development stack.
	@node scripts/dev.mjs up

restart: build ## Build, then gracefully restart the complete development stack.
	@node scripts/dev.mjs restart

restart-paper: build-paper ## Build and safely restart Paper, draining web/MCP first.
	@node scripts/dev.mjs restart-paper

restart-web: build-web ## Build, migrate, and restart only the web/MCP service.
	@node scripts/dev.mjs restart-web

down: ## Stop the complete development stack cleanly.
	@node scripts/dev.mjs down

status: ## Show managed process state and aggregate readiness.
	@node scripts/dev.mjs status

health: ## Run authenticated web, control, Paper, and FAWE readiness checks.
	@node scripts/dev.mjs health

logs: ## Print both live panes or the stopped Paper log.
	@node scripts/dev.mjs logs

console: ## Attach to the managed Paper console; detach with Ctrl-b d.
	@node scripts/dev.mjs console

command: ## Prompt for or read one Paper console command from standard input.
	@node scripts/dev.mjs command

smoke: restart ## Run the complete managed-server integration gate.
	@node scripts/smoke-managed-server.mjs || { node scripts/dev.mjs logs >&2; exit 1; }
	@scripts/validate-paper-log.sh running

clean: down ## Stop services and remove build outputs while preserving .dev data.
	./gradlew clean
	pnpm run clean
