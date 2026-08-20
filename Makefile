SHELL := /bin/bash
.SHELLFLAGS := -eu -o pipefail -c
.DEFAULT_GOAL := help

MC_PORT ?= 25566
BRIDGE_PORT ?= 8765
DEV_TOKEN_FILE := paper-plugin/run/.dirt-mcp-token

.PHONY: help doctor install node-deps build build-java build-mcp dev-build paper-runtime check verify ci format dev-token up reload down status logs console command smoke mcp health clean

help: ## Show the available development commands.
	@awk 'BEGIN { FS = ":.*## "; printf "Dirt MCP development commands:\n\n" } /^[a-zA-Z_-]+:.*## / { printf "  %-14s %s\n", $$1, $$2 }' $(MAKEFILE_LIST)
	@printf '\nNew managed-server ports: MC_PORT=%s BRIDGE_PORT=%s; existing and reloaded servers keep saved ports.\n' "$(MC_PORT)" "$(BRIDGE_PORT)"

doctor: ## Verify the required Java, Node.js, pnpm, Gradle, curl, tmux, and lint tools.
	@command -v java >/dev/null || { printf 'Java 25 is required.\n' >&2; exit 1; }
	@command -v jar >/dev/null || { printf 'The Java 25 JDK jar tool is required.\n' >&2; exit 1; }
	@command -v node >/dev/null || { printf 'Node.js 26 or newer is required.\n' >&2; exit 1; }
	@command -v pnpm >/dev/null || { printf 'pnpm 11.22.0 or a newer 11.x release is required.\n' >&2; exit 1; }
	@command -v curl >/dev/null || { printf 'curl is required.\n' >&2; exit 1; }
	@command -v tmux >/dev/null || { printf 'tmux is required for the managed development server.\n' >&2; exit 1; }
	@command -v shellcheck >/dev/null || { printf 'ShellCheck 0.9.0 or newer is required.\n' >&2; exit 1; }
	@command -v actionlint >/dev/null || { printf 'actionlint 1.7.12 or newer is required.\n' >&2; exit 1; }
	@if ! ./gradlew -q javaToolchains | grep -Eq 'Language Version:[[:space:]]+25'; then \
	  printf 'Gradle could not resolve the Java 25 toolchain required by Paper 26.2.\n' >&2; exit 1; fi
	@node_major="$$(node -p "process.versions.node.split('.')[0]")"; \
	  if (( node_major < 26 )); then printf 'Expected Node.js 26 or newer, found Node.js %s.\n' "$$(node --version)" >&2; exit 1; fi
	@pnpm_version="$$(pnpm --version)"; \
	  if [[ "$$pnpm_version" =~ ^11\.([0-9]+)\.([0-9]+)$$ ]]; then \
	    pnpm_minor="$${BASH_REMATCH[1]}"; \
	  else \
	    pnpm_minor=-1; \
	  fi; \
	  if (( pnpm_minor < 22 )); then \
	    printf 'Expected pnpm 11.22.0 or a newer 11.x release, found pnpm %s.\n' "$$pnpm_version" >&2; exit 1; fi
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
	@printf 'ShellCheck: %s\n' "$$(shellcheck --version | awk '/^version:/ { print $$2 }')"
	@printf 'actionlint: %s\n' "$$(actionlint -version | awk 'NR == 1 { print $$1 }')"

install: node-deps ## Install the locked Node.js dependencies.

node-deps:
	pnpm install --frozen-lockfile

build: build-java build-mcp ## Build the Paper plugin and MCP server.

build-java: ## Compile and test the Paper plugin.
	./gradlew build
	@scripts/validate-paper-jar

build-mcp: node-deps ## Compile the MCP server.
	pnpm run build

dev-build: node-deps paper-runtime ## Incrementally compile the Paper plugin and MCP server without tests.
	pnpm run build

paper-runtime:
	./gradlew :paper-plugin:jar
	@scripts/validate-paper-jar

check: node-deps ## Run every offline build, test, lint, format, and validation gate.
	./gradlew build
	@scripts/validate-paper-jar
	pnpm run check

verify: ## Run the complete incremental local gate, including managed Paper smoke coverage.
	@$(MAKE) --no-print-directory check
	@$(MAKE) --no-print-directory smoke

ci: doctor ## Run the clean complete gate used by continuous integration.
	pnpm clean
	@$(MAKE) --no-print-directory clean
	@$(MAKE) --no-print-directory verify

format: node-deps ## Apply the repository's Java, TypeScript, and configuration formatters.
	./gradlew spotlessApply
	pnpm run format

dev-token: ## Create or repair the ignored local bearer token and its permissions.
	@mkdir -p "$(dir $(DEV_TOKEN_FILE))"
	@token=''; \
	  if [[ -f "$(DEV_TOKEN_FILE)" ]]; then token="$$(<"$(DEV_TOKEN_FILE)")"; fi; \
	  if [[ ! "$$token" =~ ^[0-9a-f]{64}$$ ]]; then \
	  umask 077; \
	  node -e "process.stdout.write(require('node:crypto').randomBytes(32).toString('hex'))" > "$(DEV_TOKEN_FILE)"; \
	  printf 'Generated local Dirt MCP token at %s.\n' "$(DEV_TOKEN_FILE)" >&2; \
	fi
	@chmod 600 "$(DEV_TOKEN_FILE)"

up: ## Start the managed Paper development server and wait until it is ready.
	@$(MAKE) --no-print-directory dev-token
	@if scripts/dev-paper status >/dev/null 2>&1; then \
	  scripts/dev-paper status; \
	else \
	  $(MAKE) --no-print-directory dev-build; \
	  scripts/dev-paper up "$(MC_PORT)" "$(BRIDGE_PORT)"; \
	fi

reload: ## Rebuild and gracefully restart the managed development server.
	@$(MAKE) --no-print-directory dev-token
	@$(MAKE) --no-print-directory dev-build
	@scripts/dev-paper restart "$(MC_PORT)" "$(BRIDGE_PORT)"

down: ## Stop the managed development server cleanly.
	@scripts/dev-paper down

status: ## Report managed Paper process and bridge health.
	@scripts/dev-paper status

logs: ## Print recent managed Paper console output (override with LINES=...).
	@scripts/dev-paper logs "$(or $(LINES),100)"

console: ## Attach to the managed Paper console; detach with Ctrl-b d.
	@scripts/dev-paper console

command: export DIRT_MCP_DEV_COMMAND := $(value CMD)
command: ## Send one Paper console command with CMD='...'.
	@test -n "$$DIRT_MCP_DEV_COMMAND" || { printf 'Usage: make command CMD='\''version'\''\n' >&2; exit 2; }
	@scripts/dev-paper command "$$DIRT_MCP_DEV_COMMAND"

smoke: ## Restart Paper and run the complete managed-server integration gate.
	@$(MAKE) --no-print-directory dev-token
	@$(MAKE) --no-print-directory paper-runtime
	@scripts/dev-paper restart "$(MC_PORT)" "$(BRIDGE_PORT)"
	@node scripts/smoke-managed-server.mjs || { \
	  scripts/dev-paper logs 120 >&2; \
	  exit 1; \
	}
	@scripts/validate-paper-log running

mcp: ## Run the built MCP stdio server for an MCP host.
	@scripts/run-dirt-mcp

health: ## Run the authenticated end-to-end Dirt/Paper/FAWE ping.
	@scripts/dev-paper health

clean: ## Remove generated build outputs; preserve the local Paper world.
	./gradlew clean
	$(RM) -r mcp-server/dist
