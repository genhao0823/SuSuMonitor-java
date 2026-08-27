#!/usr/bin/env bash
# Install or update a SuSuMonitor Agent from a versioned HTTPS release.
# The administrator password is read from /dev/tty and never printed.

set -Eeuo pipefail
umask 077

readonly SERVICE_NAME="susumonitor-agent"
readonly CONFIG_DIR="/etc/susumonitor"
readonly CONFIG_FILE="${CONFIG_DIR}/agent.env"
readonly INSTALL_DIR="/usr/local/bin"
readonly BINARY_FILE="${INSTALL_DIR}/susumonitor-agent"
readonly SERVICE_FILE="/etc/systemd/system/${SERVICE_NAME}.service"
readonly LOG_DIR="/var/log/susumonitor"
readonly LOGROTATE_FILE="/etc/logrotate.d/${SERVICE_NAME}"

: "${AGENT_BASE_URL:=https://genhaosan.online}"
: "${AGENT_VERSION:=1.0.0}"
: "${AGENT_NAME:=}"
: "${AGENT_SERVER_ID:=}"
: "${AGENT_ROTATE:=false}"
: "${AGENT_TERMINAL_ENABLED:=false}"
: "${AGENT_ALLOW_INSECURE_HTTP:=false}"

TMP_DIR=""
OLD_BINARY=""
OLD_SERVICE=""
OLD_CONFIG=""
ROLLBACK_NEEDED=false

info() { printf '[INFO] %s\n' "$*"; }
warn() { printf '[WARN] %s\n' "$*" >&2; }
die() { printf '[ERROR] %s\n' "$*" >&2; exit 1; }

cleanup() {
    unset ADMIN_USERNAME ADMIN_PASSWORD JWT AGENT_TOKEN LOGIN_JSON SERVER_JSON TOKEN_JSON || true
    if [[ -n "${TMP_DIR}" && -d "${TMP_DIR}" ]]; then
        rm -rf -- "${TMP_DIR}"
    fi
}
trap cleanup EXIT

rollback() {
    if [[ "${ROLLBACK_NEEDED}" != true ]]; then
        return
    fi
    warn "Agent start failed; restoring the previous installation."
    systemctl stop "${SERVICE_NAME}" >/dev/null 2>&1 || true
    if [[ -n "${OLD_BINARY}" && -f "${OLD_BINARY}" ]]; then
        install -o root -g root -m 0755 "${OLD_BINARY}" "${BINARY_FILE}"
    fi
    if [[ -n "${OLD_SERVICE}" && -f "${OLD_SERVICE}" ]]; then
        install -o root -g root -m 0644 "${OLD_SERVICE}" "${SERVICE_FILE}"
    fi
    if [[ -n "${OLD_CONFIG}" && -f "${OLD_CONFIG}" ]]; then
        install -o root -g root -m 0600 "${OLD_CONFIG}" "${CONFIG_FILE}"
    fi
    systemctl daemon-reload >/dev/null 2>&1 || true
    if [[ -f "${BINARY_FILE}" && -f "${SERVICE_FILE}" && -f "${CONFIG_FILE}" ]]; then
        systemctl start "${SERVICE_NAME}" >/dev/null 2>&1 || true
    fi
}
trap rollback ERR

require_command() {
    command -v "$1" >/dev/null 2>&1 || die "Required command not found: $1"
}

[[ "$(id -u)" -eq 0 ]] || die "Run this command with sudo."
[[ "$(uname -s)" == Linux ]] || die "This installer supports Linux only."
[[ -d /run/systemd/system ]] || die "systemd is required."

for command_name in curl python3 sha256sum install systemctl; do
    require_command "${command_name}"
done

if [[ "${AGENT_ALLOW_INSECURE_HTTP}" == true ]]; then
    [[ "${AGENT_BASE_URL}" =~ ^http://[^/]+$ ]] \
        || die "AGENT_ALLOW_INSECURE_HTTP=true requires an http:// origin without a path."
    warn "INSECURE TEMPORARY MODE: credentials and downloads use plain HTTP. Intended only for authorized temporary IPv4 test hosts (e.g. the historical 82.156.245.102)."
    CURL_TRANSPORT=(--proto '=http' --http1.1)
else
    [[ "${AGENT_BASE_URL}" =~ ^https://[^/]+$ ]] \
        || die "AGENT_BASE_URL must be an HTTPS origin without a path. Set AGENT_ALLOW_INSECURE_HTTP=true only for authorized temporary IPv4 tests."
    CURL_TRANSPORT=(--proto '=https' --tlsv1.2)
fi
[[ "${AGENT_VERSION}" =~ ^[0-9A-Za-z._-]+$ ]] || die "AGENT_VERSION contains unsupported characters."
[[ "${AGENT_ROTATE}" == true || "${AGENT_ROTATE}" == false ]] || die "AGENT_ROTATE must be true or false."
[[ "${AGENT_TERMINAL_ENABLED}" == true || "${AGENT_TERMINAL_ENABLED}" == false ]] || die "AGENT_TERMINAL_ENABLED must be true or false."

if [[ -z "${AGENT_NAME}" ]]; then
    AGENT_NAME="$(hostname -s 2>/dev/null || hostname)"
fi
[[ "${AGENT_NAME}" =~ ^[A-Za-z0-9._-]{1,100}$ ]] || die "AGENT_NAME must contain only letters, digits, dot, underscore or hyphen."

TMP_DIR="$(mktemp -d /tmp/susumonitor-agent.XXXXXX)"
readonly RELEASE_URL="${AGENT_BASE_URL}/agent/releases/${AGENT_VERSION}"
readonly BINARY_NAME="susumonitor-agent-linux-amd64"
readonly SERVICE_NAME_FILE="susumonitor-agent.service"
readonly LOGROTATE_NAME="susumonitor-agent.logrotate"

download() {
    curl --fail --silent --show-error --location "${CURL_TRANSPORT[@]}" \
        --retry 2 --connect-timeout 10 --max-time 120 -o "$2" "$1"
}

if [[ "$(uname -m)" != x86_64 && "$(uname -m)" != amd64 ]]; then
    die "This release currently supports Linux x86_64 only."
fi

info "Downloading Agent release ${AGENT_VERSION}."
download "${RELEASE_URL}/${BINARY_NAME}" "${TMP_DIR}/${BINARY_NAME}"
download "${RELEASE_URL}/${BINARY_NAME}.sha256" "${TMP_DIR}/${BINARY_NAME}.sha256"
download "${RELEASE_URL}/${SERVICE_NAME_FILE}" "${TMP_DIR}/${SERVICE_NAME_FILE}"
download "${RELEASE_URL}/${LOGROTATE_NAME}" "${TMP_DIR}/${LOGROTATE_NAME}"

grep -Eq "^[[:xdigit:]]{64}[[:space:]]+\*?${BINARY_NAME}$" "${TMP_DIR}/${BINARY_NAME}.sha256" \
    || die "Invalid Agent checksum manifest."
(cd "${TMP_DIR}" && sha256sum --check "${BINARY_NAME}.sha256") \
    || die "Agent checksum verification failed."
if command -v file >/dev/null 2>&1; then
    file_type="$(file -b "${TMP_DIR}/${BINARY_NAME}" 2>/dev/null || true)"
    [[ "${file_type}" == *"ELF 64-bit"* && "${file_type}" == *"x86-64"* ]] \
        || die "Downloaded Agent is not a Linux x86_64 ELF binary."
fi
[[ -s "${TMP_DIR}/${SERVICE_NAME_FILE}" ]] || die "Downloaded systemd unit is empty."
[[ -s "${TMP_DIR}/${LOGROTATE_NAME}" ]] || die "Downloaded logrotate config is empty."

if [[ -z "${AGENT_SERVER_ID}" && -r "${CONFIG_FILE}" ]]; then
    AGENT_SERVER_ID="$(sed -n 's/^SUSUMONITOR_SERVER_ID=//p' "${CONFIG_FILE}" | head -n 1)"
fi

if [[ -z "${AGENT_SERVER_ID}" ]]; then
    [[ -r /dev/tty ]] || die "AGENT_SERVER_ID is required when no interactive terminal is available."
    exec 3<>/dev/tty
    read -r -p "Administrator username: " ADMIN_USERNAME <&3
    read -r -s -p "Administrator password: " ADMIN_PASSWORD <&3
    printf '\n' >&3
    read -r -p "Agent name [${AGENT_NAME}]: " entered_name <&3 || true
    exec 3>&-
    [[ -n "${ADMIN_USERNAME}" && -n "${ADMIN_PASSWORD}" ]] || die "Administrator credentials are required."
    [[ -z "${entered_name}" || "${entered_name}" =~ ^[A-Za-z0-9._-]{1,100}$ ]] \
        || die "Agent name contains unsupported characters."
    [[ -n "${entered_name}" ]] && AGENT_NAME="${entered_name}"

    LOGIN_JSON="$(curl --fail --silent --show-error --location "${CURL_TRANSPORT[@]}" \
        --retry 2 --connect-timeout 10 --max-time 30 \
        -H 'Content-Type: application/json' \
        --data "$(python3 -c 'import json,sys; print(json.dumps({"username":sys.argv[1],"password":sys.argv[2]}))' "${ADMIN_USERNAME}" "${ADMIN_PASSWORD}")" \
        "${AGENT_BASE_URL}/api/auth/login")" \
        || die "Login request failed."
    JWT="$(printf '%s' "${LOGIN_JSON}" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get("data",{}).get("token","") if d.get("code") == 0 else "")')"
    [[ -n "${JWT}" ]] || die "Login failed or response did not contain a token."

    SERVER_JSON="$(curl --fail --silent --show-error --location "${CURL_TRANSPORT[@]}" \
        --retry 2 --connect-timeout 10 --max-time 30 \
        -H "Authorization: Bearer ${JWT}" -H 'Content-Type: application/json' \
        --data "$(python3 -c 'import json,sys; n=sys.argv[1]; print(json.dumps({"name":n,"host":n,"description":"SuSuMonitor Agent host","ssh_host":"127.0.0.1","ssh_port":22,"ssh_user":"agent","ssh_auth_type":"password","ssh_password":"agent-mode-placeholder"}))' "${AGENT_NAME}")" \
        "${AGENT_BASE_URL}/api/servers")" \
        || die "Server creation request failed."
    AGENT_SERVER_ID="$(printf '%s' "${SERVER_JSON}" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get("data",{}).get("id","") if d.get("code") == 0 else "")')"
    [[ "${AGENT_SERVER_ID}" =~ ^[1-9][0-9]*$ ]] || die "Server creation failed or returned an invalid id."

    TOKEN_JSON="$(curl --fail --silent --show-error --location "${CURL_TRANSPORT[@]}" \
        --retry 2 --connect-timeout 10 --max-time 30 \
        -X POST \
        -H "Authorization: Bearer ${JWT}" \
        "${AGENT_BASE_URL}/api/servers/${AGENT_SERVER_ID}/agent/register")" \
        || die "Agent token request failed."
    AGENT_TOKEN="$(printf '%s' "${TOKEN_JSON}" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get("data",{}).get("agent_token","") if d.get("code") == 0 else "")')"
    [[ -n "${AGENT_TOKEN}" ]] || die "Agent registration failed. Existing servers are not rotated automatically."
fi

[[ "${AGENT_SERVER_ID}" =~ ^[1-9][0-9]*$ ]] || die "AGENT_SERVER_ID must be a positive integer."
if [[ -z "${AGENT_TOKEN:-}" && -r "${CONFIG_FILE}" ]]; then
    AGENT_TOKEN="$(sed -n 's/^SUSUMONITOR_AGENT_TOKEN=//p' "${CONFIG_FILE}" | head -n 1)"
fi
[[ -n "${AGENT_TOKEN:-}" ]] || die "AGENT_TOKEN is required when reusing an existing server."

mkdir -p "${CONFIG_DIR}" "${LOG_DIR}"
chown root:root "${CONFIG_DIR}" "${LOG_DIR}"
chmod 0700 "${CONFIG_DIR}"
chmod 0755 "${LOG_DIR}"

if [[ -f "${BINARY_FILE}" ]]; then
    OLD_BINARY="${TMP_DIR}/old-agent"
    cp -p "${BINARY_FILE}" "${OLD_BINARY}"
fi
if [[ -f "${SERVICE_FILE}" ]]; then
    OLD_SERVICE="${TMP_DIR}/old-service"
    cp -p "${SERVICE_FILE}" "${OLD_SERVICE}"
fi
if [[ -f "${CONFIG_FILE}" ]]; then
    OLD_CONFIG="${TMP_DIR}/old-config"
    cp -p "${CONFIG_FILE}" "${OLD_CONFIG}"
fi

install -o root -g root -m 0755 "${TMP_DIR}/${BINARY_NAME}" "${BINARY_FILE}.new"
mv -f -- "${BINARY_FILE}.new" "${BINARY_FILE}"
install -o root -g root -m 0644 "${TMP_DIR}/${SERVICE_NAME_FILE}" "${SERVICE_FILE}.new"
mv -f -- "${SERVICE_FILE}.new" "${SERVICE_FILE}"

if [[ "${AGENT_BASE_URL}" == https://* ]]; then
    AGENT_WS_URL="${AGENT_BASE_URL/https:/wss:}"
else
    AGENT_WS_URL="${AGENT_BASE_URL/http:/ws:}"
fi

if [[ ! -f "${CONFIG_FILE}" ]]; then
    cat > "${CONFIG_FILE}.new" <<EOF
SUSUMONITOR_BACKEND_URL=${AGENT_WS_URL}
SUSUMONITOR_SERVER_ID=${AGENT_SERVER_ID}
SUSUMONITOR_AGENT_TOKEN=${AGENT_TOKEN}
SUSUMONITOR_COLLECT_INTERVAL_SECONDS=5
SUSUMONITOR_HEARTBEAT_INTERVAL_SECONDS=30
SUSUMONITOR_RECONNECT_INITIAL_SECONDS=5
SUSUMONITOR_RECONNECT_MAX_SECONDS=60
SUSUMONITOR_LOG_LEVEL=info
SUSUMONITOR_TERMINAL_ENABLED=${AGENT_TERMINAL_ENABLED}
SUSUMONITOR_TERMINAL_SHELL=/bin/bash
EOF
    chown root:root "${CONFIG_FILE}.new"
    chmod 0600 "${CONFIG_FILE}.new"
    mv -f -- "${CONFIG_FILE}.new" "${CONFIG_FILE}"
else
    chown root:root "${CONFIG_FILE}"
    chmod 0600 "${CONFIG_FILE}"
fi

install -o root -g root -m 0644 "${TMP_DIR}/${LOGROTATE_NAME}" "${LOGROTATE_FILE}"

ROLLBACK_NEEDED=true
systemctl daemon-reload
systemctl enable "${SERVICE_NAME}" >/dev/null
systemctl restart "${SERVICE_NAME}"
ROLLBACK_NEEDED=false

unset ADMIN_USERNAME ADMIN_PASSWORD JWT AGENT_TOKEN LOGIN_JSON SERVER_JSON TOKEN_JSON
info "Agent ${AGENT_VERSION} installed and started."
info "Server id: ${AGENT_SERVER_ID}; configuration: ${CONFIG_FILE}"
info "Check: systemctl status ${SERVICE_NAME} --no-pager"
