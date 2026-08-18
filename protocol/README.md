# Dirt bridge protocol

This directory is the versioned contract between the local MCP process and the
Paper plugin. [`openapi.yaml`](openapi.yaml) uses OpenAPI 3.2 and contains only
implemented behavior.

The current bridge is deliberately loopback-only and exposes one authenticated
health endpoint. The planned world surface is not added here until its complete
vertical slice is implemented.
