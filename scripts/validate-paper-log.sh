#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
log_file="${repo_root}/.dev/paper/logs/latest.log"

usage() {
  printf 'Usage: %s {running|shutdown START_LINE}\n' "$0" >&2
  exit 2
}

if [[ ! -s "${log_file}" ]]; then
  printf 'Managed Paper log is missing or empty: %s\n' "${log_file}" >&2
  exit 1
fi

mode="${1:-}"
case "${mode}" in
  running)
    [[ $# -eq 1 ]] || usage
    log_content="$(<"${log_file}")"
    ;;
  shutdown)
    [[ $# -eq 2 && "${2}" =~ ^[0-9]+$ ]] || usage
    start_line="$2"
    log_content="$(tail -n "+$((start_line + 1))" "${log_file}")"
    ;;
  *) usage ;;
esac

failure_pattern='\[[^]]+/(ERROR|FATAL)\]:|([[:alnum:]_.]+Exception|[[:alnum:]_.]+Error)(:|$)'
if matches="$(grep -En "${failure_pattern}" <<<"${log_content}" || true)" && [[ -n "${matches}" ]]; then
  printf 'Managed Paper log contains serious failures:\n%s\n' "${matches}" >&2
  exit 1
fi

if [[ "${mode}" == 'running' ]]; then
  if ! grep -Fq '[DirtMCP] Dirt MCP is ready on 127.0.0.1:' <<<"${log_content}"; then
    printf 'Managed Paper log does not contain the Dirt MCP runtime startup marker.\n' >&2
    exit 1
  fi
else
  if ! grep -Fq '[DirtMCP] Dirt MCP stopped' <<<"${log_content}"; then
    printf 'Managed Paper log does not contain the Dirt MCP runtime shutdown marker.\n' >&2
    exit 1
  fi
fi

printf 'Managed Paper %s log validation passed.\n' "${mode}"
