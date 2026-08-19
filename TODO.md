# Dirt MCP TODO

This list tracks the remaining work needed to make the v1 source implementation
consistently documented, thoroughly verified, and ready for release. Items are
ordered by priority.

## 1. Resolve `fill_region` support status

- Reconcile the repository instructions, which describe fill as planned, with
  the README, design, OpenAPI contract, Java bridge, and MCP tool that currently
  expose it.
- Exercise fill, no-op fill, limits, undo, interruption, and cleanup against the
  managed Paper and FAWE server.
- After validation, either designate the complete vertical slice as implemented
  everywhere or remove it from implemented contracts and documentation until it
  is ready.

## 2. Automate Paper and FAWE integration coverage

- Add repeatable live coverage for inspection, replacement, undo, lifecycle,
  authentication, and failure paths.
- Incorporate the managed-server smoke coverage into a suitable release or CI
  gate without creating disposable Paper installations.
- Verify clean startup, shutdown, and restart logs around plugin changes.

## 3. Test the real FAWE editor directly

- Cover per-world locking and `world_busy` behavior.
- Cover history limits, disabled history, no-op edits, and undo ordering.
- Verify change limits before mutation and exact changed-block counts.
- Verify edit-session and chunk-ticket cleanup on success, failure, interruption,
  and plugin shutdown.

## 4. Complete MCP forwarding coverage

- Add successful request and response tests for `count_region_block_states`,
  `replace_region_blocks`, `fill_region` once supported, and
  `undo_last_dirt_edit`.
- Cover malformed bridge responses, timeouts, unavailable bridges, and every
  structured bridge error used by the MCP tools.

## 5. Enforce contract consistency

- Lint and validate `protocol/openapi.yaml` in CI.
- Add checks that keep Java endpoints and error envelopes, OpenAPI schemas, and
  TypeScript/Zod schemas synchronized.
- Fail CI when implemented behavior is added to only one layer.

## 6. Wire up releases

- Establish one non-snapshot version shared by the Paper plugin, bridge contract,
  and MCP package.
- Publish the Paper JAR through GitHub Releases with checksums.
- Make `@dirt-mcp/server` publishable and publish the matching npm package.
- Add a release workflow that builds, tests, inspects, and publishes artifacts.
- Verify the documented clean installation flow using only published artifacts.

Features explicitly outside v1—such as remote transport, renderers, schematics,
terrain systems, persistent jobs, databases, a web UI, Docker, and permission or
land-policy integrations—are not TODO items unless the product scope changes.
