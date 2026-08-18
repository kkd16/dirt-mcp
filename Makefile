SHELL := /bin/bash
.SHELLFLAGS := -eu -o pipefail -c
.DEFAULT_GOAL := help

MC_HOST ?= 0.0.0.0
MC_PORT ?= 25566
BRIDGE_PORT ?= 8765
BRIDGE_URL ?= http://127.0.0.1:$(BRIDGE_PORT)

.PHONY: help doctor install build build-java build-mcp check test ci up dev mcp inspect health clean

help: ## Show the available development commands.
	@awk 'BEGIN { FS = ":.*## "; printf "Dirt MCP development commands:\n\n" } /^[a-zA-Z_-]+:.*## / { printf "  %-12s %s\n", $$1, $$2 }' $(MAKEFILE_LIST)
	@printf '\nOverrides: MC_HOST=%s MC_PORT=%s BRIDGE_PORT=%s BRIDGE_URL=%s\n' "$(MC_HOST)" "$(MC_PORT)" "$(BRIDGE_PORT)" "$(BRIDGE_URL)"

doctor: ## Verify the required Java, Node.js, npm, and wrapper tools.
	@command -v java >/dev/null || { printf 'Java 25 is required.\n' >&2; exit 1; }
	@command -v node >/dev/null || { printf 'Node.js 24 LTS or newer is required.\n' >&2; exit 1; }
	@command -v npm >/dev/null || { printf 'npm is required.\n' >&2; exit 1; }
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

check: ## Run all static checks and Java tests.
	./gradlew check
	npm run check

test: check ## Alias for the complete test/check suite.

ci: ## Reproduce the clean continuous-integration build.
	npm ci
	./gradlew --no-daemon clean build
	npm run check
	npm run build

up: ## Build and run the full local Paper development stack in the foreground.
	@$(MAKE) --no-print-directory install
	@$(MAKE) --no-print-directory build
	@printf 'Starting Paper on %s:%s with the bridge on %s. Type "stop" to shut it down.\n' "$(MC_HOST)" "$(MC_PORT)" "$(BRIDGE_URL)"
	@env PAPER_EULA=true \
	  DIRT_MCP_DEV_HOST="$(MC_HOST)" \
	  DIRT_MCP_DEV_PORT="$(MC_PORT)" \
	  DIRT_MCP_BRIDGE_ENABLED=true \
	  DIRT_MCP_BRIDGE_PORT="$(BRIDGE_PORT)" \
	  ./gradlew :paper-plugin:runServer

dev: up ## Alias for make up.

mcp: build-mcp ## Run the MCP stdio server for an MCP host.
	@env DIRT_MCP_BRIDGE_URL="$(BRIDGE_URL)" npm run start:mcp

inspect: ## Build and open MCP Inspector against the stdio server.
	@env DIRT_MCP_BRIDGE_URL="$(BRIDGE_URL)" npm run inspect:mcp

health: ## Query the running Paper bridge health endpoint.
	curl --fail --silent --show-error "$(BRIDGE_URL)/v1/health"
	@printf '\n'

clean: ## Remove generated build outputs; preserve the local Paper world.
	./gradlew clean
	npm run clean
