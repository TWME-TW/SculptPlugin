#!/usr/bin/env bash
set -euo pipefail

readonly PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly EDITOR="${SCULPT_E2E_EDITOR:-worldedit}"
readonly PORT="${SCULPT_E2E_PORT:-$(node -e 'const net=require("net");const s=net.createServer();s.listen(0,"127.0.0.1",()=>{console.log(s.address().port);s.close()})')}"
readonly TEMP_DIR="$(mktemp -d)"
readonly SERVER_DIR="${TEMP_DIR}/server"
readonly CACHE_DIR="${PROJECT_DIR}/target/integration-cache"
readonly PAPER_JAR="${CACHE_DIR}/paper-1.21.11.jar"
readonly PAPER_API="https://fill.papermc.io/v3/projects/paper/versions/1.21.11/builds/latest"
readonly WORLD_EDIT_JAR="${SCULPT_E2E_WORLDEDIT_JAR:-${CACHE_DIR}/worldedit-bukkit-7.4.5.jar}"
readonly WORLD_EDIT_API='https://api.modrinth.com/v2/project/worldedit/version?loaders=%5B%22paper%22%5D&game_versions=%5B%221.21.11%22%5D'
readonly FAWE_JAR="${SCULPT_E2E_FAWE_JAR:-${CACHE_DIR}/FastAsyncWorldEdit-Paper.jar}"
readonly INTEGRATION_JAR="${PROJECT_DIR}/integration/paper-plugin/target/sculpt-integration-1.0.0.jar"
SERVER_PID=""

cleanup() {
  if [[ -n "${SERVER_PID}" ]] && kill -0 "${SERVER_PID}" 2>/dev/null; then
    kill "${SERVER_PID}" 2>/dev/null || true
    for _ in $(seq 1 10); do
      if ! kill -0 "${SERVER_PID}" 2>/dev/null; then break; fi
      sleep 1
    done
    if kill -0 "${SERVER_PID}" 2>/dev/null; then
      kill -KILL "${SERVER_PID}" 2>/dev/null || true
    fi
    wait "${SERVER_PID}" 2>/dev/null || true
  fi
  if [[ "${SCULPT_E2E_KEEP_SERVER:-false}" == "true" ]]; then
    echo "Server retained at ${SERVER_DIR}"
  else
    rm -rf "${TEMP_DIR}"
  fi
}
trap cleanup EXIT

if [[ "${EDITOR}" != "worldedit" && "${EDITOR}" != "fawe" ]]; then
  echo "SCULPT_E2E_EDITOR must be worldedit or fawe" >&2
  exit 2
fi

if [[ "${SCULPT_E2E_SKIP_BUILD:-false}" != "true" ]]; then
  mvn -B -f "${PROJECT_DIR}/pom.xml" package
  mvn -B -f "${PROJECT_DIR}/integration/paper-plugin/pom.xml" package
fi
if [[ ! -f "${PROJECT_DIR}/target/Sculpt-1.0.0.jar" \
    || ! -f "${INTEGRATION_JAR}" ]]; then
  echo "Build artifacts are missing; rerun without SCULPT_E2E_SKIP_BUILD=true" >&2
  exit 2
fi
mkdir -p "${CACHE_DIR}" "${SERVER_DIR}/plugins"

paper_metadata="$(curl --fail --silent --show-error --location "${PAPER_API}")"
paper_url="$(jq -er '.downloads["server:default"].url' <<<"${paper_metadata}")"
paper_sha256="$(jq -er '.downloads["server:default"].checksums.sha256' <<<"${paper_metadata}")"
if [[ ! -f "${PAPER_JAR}" ]] || ! echo "${paper_sha256}  ${PAPER_JAR}" | sha256sum --check --status; then
  curl --fail --silent --show-error --location "${paper_url}" --output "${PAPER_JAR}"
fi
echo "${paper_sha256}  ${PAPER_JAR}" | sha256sum --check --status

if [[ "${EDITOR}" == "worldedit" && -z "${SCULPT_E2E_WORLDEDIT_JAR:-}" ]]; then
  worldedit_metadata="$(curl --fail --silent --show-error --location "${WORLD_EDIT_API}")"
  worldedit_url="$(jq -er '[.[] | select(.version_number == "7.4.5")][0].files[] | select(.primary).url' <<<"${worldedit_metadata}")"
  worldedit_sha512="$(jq -er '[.[] | select(.version_number == "7.4.5")][0].files[] | select(.primary).hashes.sha512' <<<"${worldedit_metadata}")"
  if [[ ! -f "${WORLD_EDIT_JAR}" ]] || ! echo "${worldedit_sha512}  ${WORLD_EDIT_JAR}" | sha512sum --check --status; then
    curl --fail --silent --show-error --location "${worldedit_url}" --output "${WORLD_EDIT_JAR}"
  fi
  echo "${worldedit_sha512}  ${WORLD_EDIT_JAR}" | sha512sum --check --status
fi

if [[ "${EDITOR}" == "worldedit" ]]; then
  editor_jar="${WORLD_EDIT_JAR}"
else
  editor_jar="${FAWE_JAR}"
fi
if [[ ! -f "${editor_jar}" ]]; then
  echo "Editor plugin not found: ${editor_jar}" >&2
  exit 2
fi

cp "${PAPER_JAR}" "${SERVER_DIR}/paper.jar"
cp "${PROJECT_DIR}/target/Sculpt-1.0.0.jar" "${SERVER_DIR}/plugins/Sculpt.jar"
cp "${INTEGRATION_JAR}" "${SERVER_DIR}/plugins/SculptIntegration.jar"
cp "${editor_jar}" "${SERVER_DIR}/plugins/WorldEditor.jar"

printf 'eula=true\n' >"${SERVER_DIR}/eula.txt"
printf '%s\n' \
  "server-port=${PORT}" \
  'online-mode=false' \
  'use-native-transport=false' \
  'spawn-protection=0' \
  'view-distance=5' \
  'simulation-distance=5' \
  'level-type=minecraft:flat' \
  'generate-structures=false' \
  'difficulty=peaceful' \
  'motd=Sculpt WorldEdit integration test' \
  >"${SERVER_DIR}/server.properties"

offline_uuid="$(node -e '
  const { createHash } = require("node:crypto")
  const bytes = createHash("md5").update(`OfflinePlayer:${process.argv[1]}`).digest()
  bytes[6] = (bytes[6] & 0x0f) | 0x30
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = bytes.toString("hex")
  console.log(`${hex.slice(0,8)}-${hex.slice(8,12)}-${hex.slice(12,16)}-${hex.slice(16,20)}-${hex.slice(20)}`)
' SculptE2E)"
printf '[{"uuid":"%s","name":"SculptE2E","level":4,"bypassesPlayerLimit":false}]\n' \
  "${offline_uuid}" >"${SERVER_DIR}/ops.json"

pushd "${SERVER_DIR}" >/dev/null
java -Xms512M -Xmx2G -jar paper.jar --nogui >server.log 2>&1 &
SERVER_PID="$!"
popd >/dev/null

for _ in $(seq 1 240); do
  if grep -q 'Done (' "${SERVER_DIR}/server.log" 2>/dev/null; then break; fi
  if ! kill -0 "${SERVER_PID}" 2>/dev/null; then
    tail -240 "${SERVER_DIR}/server.log" >&2
    exit 1
  fi
  sleep 1
done
if ! grep -q 'Done (' "${SERVER_DIR}/server.log"; then
  tail -240 "${SERVER_DIR}/server.log" >&2
  echo "Paper did not start within 240 seconds" >&2
  exit 1
fi
if ! grep -q 'Enabling Sculpt v1.0.0' "${SERVER_DIR}/server.log" \
    || ! grep -q 'WorldEdit paste detection enabled' "${SERVER_DIR}/server.log"; then
  tail -240 "${SERVER_DIR}/server.log" >&2
  echo "Sculpt or ${EDITOR} integration did not enable" >&2
  exit 1
fi

npm install --package-lock-only --prefix "${PROJECT_DIR}/integration/mineflayer" --silent
npm ci --prefix "${PROJECT_DIR}/integration/mineflayer" --silent
if ! SCULPT_E2E_PORT="${PORT}" SCULPT_E2E_EDITOR="${EDITOR}" \
    node "${PROJECT_DIR}/integration/mineflayer/test.mjs"; then
  tail -300 "${SERVER_DIR}/server.log" >&2
  exit 1
fi
