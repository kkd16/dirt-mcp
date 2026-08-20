# Dirt bridge protocol

This directory is the versioned contract between the local MCP process and the
Paper plugin. [`openapi.yaml`](openapi.yaml) uses OpenAPI 3.2 and contains only
implemented behavior.

The bridge is authenticated and loopback-only. It covers runtime status,
bounded inspection, FAWE-backed cuboid and palette-based edits, bounded retained
edit history, newest-first history lookup, and identity-checked undo.
Unimplemented operations are not included. Server status reports every resolved
MCP tool boolean in `tools` and the active console and detail-log settings in
`logging`; the local MCP process uses the tool snapshot to omit disabled tools
from its agent-facing catalog while bridge routing remains available internally.

Every block-edit response distinguishes `preview`, `no_change`, and `committed`;
only a committed result contains an `EditRecord`. Its fields are `editId`,
`callId`, `operation`, `world`, `worldId`, `bounds`, `changedBlockCount`,
`completedAt`, and the record's last retained `status`. `get_edit_history`
returns only records with live undo data. `undo_edit` accepts the exact newest
`editId`, consumes it only after successful completion, and returns its status
immediately before consumption.

The three block-edit routes and `undo_edit` require a canonical UUIDv4
`X-Dirt-Call-Id` header. History is process-local and bounded by positive
per-world, global-entry, and retained-changed-block settings; recovery-required
records stay visible and retryable. A live edit reserves bounded recovery space
before its first mutation. Structured errors expose `error.editId` whenever an
undo record is retained or rollback is uncertain, and may expose the generated
transaction ID after a finalization failure that was rolled back. Callers
reconcile that ID against history; absence means no undoable record remains.

Paper writes detailed JSON Lines to
`plugins/DirtMCP/logs/dirt-detail.%g.jsonl`, with size and retained-file rotation
reported by server status and retained-file count limited to between two and 100. A file-sink
failure is fail-open and is reported in the concise Paper console. The MCP
process keeps stdout protocol-only and emits
structured JSON Lines to stderr. Applicable call ID, edit ID, operation, world,
outcome, and duration fields correlate both sides; bearer tokens, raw request
bodies, and complete block payloads are never logged. See the
[v1 behavior guide](../docs/v1-design.md) for lifecycle semantics and
[`openapi.yaml`](openapi.yaml) for exact fields and errors.
