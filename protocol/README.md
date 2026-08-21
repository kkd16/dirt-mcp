# Dirt bridge protocol

This directory is the versioned contract between the local MCP process and the
Paper plugin. [`openapi.yaml`](openapi.yaml) uses OpenAPI 3.2 and contains only
implemented behavior.

The bridge is authenticated and loopback-only. It covers runtime status,
bounded region and online-player inspection, server-authoritative perspective
block views, FAWE-backed cuboid and palette-based edits, bounded retained edit
history, newest-first history lookup, identity-checked undo, and bounded ordered
Minecraft command dispatch that stops at the first per-command failure.
Unimplemented operations are not included. Server status reports every resolved
MCP tool boolean in `tools` and the active console and detail-log settings in
`logging`; the local MCP process uses the tool snapshot to omit disabled tools
from its agent-facing catalog while bridge routing remains available internally.

The OpenAPI document is authoritative for request and response fields, edit and
call identities, required headers, result variants, command outcomes, and
structured errors. The
[v1 behavior guide](../docs/v1-design.md) explains how those contracts compose
into inspection, edit, recovery, history, undo, and logging workflows.
