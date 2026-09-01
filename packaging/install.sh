#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

if [[ "${DIRT_INSTALL_DIAGNOSTICS:-}" == true ]]; then
  trap 'printf "Installer failed in %s at line %s.\n" "${FUNCNAME[0]:-main}" "${LINENO}" >&2' ERR
fi

readonly release_version='@VERSION@'
readonly supported_paper_version='@PAPER_VERSION@'
readonly supported_paper_build='@PAPER_BUILD@'
readonly plugin_sha256='@PLUGIN_SHA256@'
readonly fawe_version='2.15.4'
readonly fawe_filename='FastAsyncWorldEdit-Paper-2.15.4.jar'
readonly fawe_url='https://cdn.modrinth.com/data/z4HZZnLr/versions/5TOYHuQr/FastAsyncWorldEdit-Paper-2.15.4.jar'
readonly fawe_sha512='f623a5729aed386c5aec0cc5a51f01a6362d452b020851bd8e17bb9df66b9abaa217b07a4fbf3ee084ddea202824843390e02486f6eea5640624e6ab6bcc81b8'
readonly caddy_key_url='https://dl.cloudsmith.io/public/caddy/stable/gpg.155B6D79CA56EA34.key'
readonly caddy_key_fingerprint='65760C51EDEA2017CEA2CA15155B6D79CA56EA34'
readonly caddy_repository_entry='deb [signed-by=/usr/share/keyrings/caddy-stable-archive-keyring.gpg] https://dl.cloudsmith.io/public/caddy/stable/deb/debian any-version main'
readonly application_directory='/opt/dirt-mcp'
readonly configuration_directory='/etc/dirt-mcp'
readonly credentials_directory='/etc/dirt-mcp/credentials'
readonly service_unit='/etc/systemd/system/dirt-mcp.service'
readonly service_user='dirt-mcp'
bundle_directory=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
readonly bundle_directory

paper_directory=''
public_hostname=''
temporary_directory=''
application_staging_directory=''
configuration_staging_directory=''
resume_installation=false
regenerate_paper_credentials=false
paper_owner=''
paper_group=''

usage() {
  cat <<'EOF'
Usage: install.sh --paper-dir ABSOLUTE_PATH --hostname FQDN

Install Dirt MCP on Ubuntu Server 24.04 x86-64 beside an existing Paper server.

Options:
  --paper-dir ABSOLUTE_PATH  Existing Paper server root
  --hostname FQDN           Permanent public Dirt hostname
  --help                    Show this help
EOF
}

fail() {
  printf 'Dirt MCP installation failed: %s\n' "$1" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "$1 is required"
}

read_arguments() {
  while (($# > 0)); do
    case "$1" in
      --paper-dir)
        (($# >= 2)) || fail '--paper-dir requires a value'
        [[ -z "${paper_directory}" ]] || fail '--paper-dir may be supplied only once'
        paper_directory=$2
        shift 2
        ;;
      --hostname)
        (($# >= 2)) || fail '--hostname requires a value'
        [[ -z "${public_hostname}" ]] || fail '--hostname may be supplied only once'
        public_hostname=${2,,}
        shift 2
        ;;
      --help)
        usage
        exit 0
        ;;
      *)
        fail "unknown argument: $1"
        ;;
    esac
  done
  [[ -n "${paper_directory}" ]] || fail '--paper-dir is required'
  [[ -n "${public_hostname}" ]] || fail '--hostname is required'
}

validate_hostname() {
  local label
  local -a labels
  ((${#public_hostname} <= 253)) || fail '--hostname is too long'
  [[ "${public_hostname}" != 'localhost' && "${public_hostname}" != *'.' ]] ||
    fail '--hostname must be a bare multi-label DNS name'
  [[ ! "${public_hostname}" =~ ^[0-9.]+$ ]] ||
    fail '--hostname must be a DNS name, not an IPv4 address'
  IFS='.' read -r -a labels <<<"${public_hostname}"
  ((${#labels[@]} >= 2)) || fail '--hostname must be a bare multi-label DNS name'
  for label in "${labels[@]}"; do
    ((${#label} >= 1 && ${#label} <= 63)) || fail '--hostname contains an invalid DNS label'
    [[ "${label}" =~ ^[a-z0-9]([a-z0-9-]*[a-z0-9])?$ ]] ||
      fail '--hostname contains an invalid DNS label'
  done
  "${bundle_directory}/node/bin/node" --input-type=module --eval '
    import { isIP } from "node:net";
    const hostname = process.argv[1];
    const origin = "https://" + hostname;
    const url = URL.parse(origin);
    process.exit(
      url !== null && url.hostname === hostname && url.origin === origin && isIP(url.hostname) === 0 ? 0 : 1,
    );
  ' "${public_hostname}" || fail '--hostname must be a canonical DNS name, not an IP address'
}

validate_host() {
  local os_id
  local os_version
  [[ ${EUID} -eq 0 ]] || fail 'run this installer with sudo'
  [[ -r /etc/os-release ]] || fail 'Ubuntu 24.04 is required'
  os_id=$(sed -n 's/^ID=//p' /etc/os-release | tr -d '"')
  os_version=$(sed -n 's/^VERSION_ID=//p' /etc/os-release | tr -d '"')
  [[ "${os_id}" == 'ubuntu' && "${os_version}" == '24.04' && "$(uname -m)" == 'x86_64' ]] ||
    fail 'Ubuntu 24.04 x86-64 is required'
  [[ "$(ps -p 1 -o comm= | xargs)" == 'systemd' ]] || fail 'systemd must be PID 1'
  systemctl is-system-running --quiet ||
    [[ "$(systemctl is-system-running 2>/dev/null)" == 'degraded' ]] ||
    fail 'the systemd system manager is not running'
}

validate_bundle() {
  local bundled_plugin_hash
  local relative
  local -a required_files=(
    'Caddyfile.in'
    'LICENSE'
    'app/dist/backup.js'
    'app/dist/index.js'
    'app/dist/init-db.js'
    'dirt-mcp.service.in'
    'node/bin/node'
    'paper-plugin.jar'
  )
  for relative in "${required_files[@]}"; do
    [[ -f "${bundle_directory}/${relative}" && ! -L "${bundle_directory}/${relative}" ]] ||
      fail "release bundle is missing ${relative}"
  done
  [[ -x "${bundle_directory}/node/bin/node" ]] || fail 'bundled Node runtime is not executable'
  bundled_plugin_hash=$(sha256sum "${bundle_directory}/paper-plugin.jar" | awk '{print $1}')
  [[ "${bundled_plugin_hash}" == "${plugin_sha256}" ]] || fail 'bundled Paper plugin checksum is invalid'
}

validate_paper() {
  local current_build
  local current_version
  local online_mode
  local paper_release
  [[ "${paper_directory}" == /* ]] || fail '--paper-dir must be an absolute path'
  [[ "${paper_directory}" != *$'\n'* && "${paper_directory}" != *$'\r'* ]] ||
    fail '--paper-dir contains an unsupported line break'
  paper_directory=$(realpath -e -- "${paper_directory}")
  [[ "${paper_directory}" != / ]] || fail '--paper-dir may not be the filesystem root'
  local managed_path
  for managed_path in "${application_directory}" "${configuration_directory}" /var/lib/dirt-mcp; do
    [[ "${managed_path}" != "${paper_directory}" &&
      "${managed_path}" != "${paper_directory}/"* ]] ||
      fail '--paper-dir may not contain Dirt MCP service paths'
  done
  [[ -d "${paper_directory}/plugins" && ! -L "${paper_directory}/plugins" ]] ||
    fail 'Paper plugins/ must be a real directory'
  [[ -f "${paper_directory}/server.properties" && ! -L "${paper_directory}/server.properties" ]] ||
    fail 'Paper server.properties must be a real file'
  [[ -f "${paper_directory}/.paper/version_history.json" &&
    ! -L "${paper_directory}/.paper/version_history.json" ]] ||
    fail 'Paper .paper/version_history.json is missing; start Paper once before installing Dirt'
  online_mode=$(awk '
    /^[[:space:]]*#/ { next }
    /^[[:space:]]*online-mode[[:space:]]*=/ {
      value=$0
      sub(/^[^=]*=/, "", value)
      gsub(/^[[:space:]]+|[[:space:]\r]+$/, "", value)
    }
    END { print value }
  ' "${paper_directory}/server.properties")
  [[ "${online_mode}" == 'true' ]] || fail 'Paper must use online-mode=true'
  paper_release=$("${bundle_directory}/node/bin/node" --input-type=module --eval '
    import { readFileSync } from "node:fs";
    const value = JSON.parse(readFileSync(process.argv[1], "utf8")).currentVersion;
    const match = typeof value === "string" ? /-(\d+)-[^ ]+ \(MC: ([^)]+)\)$/.exec(value) : null;
    if (match === null) process.exit(1);
    process.stdout.write(match[2] + " " + match[1]);
  ' "${paper_directory}/.paper/version_history.json") || fail 'Paper version history is invalid'
  read -r current_version current_build <<<"${paper_release}"
  [[ "${current_version}" == "${supported_paper_version}" &&
    "${current_build}" == "${supported_paper_build}" ]] ||
    fail "Paper ${supported_paper_version} build ${supported_paper_build} is required; found ${current_version} build ${current_build}"

  paper_owner=$(stat -c '%U' "${paper_directory}/plugins")
  paper_group=$(stat -c '%G' "${paper_directory}/plugins")
  [[ -n "${paper_owner}" && "${paper_owner}" != 'root' && "${paper_owner}" != 'UNKNOWN' ]] ||
    fail 'Paper plugins/ must be owned by a non-root system account'
  id "${paper_owner}" >/dev/null 2>&1 || fail 'Paper plugins/ owner is not a local account'
  [[ -n "${paper_group}" && "${paper_group}" != 'UNKNOWN' ]] ||
    fail 'Paper plugins/ group is not a local group'
  getent group "${paper_group}" >/dev/null || fail 'Paper plugins/ group is not a local group'

  local plugin_data="${paper_directory}/plugins/DirtMCP"
  local secrets="${plugin_data}/secrets"
  [[ ! -e "${plugin_data}" && ! -L "${plugin_data}" ||
    -d "${plugin_data}" && ! -L "${plugin_data}" ]] ||
    fail 'Paper DirtMCP data path must be a real directory'
  [[ ! -e "${secrets}" && ! -L "${secrets}" || -d "${secrets}" && ! -L "${secrets}" ]] ||
    fail 'Paper DirtMCP/secrets must be a real directory'
}

validate_install_state() {
  local installed_hostname
  local installed_paper_directory
  local installed_version
  if [[ -e "${configuration_directory}/install-version" ]]; then
    [[ -f "${configuration_directory}/install-version" &&
      ! -L "${configuration_directory}/install-version" &&
      -f "${configuration_directory}/hostname" && ! -L "${configuration_directory}/hostname" &&
      -f "${configuration_directory}/paper-directory" &&
      ! -L "${configuration_directory}/paper-directory" ]] ||
      fail 'the existing Dirt MCP installation state is invalid'
    installed_version=$(<"${configuration_directory}/install-version")
    installed_hostname=$(<"${configuration_directory}/hostname")
    installed_paper_directory=$(<"${configuration_directory}/paper-directory")
    [[ "${installed_version}" == "${release_version}" ]] ||
      fail "Dirt MCP ${installed_version} is already installed; upgrades are not supported yet"
    [[ "${installed_hostname}" == "${public_hostname}" &&
      "${installed_paper_directory}" == "${paper_directory}" ]] ||
      fail 'the existing installation uses different --paper-dir or --hostname values'
    resume_installation=true
    return
  fi

  [[ ! -e "${configuration_directory}" && ! -e "${application_directory}" &&
    ! -e "${service_unit}" && ! -e /var/lib/dirt-mcp ]] ||
    fail 'foreign or incomplete Dirt MCP files already exist'
  id "${service_user}" >/dev/null 2>&1 && fail 'the dirt-mcp system account already exists'
  getent group "${service_user}" >/dev/null 2>&1 && fail 'the dirt-mcp system group already exists'
  if command -v caddy >/dev/null 2>&1 || package_is_installed caddy ||
    [[ -e /etc/caddy/Caddyfile ]]; then
    fail 'Caddy is already installed; Dirt requires an otherwise unused HTTPS edge'
  fi
  if package_is_installed nginx || package_is_installed apache2; then
    fail 'an existing public web server is installed'
  fi
  local port
  for port in 80 443 3000; do
    if ss -H -ltn "sport = :${port}" | grep -q .; then
      fail "TCP port ${port} is already in use"
    fi
  done
}

package_is_installed() {
  dpkg-query --show --showformat='${db:Status-Status}\n' "$1" 2>/dev/null | grep -qx 'installed'
}

find_existing_jar() {
  local pattern=$1
  local -n result=$2
  mapfile -d '' -t result < <(
    find "${paper_directory}/plugins" -mindepth 1 -maxdepth 1 -iname "${pattern}" -print0
  )
  ((${#result[@]} <= 1)) || fail "multiple ${pattern} files exist in Paper plugins/"
}

validate_existing_plugins() {
  local hash
  local -a dirt_jars
  local -a fawe_jars
  local -a worldedit_jars

  mapfile -d '' -t worldedit_jars < <(
    find "${paper_directory}/plugins" -mindepth 1 -maxdepth 1 -iname 'WorldEdit*.jar' -print0
  )
  ((${#worldedit_jars[@]} == 0)) ||
    fail 'WorldEdit must be removed before installing FAWE, which replaces it'

  find_existing_jar '*dirt*mcp*.jar' dirt_jars
  if ((${#dirt_jars[@]} == 1)); then
    [[ -f "${dirt_jars[0]}" && ! -L "${dirt_jars[0]}" ]] ||
      fail 'the existing Dirt Paper plugin must be a regular file'
    hash=$(sha256sum "${dirt_jars[0]}" | awk '{print $1}')
    [[ "${hash}" == "${plugin_sha256}" ]] || fail 'a different Dirt Paper plugin is already installed'
  elif [[ -e "${paper_directory}/plugins/dirt-mcp-paper.jar" ||
    -L "${paper_directory}/plugins/dirt-mcp-paper.jar" ]]; then
    fail 'the Dirt Paper plugin destination is not safe to replace'
  fi

  find_existing_jar 'FastAsyncWorldEdit*.jar' fawe_jars
  if ((${#fawe_jars[@]} == 1)); then
    [[ -f "${fawe_jars[0]}" && ! -L "${fawe_jars[0]}" ]] ||
      fail 'the existing FAWE plugin must be a regular file'
    hash=$(sha512sum "${fawe_jars[0]}" | awk '{print $1}')
    [[ "${hash}" == "${fawe_sha512}" ]] || fail "FAWE ${fawe_version} with the pinned checksum is required"
  elif [[ -e "${paper_directory}/plugins/${fawe_filename}" ||
    -L "${paper_directory}/plugins/${fawe_filename}" ]]; then
    fail 'the FAWE destination is not safe to replace'
  fi
}

prepare_downloads() {
  temporary_directory=$(mktemp -d)
  trap cleanup EXIT
  if ! find "${paper_directory}/plugins" -maxdepth 1 -type f -iname 'FastAsyncWorldEdit*.jar' |
    grep -q .; then
    curl --proto '=https' --tlsv1.2 --fail --location --silent --show-error \
      --retry 3 --retry-all-errors --connect-timeout 15 --max-time 300 \
      --output "${temporary_directory}/${fawe_filename}" "${fawe_url}"
    printf '%s  %s\n' "${fawe_sha512}" "${temporary_directory}/${fawe_filename}" |
      sha512sum --check --status || fail 'downloaded FAWE checksum is invalid'
  fi
  curl --proto '=https' --tlsv1.2 --fail --location --silent --show-error \
    --retry 3 --retry-all-errors --connect-timeout 15 --max-time 60 \
    --output "${temporary_directory}/caddy.gpg.key" "${caddy_key_url}"
  printf '%s\n' "${caddy_repository_entry}" >"${temporary_directory}/caddy-stable.list"
}

write_install_state() {
  [[ "${resume_installation}" == false ]] || return
  configuration_staging_directory=$(mktemp --directory --tmpdir=/etc '.dirt-mcp.install.XXXXXX')
  printf '%s\n' "${release_version}" >"${configuration_staging_directory}/install-version"
  printf '%s\n' "${public_hostname}" >"${configuration_staging_directory}/hostname"
  printf '%s\n' "${paper_directory}" >"${configuration_staging_directory}/paper-directory"
  chmod 0600 \
    "${configuration_staging_directory}/install-version" \
    "${configuration_staging_directory}/hostname" \
    "${configuration_staging_directory}/paper-directory"
  mv --no-target-directory -- "${configuration_staging_directory}" "${configuration_directory}"
  configuration_staging_directory=''
}

install_application() {
  local unit_load_state
  unit_load_state=$(systemctl show --property=LoadState --value dirt-mcp.service)
  if [[ "${unit_load_state}" != 'not-found' ]]; then
    systemctl stop dirt-mcp.service || fail 'the existing Dirt MCP service could not be stopped safely'
  fi
  application_staging_directory=$(mktemp \
    --directory --tmpdir=/opt ".dirt-mcp.${release_version}.XXXXXX")
  install -d -o root -g root -m 0755 "${application_staging_directory}"
  cp -a -- "${bundle_directory}/app" "${application_staging_directory}/app"
  cp -a -- "${bundle_directory}/node" "${application_staging_directory}/node"
  install -o root -g root -m 0644 \
    "${bundle_directory}/LICENSE" "${application_staging_directory}/LICENSE"
  chown -R root:root "${application_staging_directory}"
  chmod -R a+rX,go-w "${application_staging_directory}"
  if [[ -e "${application_directory}" ]]; then
    [[ "${resume_installation}" == true ]] || fail "${application_directory} already exists"
    rm -rf -- "${application_directory}"
  fi
  mv --no-target-directory -- "${application_staging_directory}" "${application_directory}"
  application_staging_directory=''
}

install_plugin_jar() {
  local source=$1
  local destination=$2
  local staging="${destination}.installing"
  [[ ! -e "${staging}" && ! -L "${staging}" || -f "${staging}" && ! -L "${staging}" ]] ||
    fail "the plugin staging path is unsafe: ${staging}"
  rm -f -- "${staging}"
  install -o "${paper_owner}" -g "${paper_group}" -m 0644 "${source}" "${staging}"
  mv -f -- "${staging}" "${destination}"
}

install_plugin_files() {
  local -a dirt_jars
  local -a fawe_jars

  find_existing_jar '*dirt*mcp*.jar' dirt_jars
  if ((${#dirt_jars[@]} == 0)); then
    install_plugin_jar \
      "${bundle_directory}/paper-plugin.jar" \
      "${paper_directory}/plugins/dirt-mcp-paper.jar"
  else
    chown "${paper_owner}:${paper_group}" "${dirt_jars[0]}"
    chmod 0644 "${dirt_jars[0]}"
  fi
  find_existing_jar 'FastAsyncWorldEdit*.jar' fawe_jars
  if ((${#fawe_jars[@]} == 0)); then
    install_plugin_jar \
      "${temporary_directory}/${fawe_filename}" \
      "${paper_directory}/plugins/${fawe_filename}"
  else
    chown "${paper_owner}:${paper_group}" "${fawe_jars[0]}"
    chmod 0644 "${fawe_jars[0]}"
  fi

  install_plugin_credentials "${paper_owner}" "${paper_group}"
}

valid_service_token_file() {
  [[ -f "$1" && ! -L "$1" ]] || return 1
  "${bundle_directory}/node/bin/node" --input-type=module --eval '
    import { readFileSync } from "node:fs";
    const raw = readFileSync(process.argv[1], "utf8");
    const value = raw.endsWith("\r\n") ? raw.slice(0, -2) : raw.endsWith("\n") ? raw.slice(0, -1) : raw;
    process.exit(/^[0-9a-f]{64}$/u.test(value) ? 0 : 1);
  ' "$1"
}

service_token_value() {
  "${bundle_directory}/node/bin/node" --input-type=module --eval '
    import { readFileSync } from "node:fs";
    const raw = readFileSync(process.argv[1], "utf8");
    process.stdout.write(raw.endsWith("\r\n") ? raw.slice(0, -2) : raw.endsWith("\n") ? raw.slice(0, -1) : raw);
  ' "$1"
}

validate_existing_credentials() {
  local auth="${credentials_directory}/auth-secret"
  local secrets="${paper_directory}/plugins/DirtMCP/secrets"
  local bridge="${secrets}/bridge-token"
  local control="${secrets}/control-token"
  local bridge_value=''
  local control_value=''
  local auth_value=''

  local bridge_exists=false
  local control_exists=false
  [[ -e "${bridge}" || -L "${bridge}" ]] && bridge_exists=true
  [[ -e "${control}" || -L "${control}" ]] && control_exists=true
  if [[ "${bridge_exists}" != "${control_exists}" ]]; then
    [[ "${resume_installation}" == true ]] ||
      fail 'existing Paper credentials must contain both bridge-token and control-token'
    if [[ "${bridge_exists}" == true ]]; then
      [[ -f "${bridge}" && ! -L "${bridge}" ]] || fail 'the partial bridge credential is unsafe'
    else
      [[ -f "${control}" && ! -L "${control}" ]] || fail 'the partial control credential is unsafe'
    fi
    regenerate_paper_credentials=true
  elif [[ "${bridge_exists}" == true ]]; then
    if ! valid_service_token_file "${bridge}" || ! valid_service_token_file "${control}"; then
      fail 'existing Paper credentials must both be regular files containing exactly 64 lowercase hexadecimal characters'
    fi
    bridge_value=$(service_token_value "${bridge}")
    control_value=$(service_token_value "${control}")
    [[ "${bridge_value}" != "${control_value}" ]] ||
      fail 'Paper bridge and control credentials must be distinct'
  fi

  if [[ -e "${auth}" || -L "${auth}" ]]; then
    valid_service_token_file "${auth}" ||
      fail 'the existing authentication secret is not a safe 64-character hexadecimal file'
    auth_value=$(service_token_value "${auth}")
    [[ -z "${bridge_value}" || "${auth_value}" != "${bridge_value}" ]] ||
      fail 'authentication and bridge credentials must be distinct'
    [[ -z "${control_value}" || "${auth_value}" != "${control_value}" ]] ||
      fail 'authentication and control credentials must be distinct'
  fi
}

generate_token_file() {
  local destination=$1
  local owner=$2
  local group=$3
  local staging="${destination}.installing"
  [[ ! -e "${staging}" && ! -L "${staging}" || -f "${staging}" && ! -L "${staging}" ]] ||
    fail "the credential staging path is unsafe: ${staging}"
  rm -f -- "${staging}"
  "${application_directory}/node/bin/node" --input-type=module --eval '
    import { randomBytes } from "node:crypto";
    process.stdout.write(randomBytes(32).toString("hex"));
  ' >"${temporary_directory}/generated-token"
  install -o "${owner}" -g "${group}" -m 0600 "${temporary_directory}/generated-token" "${staging}"
  mv -f -- "${staging}" "${destination}"
}

install_plugin_credentials() {
  local paper_owner=$1
  local paper_group=$2
  local secrets="${paper_directory}/plugins/DirtMCP/secrets"
  local bridge="${secrets}/bridge-token"
  local control="${secrets}/control-token"
  install -d -o "${paper_owner}" -g "${paper_group}" -m 0750 \
    "${paper_directory}/plugins/DirtMCP"
  if [[ -e "${secrets}" || -L "${secrets}" ]]; then
    [[ -d "${secrets}" && ! -L "${secrets}" ]] || fail 'Paper DirtMCP/secrets must be a real directory'
    chown "${paper_owner}:${paper_group}" "${secrets}"
    chmod 0700 "${secrets}"
  else
    install -d -o "${paper_owner}" -g "${paper_group}" -m 0700 "${secrets}"
  fi
  if [[ "${regenerate_paper_credentials}" == true ]]; then
    rm -f -- "${bridge}" "${control}"
    generate_token_file "${bridge}" "${paper_owner}" "${paper_group}"
    generate_token_file "${control}" "${paper_owner}" "${paper_group}"
  elif [[ -e "${bridge}" || -L "${bridge}" || -e "${control}" || -L "${control}" ]]; then
    if ! valid_service_token_file "${bridge}" || ! valid_service_token_file "${control}"; then
      fail 'existing Paper credentials must both be regular files containing 64 lowercase hexadecimal characters'
    fi
  else
    generate_token_file "${bridge}" "${paper_owner}" "${paper_group}"
    generate_token_file "${control}" "${paper_owner}" "${paper_group}"
  fi
  chown "${paper_owner}:${paper_group}" "${bridge}" "${control}"
  chmod 0600 "${bridge}" "${control}"
  [[ "$(service_token_value "${bridge}")" != "$(service_token_value "${control}")" ]] ||
    fail 'Paper bridge and control credentials must be distinct'
}

install_authentication_secret() {
  local auth_secret="${credentials_directory}/auth-secret"
  install -d -o root -g root -m 0700 "${credentials_directory}"
  if [[ -e "${auth_secret}" || -L "${auth_secret}" ]]; then
    valid_service_token_file "${auth_secret}" ||
      fail 'the existing authentication secret is not a safe 64-character hexadecimal file'
  else
    generate_token_file "${auth_secret}" root root
  fi
  chown root:root "${auth_secret}"
  chmod 0600 "${auth_secret}"
  local auth_value
  local bridge_value
  local control_value
  auth_value=$(service_token_value "${auth_secret}")
  bridge_value=$(service_token_value "${paper_directory}/plugins/DirtMCP/secrets/bridge-token")
  control_value=$(service_token_value "${paper_directory}/plugins/DirtMCP/secrets/control-token")
  while [[ "${auth_value}" == "${bridge_value}" || "${auth_value}" == "${control_value}" ]]; do
    rm -f -- "${auth_secret}"
    generate_token_file "${auth_secret}" root root
    auth_value=$(service_token_value "${auth_secret}")
  done
}

render_templates() {
  "${application_directory}/node/bin/node" --input-type=module --eval '
    import { readFileSync, writeFileSync } from "node:fs";
    const [input, output, hostname, rawPaperDirectory] = process.argv.slice(1);
    const credentialPaperDirectory = rawPaperDirectory.replaceAll("%", "%%");
    const paperDirectory = credentialPaperDirectory
      .replaceAll("\\", "\\\\")
      .replaceAll("\"", "\\\"")
      .replaceAll("\t", "\\t");
    const placeholder = (name) => String.fromCharCode(64) + name + String.fromCharCode(64);
    const rendered = readFileSync(input, "utf8")
      .replaceAll(placeholder("HOSTNAME"), hostname)
      .replaceAll(placeholder("PAPER_CREDENTIAL_DIR"), credentialPaperDirectory)
      .replaceAll(placeholder("PAPER_DIR"), paperDirectory);
    if (/@[A-Z0-9_]+@/u.test(rendered)) throw new Error("Unresolved service template placeholder");
    writeFileSync(output, rendered, { mode: 0o600 });
  ' "${bundle_directory}/dirt-mcp.service.in" "${temporary_directory}/dirt-mcp.service" \
    "${public_hostname}" "${paper_directory}"
  "${application_directory}/node/bin/node" --input-type=module --eval '
    import { readFileSync, writeFileSync } from "node:fs";
    const [input, output, hostname] = process.argv.slice(1);
    const placeholder = String.fromCharCode(64) + "HOSTNAME" + String.fromCharCode(64);
    const rendered = readFileSync(input, "utf8").replaceAll(placeholder, hostname);
    if (/@[A-Z0-9_]+@/u.test(rendered)) throw new Error("Unresolved Caddy template placeholder");
    writeFileSync(output, rendered, { mode: 0o600 });
  ' "${bundle_directory}/Caddyfile.in" "${temporary_directory}/Caddyfile" "${public_hostname}"
  systemd-analyze verify "${temporary_directory}/dirt-mcp.service"
}

install_caddy() {
  local key_fingerprint

  apt-get update
  DEBIAN_FRONTEND=noninteractive apt-get install --yes --no-install-recommends \
    -o Dpkg::Options::=--force-confold \
    apt-transport-https ca-certificates curl debian-archive-keyring debian-keyring gpg
  if ! key_fingerprint=$(
    gpg --homedir "${temporary_directory}" --batch --quiet --no-options --no-default-keyring \
      --show-keys --with-colons \
      "${temporary_directory}/caddy.gpg.key" |
      awk -F: '
        $1 == "pub" { primary_key = 1; next }
        primary_key && $1 == "fpr" { print $10; primary_key = 0 }
      '
  ); then
    fail 'downloaded Caddy signing key is invalid'
  fi
  [[ "${key_fingerprint}" == "${caddy_key_fingerprint}" ]] ||
    fail 'downloaded Caddy signing key fingerprint is invalid'
  gpg --dearmor --batch --yes --no-options \
    --output "${temporary_directory}/caddy-stable-archive-keyring.gpg" \
    "${temporary_directory}/caddy.gpg.key"
  install -o root -g root -m 0644 "${temporary_directory}/caddy-stable-archive-keyring.gpg" \
    /usr/share/keyrings/caddy-stable-archive-keyring.gpg
  install -o root -g root -m 0644 "${temporary_directory}/caddy-stable.list" \
    /etc/apt/sources.list.d/caddy-stable.list
  install -d -o root -g root -m 0755 /etc/caddy
  install -o root -g root -m 0644 "${temporary_directory}/Caddyfile" /etc/caddy/Caddyfile
  apt-get update
  DEBIAN_FRONTEND=noninteractive apt-get install --yes --no-install-recommends \
    -o Dpkg::Options::=--force-confold caddy
  caddy validate --config "${temporary_directory}/Caddyfile" --adapter caddyfile
  install -o root -g caddy -m 0640 "${temporary_directory}/Caddyfile" /etc/caddy/Caddyfile
}

install_service() {
  install -o root -g root -m 0644 "${temporary_directory}/dirt-mcp.service" "${service_unit}"
  systemctl daemon-reload
  if ! systemctl enable --now dirt-mcp.service; then
    journalctl --unit dirt-mcp.service --no-pager --lines 30 >&2 || true
    fail 'the Dirt MCP service could not start'
  fi
  systemctl enable caddy.service
  systemctl restart caddy.service

  local _attempt
  for _attempt in {1..40}; do
    if curl --fail --silent --show-error --max-time 2 http://127.0.0.1:3000/healthz >/dev/null; then
      systemctl is-active --quiet caddy.service || fail 'Caddy did not remain active'
      return
    fi
    sleep 0.5
  done
  journalctl --unit dirt-mcp.service --no-pager --lines 30 >&2 || true
  fail 'the Dirt MCP service did not become healthy'
}

cleanup() {
  if [[ "${application_staging_directory}" == /opt/.dirt-mcp.* &&
    -d "${application_staging_directory}" && ! -L "${application_staging_directory}" ]]; then
    rm -rf -- "${application_staging_directory}"
  fi
  if [[ "${configuration_staging_directory}" == /etc/.dirt-mcp.install.* &&
    -d "${configuration_staging_directory}" && ! -L "${configuration_staging_directory}" ]]; then
    rm -rf -- "${configuration_staging_directory}"
  fi
  if [[ -n "${temporary_directory}" && -d "${temporary_directory}" ]]; then
    rm -rf -- "${temporary_directory}"
  fi
}

main() {
  read_arguments "$@"
  validate_host
  local command
  for command in apt-get awk chown chmod cp curl dpkg-query find getent grep id install journalctl \
    mktemp mv ps realpath rm sed sha256sum sha512sum sleep ss stat systemctl systemd-analyze tr uname useradd xargs; do
    require_command "${command}"
  done
  validate_bundle
  validate_hostname
  validate_paper
  validate_install_state
  validate_existing_plugins
  validate_existing_credentials
  prepare_downloads

  write_install_state
  if ! id "${service_user}" >/dev/null 2>&1; then
    useradd --system --user-group --home-dir /var/lib/dirt-mcp --no-create-home \
      --shell /usr/sbin/nologin "${service_user}"
  fi
  getent group "${service_user}" >/dev/null || fail 'the dirt-mcp service group is missing'
  install_application
  install_plugin_files
  install_authentication_secret
  render_templates
  install_caddy
  install_service

  printf '\nDirt MCP %s is installed for https://%s.\n' "${release_version}" "${public_hostname}"
  printf 'Restart Paper with its existing supervisor, then create the first Dirt invitation in game.\n'
}

main "$@"
