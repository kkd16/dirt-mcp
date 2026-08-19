# Dirt bridge protocol

This directory is the versioned contract between the local MCP process and the
Paper plugin. [`openapi.yaml`](openapi.yaml) uses OpenAPI 3.2 and contains only
implemented behavior.

The bridge is authenticated and loopback-only. It covers runtime status, bounded
inspection, FAWE-backed cuboid and sparse edits, in-memory undo, and ordered
operator-level command dispatch. Unimplemented operations are not included.
