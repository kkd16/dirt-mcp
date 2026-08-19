# Dirt MCP TODO

This list tracks the remaining work needed to make the v1 source implementation
consistently documented, thoroughly verified, and ready for release. Items are
ordered by priority.

## 1. Expand Paper and FAWE integration coverage

- Cover per-world locking and `world_busy` behavior.
- Cover bounded and disabled history plus multi-entry undo ordering.
- Verify live authentication failures and change-limit rejection before mutation.
- Verify edit-session and chunk-ticket cleanup on success, failure, interruption,
  and plugin shutdown.

## 2. Strengthen MCP bridge failure coverage

- Add a deterministic request-timeout test without materially slowing the
  offline suite.
- Cover tool hot-reload rollback when a syntactically valid replacement module
  fails partway through registration.

## 3. Enforce contract consistency

- Lint and validate `protocol/openapi.yaml` in CI.
- Add checks that keep Java endpoints and error envelopes, OpenAPI schemas, and
  TypeScript/Zod schemas synchronized.
- Fail CI when implemented behavior is added to only one layer.

## 4. Wire up releases

- Establish one non-snapshot version shared by the Paper plugin, bridge contract,
  and MCP package.
- Publish the Paper JAR through GitHub Releases with checksums.
- Make `@dirt-mcp/server` publishable and publish the matching npm package.
- Add a release workflow that builds, tests, inspects, and publishes artifacts.
- Verify the documented clean installation flow using only published artifacts.
