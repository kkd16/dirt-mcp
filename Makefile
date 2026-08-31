SHELL := /bin/bash
.SHELLFLAGS := -eu -o pipefail -c
.DEFAULT_GOAL := help

.PHONY: help doctor deps build build-paper build-web package check check-package verify ci format up restart restart-paper restart-web down status health logs console command smoke clean
# Root targets share build outputs and one managed development stack.
.NOTPARALLEL:

help: ## Show the available development commands.
	@awk 'BEGIN { FS = ":.*## "; printf "Dirt MCP development commands:\n\n" } /^[a-zA-Z_-]+:.*## / { printf "  %-20s %s\n", $$1, $$2 }' $(MAKEFILE_LIST)

doctor: ## Verify every tool required by development and CI.
	@. /etc/os-release 2>/dev/null || { printf 'Ubuntu 24.04 x86-64 is required.\n' >&2; exit 1; }; \
	  [[ "$${ID:-}" == ubuntu && "$${VERSION_ID:-}" == 24.04 && "$$(uname -m)" == x86_64 ]] || \
	  { printf 'Ubuntu 24.04 x86-64 is required.\n' >&2; exit 1; }
	@command -v java >/dev/null || { printf 'A Java launcher is required.\n' >&2; exit 1; }
	@command -v node >/dev/null || { printf 'Node.js is required.\n' >&2; exit 1; }
	@command -v pnpm >/dev/null || { printf 'pnpm is required.\n' >&2; exit 1; }
	@command -v overmind >/dev/null || { printf 'Overmind 2.5.1 is required.\n' >&2; exit 1; }
	@command -v tmux >/dev/null || { printf 'tmux is required by Overmind.\n' >&2; exit 1; }
	@command -v curl >/dev/null || { printf 'curl is required.\n' >&2; exit 1; }
	@command -v gzip >/dev/null || { printf 'gzip is required.\n' >&2; exit 1; }
	@command -v tar >/dev/null || { printf 'tar is required.\n' >&2; exit 1; }
	@command -v xz >/dev/null || { printf 'xz is required.\n' >&2; exit 1; }
	@command -v sha256sum >/dev/null || { printf 'sha256sum is required.\n' >&2; exit 1; }
	@command -v systemd-analyze >/dev/null || { printf 'systemd-analyze is required.\n' >&2; exit 1; }
	@command -v shellcheck >/dev/null || { printf 'ShellCheck is required.\n' >&2; exit 1; }
	@command -v actionlint >/dev/null || { printf 'actionlint is required.\n' >&2; exit 1; }
	@if ! ./gradlew -q javaToolchains | grep -Eq 'Language Version:[[:space:]]+25'; then \
	  printf 'Gradle could not resolve the Java 25 toolchain required by Paper.\n' >&2; exit 1; fi
	@node -e 'const manifest = require("./package.json"); const expected = manifest.devEngines.runtime.version; if (process.versions.node !== expected) { console.error(`Expected Node.js $${expected}, found $${process.version}.`); process.exit(1); }'
	@expected="$$(node -p 'require("./package.json").engines.pnpm')"; actual="$$(pnpm --version)"; \
	  [[ "$$actual" == "$$expected" ]] || { printf 'Expected pnpm %s, found %s.\n' "$$expected" "$$actual" >&2; exit 1; }
	@[[ "$$(overmind --version)" == 'Overmind version 2.5.1' ]] || { overmind --version >&2; printf 'Overmind 2.5.1 is required.\n' >&2; exit 1; }
	@printf 'Java launcher: '; java -version 2>&1 | head -n 1
	@printf 'Paper toolchain: Java 25\n'
	@printf 'Node.js: %s\n' "$$(node --version)"
	@printf 'pnpm: %s\n' "$$(pnpm --version)"
	@printf 'Gradle: '; ./gradlew --version | awk '/^Gradle / { print $$2; exit }'
	@printf 'Overmind: %s\n' "$$(overmind --version | awk '{ print $$3 }')"
	@printf 'tmux: %s\n' "$$(tmux -V | awk '{ print $$2 }')"
	@printf 'curl: %s\n' "$$(curl --version | awk 'NR == 1 { print $$2 }')"
	@printf 'gzip: %s\n' "$$(gzip --version | awk 'NR == 1 { print $$2 }')"
	@printf 'systemd: %s\n' "$$(systemd-analyze --version | awk 'NR == 1 { print $$2 }')"
	@printf 'ShellCheck: %s\n' "$$(shellcheck --version | awk '/^version:/ { print $$2 }')"
	@printf 'actionlint: %s\n' "$$(actionlint -version | awk 'NR == 1 { print $$1 }')"

deps: ## Install the locked Node.js dependencies.
	pnpm install --frozen-lockfile

build: build-paper build-web ## Stop the managed stack, then build Paper and web/MCP.

build-paper: down ## Stop the managed stack, then build and validate the Paper plugin artifact.
	./gradlew :paper-plugin:assemble

build-web: deps ## Build the dashboard and web/MCP service.
	pnpm run build

package: build ## Stop the managed stack, then build the native release assets.
	node scripts/package-native.mjs

check: down deps ## Stop the managed stack, then run every offline build, test, lint, format, and contract gate.
	./gradlew build
	pnpm run check

check-package: package ## Stop the managed stack, then validate the native package.
	node scripts/check-native-package.mjs

verify: check smoke ## Run the complete incremental local gate, including live smoke coverage.

ci: doctor clean verify ## Run the clean build, test, and package gate used by CI.
	@node scripts/dev.mjs down
	@$(MAKE) check-package

format: deps ## Apply every repository formatter.
	./gradlew spotlessApply
	pnpm run format

up: ## Start or reuse the complete Paper and web/MCP development stack.
	@node scripts/dev.mjs up

restart: ## Build, then gracefully restart the complete development stack.
	@node scripts/dev.mjs restart

restart-paper: ## Build and safely restart Paper, draining web/MCP first.
	@node scripts/dev.mjs restart-paper

restart-web: ## Build, migrate, and restart only the web/MCP service.
	@node scripts/dev.mjs restart-web

down: ## Stop the complete development stack cleanly.
	@node scripts/dev.mjs down

status: ## Show managed process state and aggregate readiness.
	@node scripts/dev.mjs status

health: ## Run authenticated web, control, Paper, and FAWE readiness checks.
	@node scripts/dev.mjs health

logs: ## Print recent output from both panes or the stopped Paper log.
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
