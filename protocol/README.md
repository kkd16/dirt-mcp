# Dirt bridge protocol

This directory is the versioned contract between the local MCP process and the
Paper plugin. [`openapi.yaml`](openapi.yaml) uses OpenAPI 3.2 and contains only
implemented behavior.

The current bridge is deliberately loopback-only and exposes an authenticated
end-to-end Dirt/Paper/FAWE ping, lightweight server context, bounded summary and
exact block inspection, sparse orthographic views, FAWE-backed block replacement
and filling, bounded in-memory undo, and ordered operator-level command dispatch.
Server context includes current builds, players, loaded worlds, runtime limits,
and defaults. Unimplemented operations are not included.
