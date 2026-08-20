# Dirt bridge protocol

This directory is the versioned contract between the local MCP process and the
Paper plugin. [`openapi.yaml`](openapi.yaml) uses OpenAPI 3.2 and contains only
implemented behavior.

The bridge is authenticated and loopback-only. It covers runtime status,
bounded inspection, FAWE-backed cuboid and palette-based edits, bounded retained
edit history, newest-first history lookup, and identity-checked undo.
Unimplemented operations are not included.

Every block-edit response distinguishes `preview`, `no_change`, and `committed`;
only a committed result contains an `EditRecord`. Its fields are `editId`,
`callId`, `operation`, `world`, `worldId`, `bounds`, `changedBlockCount`,
`completedAt`, and `status`. `get_edit_history` returns only records with live
undo data. `undo_edit` accepts the exact newest `editId` and consumes it only
after successful completion.

The three block-edit routes and `undo_edit` require a canonical UUIDv4
`X-Dirt-Call-Id` header. History is process-local and bounded by positive
per-world, global-entry, and retained-changed-block settings; recovery-required
records stay visible and retryable. A live edit reserves bounded recovery space
before its first mutation. Errors expose `error.editId` when a mutation may
remain or an undo record is retained. See the
[v1 behavior guide](../docs/v1-design.md) for lifecycle semantics and
[`openapi.yaml`](openapi.yaml) for exact fields and errors.
