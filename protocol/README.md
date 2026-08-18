# Dirt bridge protocol

This directory is the versioned contract between the local MCP process and the
Paper plugin. [`openapi.yaml`](openapi.yaml) uses OpenAPI 3.2 and contains only
implemented behavior.

The current bridge is deliberately loopback-only and exposes authenticated
health, bounded region inspection, FAWE-backed block replacement and filling,
and bounded in-memory undo. Unimplemented operations are not included.
