# Dirt bridge protocol

This directory is the versioned contract between the local MCP process and the
Paper plugin. [`openapi.yaml`](openapi.yaml) uses OpenAPI 3.2 and contains only
implemented behavior.

The current bridge is deliberately loopback-only and exposes authenticated
health and bounded region-inspection endpoints. Mutation operations are not
added here until their complete vertical slices are implemented.
