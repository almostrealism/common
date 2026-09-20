#!/usr/bin/env bash
# ─── Regression tests for rebuild.sh's fleet setup guards ───────────
#
# rebuild.sh's "Fleet monitoring" block (credential generation, data
# directories, FLEET_BIND_ADDR detection) runs real filesystem and network
# commands and defaults every path to macOS-only Shared directories, so it
# cannot be driven end to end in CI. SECRETS_DIR / FLEET_DB_DATA_DIR /
# FLEET_GRAFANA_DATA_DIR each accept an environment override (same pattern
# already used by AGENT_ENV and TRANSCRIPT_DIR_HOST elsewhere in this
# script), which lets these tests redirect that block at temporary
# directories instead. `docker` and `mvn` are replaced with no-op mocks on
# PATH so the script never attempts a real build or compose invocation.
#
# Usage:
#   test-rebuild.sh
#
# Exit codes:
#   0 - all tests passed
#   1 - one or more tests failed

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SCRIPT="$SCRIPT_DIR/rebuild.sh"

PASS=0
FAIL=0
FAILED_TESTS=()

MOCK_BIN="$(mktemp -d)"
MOCK_DOCKER_LOG="$(mktemp)"

cat > "$MOCK_BIN/docker" <<'EOF'
#!/usr/bin/env bash
{
  echo "ARGS $*"
  echo "FLEET_BIND_ADDR=${FLEET_BIND_ADDR:-<unset>}"
} >> "$MOCK_DOCKER_LOG"
exit 0
EOF
chmod +x "$MOCK_BIN/docker"

cat > "$MOCK_BIN/mvn" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
chmod +x "$MOCK_BIN/mvn"

cleanup() {
  rm -rf "$MOCK_BIN"
  rm -f "$MOCK_DOCKER_LOG"
}
trap cleanup EXIT

# run_case NAME EXPECT_EXIT MUST_CONTAIN MUST_NOT_CONTAIN -- SERVICES... -- ENV=VAL...
#
# Runs rebuild.sh with fresh temp SECRETS_DIR/FLEET_DB_DATA_DIR/
# FLEET_GRAFANA_DATA_DIR (optionally seeded beforehand via the SEED_DB_FILE /
# SEED_SECRETS_FILE globals a caller sets before invoking), the given
# service-name arguments, and the given extra environment variables.
run_case() {
  local name="$1" expect_exit="$2" must_contain="$3" must_not_contain="$4"
  shift 4
  local services=()
  while [ "$1" != "--" ]; do
    services+=("$1")
    shift
  done
  shift

  local tmp_secrets tmp_db tmp_grafana out actual_exit
  tmp_secrets="$(mktemp -d)"
  tmp_db="$(mktemp -d)"
  tmp_grafana="$(mktemp -d)"
  out="$(mktemp)"
  : > "$MOCK_DOCKER_LOG"

  if [ -n "${SEED_DB_FILE:-}" ]; then
    touch "$tmp_db/${SEED_DB_FILE}"
  fi
  if [ -n "${SEED_SECRETS_FILE:-}" ]; then
    echo "placeholder" > "$tmp_secrets/${SEED_SECRETS_FILE}"
    chmod 600 "$tmp_secrets/${SEED_SECRETS_FILE}"
  fi
  if [ -n "${SEED_EMPTY_SECRETS_FILE:-}" ]; then
    : > "$tmp_secrets/${SEED_EMPTY_SECRETS_FILE}"
    chmod 600 "$tmp_secrets/${SEED_EMPTY_SECRETS_FILE}"
  fi

  env -i PATH="$MOCK_BIN:$PATH" HOME="${HOME:-/tmp}" \
      SECRETS_DIR="$tmp_secrets" \
      FLEET_DB_DATA_DIR="$tmp_db" \
      FLEET_GRAFANA_DATA_DIR="$tmp_grafana" \
      MOCK_DOCKER_LOG="$MOCK_DOCKER_LOG" \
      "$@" \
      bash "$SCRIPT" "${services[@]}" >"$out" 2>&1
  actual_exit=$?

  local ok=true
  if [ "$actual_exit" != "$expect_exit" ]; then
    ok=false
  fi
  if [ -n "$must_contain" ] && ! grep -qF "$must_contain" "$out"; then
    ok=false
  fi
  if [ -n "$must_not_contain" ] && grep -qF "$must_not_contain" "$out"; then
    ok=false
  fi

  if [ "$ok" = "true" ]; then
    PASS=$((PASS + 1))
    echo "  PASS: $name"
  else
    FAIL=$((FAIL + 1))
    FAILED_TESTS+=("$name")
    echo "  FAIL: $name (exit=$actual_exit, expected=$expect_exit)"
    echo "    --- script output ---"
    sed 's/^/    /' "$out"
  fi

  rm -rf "$tmp_secrets" "$tmp_db" "$tmp_grafana" "$out"
  unset SEED_DB_FILE SEED_SECRETS_FILE SEED_EMPTY_SECRETS_FILE
}

echo "rebuild.sh fleet-setup tests"

run_case "an IPv4 wildcard FLEET_BIND_ADDR is rejected" 1 \
    "wildcard address" "" \
    fleet-db -- \
    FLEET_BIND_ADDR=0.0.0.0

run_case "an IPv6 wildcard FLEET_BIND_ADDR is rejected" 1 \
    "wildcard address" "" \
    fleet-db -- \
    FLEET_BIND_ADDR="::"

run_case "a non-wildcard FLEET_BIND_ADDR is accepted and exported to compose" 0 \
    "Fleet services will bind to 100.64.1.2" "" \
    fleet-db -- \
    FLEET_BIND_ADDR=100.64.1.2

SEED_DB_FILE="PG_VERSION"
run_case "a missing secret with already-initialized data aborts instead of regenerating" 1 \
    "contains initialized state" "Generating fleet-db-password" \
    fleet-db -- \
    FLEET_BIND_ADDR=100.64.1.2

run_case "a missing secret with an empty data directory generates a fresh one" 0 \
    "Generating fleet-db-password" "" \
    fleet-db -- \
    FLEET_BIND_ADDR=100.64.1.2

SEED_EMPTY_SECRETS_FILE="fleet-db-password"
SEED_DB_FILE="PG_VERSION"
run_case "a zero-byte secret with already-initialized data aborts instead of treating it as present" 1 \
    "contains initialized state" "Generating fleet-db-password" \
    fleet-db -- \
    FLEET_BIND_ADDR=100.64.1.2

SEED_EMPTY_SECRETS_FILE="fleet-db-password"
run_case "a zero-byte secret with an empty data directory regenerates a fresh one" 0 \
    "Generating fleet-db-password" "" \
    fleet-db -- \
    FLEET_BIND_ADDR=100.64.1.2

run_case "a single non-fleet service does not require a tailnet address" 0 \
    "" "ERROR: no tailnet address found" \
    flowtree-controller --

if grep -qF "FLEET_BIND_ADDR=127.0.0.1" "$MOCK_DOCKER_LOG"; then
  PASS=$((PASS + 1))
  echo "  PASS: a single non-fleet service still exports a harmless FLEET_BIND_ADDR placeholder for compose interpolation"
else
  FAIL=$((FAIL + 1))
  FAILED_TESTS+=("a single non-fleet service still exports a harmless FLEET_BIND_ADDR placeholder for compose interpolation")
  echo "  FAIL: a single non-fleet service still exports a harmless FLEET_BIND_ADDR placeholder for compose interpolation"
  echo "    --- docker mock log ---"
  sed 's/^/    /' "$MOCK_DOCKER_LOG"
fi

echo ""
if [ "$FAIL" -eq 0 ]; then
  echo "All $PASS tests passed."
  exit 0
else
  echo "$FAIL of $((PASS + FAIL)) tests failed:"
  for t in "${FAILED_TESTS[@]}"; do
    echo "  - $t"
  done
  exit 1
fi
