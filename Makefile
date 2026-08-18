SHELL := /bin/bash
.SHELLFLAGS := -eu -o pipefail -c
.DEFAULT_GOAL := help

MC_PORT ?= 25566
BRIDGE_PORT ?= 8765
DEV_TOKEN_FILE := paper-plugin/run/.dirt-mcp-token

.PHONY: help doctor install build build-java build-mcp dev-build check ci dev-token up reload down status logs console command smoke-fill mcp health clean

help: ## Show the available development commands.
	@awk 'BEGIN { FS = ":.*## "; printf "Dirt MCP development commands:\n\n" } /^[a-zA-Z_-]+:.*## / { printf "  %-12s %s\n", $$1, $$2 }' $(MAKEFILE_LIST)
	@printf '\nOverrides: MC_PORT=%s BRIDGE_PORT=%s\n' "$(MC_PORT)" "$(BRIDGE_PORT)"

doctor: ## Verify the required Java, Node.js, npm, Gradle, curl, and tmux tools.
	@command -v java >/dev/null || { printf 'Java 25 is required.\n' >&2; exit 1; }
	@command -v node >/dev/null || { printf 'Node.js 24 LTS or newer is required.\n' >&2; exit 1; }
	@command -v npm >/dev/null || { printf 'npm is required.\n' >&2; exit 1; }
	@command -v curl >/dev/null || { printf 'curl is required.\n' >&2; exit 1; }
	@command -v tmux >/dev/null || { printf 'tmux is required for the managed development server.\n' >&2; exit 1; }
	@if ! ./gradlew -q javaToolchains | grep -Eq 'Language Version:[[:space:]]+25'; then \
	  printf 'Gradle could not resolve the Java 25 toolchain required by Paper 26.2.\n' >&2; exit 1; fi
	@node_major="$$(node -p "process.versions.node.split('.')[0]")"; \
	  if (( node_major < 24 )); then printf 'Expected Node.js 24 or newer, found Node.js %s.\n' "$$(node --version)" >&2; exit 1; fi
	@printf 'Java launcher: '; java -version 2>&1 | head -n 1
	@printf 'Paper toolchain: Java 25\n'
	@printf 'Node.js: %s\n' "$$(node --version)"
	@printf 'npm: %s\n' "$$(npm --version)"
	@printf 'Gradle: '; ./gradlew --version | awk '/^Gradle / { print $$2; exit }'

install: doctor ## Install the locked Node.js dependencies.
	npm ci

build: build-java build-mcp ## Build the Paper plugin and MCP server.

build-java: ## Compile and test the Paper plugin.
	./gradlew build

build-mcp: ## Compile the MCP server.
	npm run build

dev-build: ## Incrementally compile the Paper plugin and MCP server without tests.
	./gradlew :paper-plugin:jar
	npm run build

check: ## Run all static checks and Java tests.
	./gradlew check
	npm run check
	npm run build
	npm test

ci: ## Reproduce the clean continuous-integration build.
	npm ci
	./gradlew --no-daemon clean build
	npm run check
	npm run build
	npm test

dev-token: ## Create the ignored bearer token used by local development.
	@mkdir -p "$(dir $(DEV_TOKEN_FILE))"
	@if [[ ! -s "$(DEV_TOKEN_FILE)" ]]; then \
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
	  $(MAKE) --no-print-directory install; \
	  $(MAKE) --no-print-directory build; \
	  scripts/dev-paper up "$(MC_PORT)" "$(BRIDGE_PORT)"; \
	fi

reload: ## Rebuild and gracefully restart the managed development server.
	@$(MAKE) --no-print-directory dev-build
	@scripts/dev-paper restart

down: dev-token ## Stop the managed development server cleanly.
	@scripts/dev-paper down

status: dev-token ## Report managed Paper process and bridge health.
	@scripts/dev-paper status

logs: dev-token ## Print recent managed Paper console output (override with LINES=...).
	@scripts/dev-paper logs "$(or $(LINES),100)"

console: dev-token ## Attach to the managed Paper console; detach with Ctrl-b d.
	@scripts/dev-paper console

command: export DIRT_MCP_DEV_COMMAND := $(value CMD)
command: dev-token ## Send one Paper console command with CMD='...'.
	@test -n "$$DIRT_MCP_DEV_COMMAND" || { printf 'Usage: make command CMD='\''version'\''\n' >&2; exit 2; }
	@scripts/dev-paper command "$$DIRT_MCP_DEV_COMMAND"

smoke-fill: dev-token ## Exercise fill, no-op, undo, and exact restoration on the managed server.
	@node scripts/smoke-fill-region.mjs

mcp: build-mcp dev-token ## Run the MCP stdio server for an MCP host.
	@scripts/run-dirt-mcp

health: dev-token ## Query the running Paper bridge health endpoint.
	@scripts/dev-paper health

clean: ## Remove generated build outputs; preserve the local Paper world.
	./gradlew clean
	npm run clean
