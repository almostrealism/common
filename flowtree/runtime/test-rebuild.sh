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
# PATH so the script never attempts a real build or compose invocation; the
# docker mock records every invocation, and fails any whose arguments contain
# MOCK_DOCKER_FAIL_ON, so the post-deploy prune can be checked as well.
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
if [ -n "${MOCK_DOCKER_FAIL_ON:-}" ] && [[ "$*" == *"${MOCK_DOCKER_FAIL_ON}"* ]]; then
  exit 1
fi
if [ -n "${MOCK_DOCKER_FAIL_ON_2:-}" ] && [[ "$*" == *"${MOCK_DOCKER_FAIL_ON_2}"* ]]; then
  exit 1
fi
# A running stack, for the snapshot a swap restores on failure.
case "$*" in
  *" ps -q"*) [ -z "${MOCK_DOCKER_PS_IDS:-}" ] || echo "${MOCK_DOCKER_PS_IDS}" ;;
  inspect*)   [ -z "${MOCK_DOCKER_INSPECT:-}" ] || echo "${MOCK_DOCKER_INSPECT}" ;;
  # The preflight counts the documentation corpus in the new ar-manager image.
  *" exec -T ar-manager "*) echo "${MOCK_DOCS_COUNT:-120}" ;;
esac
exit 0
EOF
chmod +x "$MOCK_BIN/docker"

cat > "$MOCK_BIN/mvn" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
chmod +x "$MOCK_BIN/mvn"

# rebuild.sh's closing (non-fatal) public-endpoint health check resolves a
# name through a public DNS resolver with `dig @1.1.1.1` and probes it with
# `curl`. Neither is a deployment command, but both reach the live internet,
# and the suite drives rebuild.sh dozens of times — on a runner where
# dnsutils is installed that is dozens of real DNS/HTTP round-trips, enough
# to exceed the test's wall-clock budget. Mock them so the check takes its
# healthy path (a resolvable address, an HTTP 401 from the authenticated
# edge) entirely offline, keeping the suite's promise that it never touches
# a real deployment. (`dig`/`curl` are used nowhere else these tests reach.)
cat > "$MOCK_BIN/dig" <<'EOF'
#!/usr/bin/env bash
echo "203.0.113.1"
EOF
chmod +x "$MOCK_BIN/dig"

cat > "$MOCK_BIN/curl" <<'EOF'
#!/usr/bin/env bash
echo "401"
EOF
chmod +x "$MOCK_BIN/curl"

cleanup() {
  rm -rf "$MOCK_BIN"
  rm -f "$MOCK_DOCKER_LOG"
}
trap cleanup EXIT

# run_case NAME EXPECT_EXIT MUST_CONTAIN MUST_NOT_CONTAIN -- SERVICES... -- ENV=VAL...
#
# Runs rebuild.sh with fresh temp SECRETS_DIR/FLEET_DB_DATA_DIR/
# FLEET_GRAFANA_DATA_DIR, a fresh FLEET_COMPOSE_ENV_FILE and a fresh
# FLOWTREE_CONTROLLER_ENV (operator settings file, created with the contents of
# the SEED_CONTROLLER_ENV global when a caller sets it and otherwise absent),
# optionally seeding the data/secret files via the SEED_DB_FILE /
# SEED_SECRETS_FILE globals, the given service-name arguments, and the given
# extra environment variables. Every path the script writes is a temp path:
# a case must neither see what an earlier case persisted nor leave anything
# in the checkout it runs from. After the run the compose env file's contents
# are captured in the LAST_COMPOSE_ENV global so a check can assert what
# rebuild.sh carried into it.
run_case() {
  local name="$1" expect_exit="$2" must_contain="$3" must_not_contain="$4"
  shift 4
  local services=()
  while [ "$1" != "--" ]; do
    services+=("$1")
    shift
  done
  shift

  local tmp_secrets tmp_db tmp_grafana tmp_compose_env tmp_controller_dir out actual_exit
  tmp_secrets="$(mktemp -d)"
  tmp_db="$(mktemp -d)"
  tmp_grafana="$(mktemp -d)"
  tmp_compose_env="$(mktemp -d)/.env"
  # The operator settings file lives in its own temp dir and is created only
  # when the case seeds it, so an unseeded case exercises the file-absent path.
  tmp_controller_dir="$(mktemp -d)"
  local tmp_controller_env="$tmp_controller_dir/controller.env"
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
  if [ -n "${SEED_CONTROLLER_ENV:-}" ]; then
    printf '%s\n' "${SEED_CONTROLLER_ENV}" > "$tmp_controller_env"
    if [ -n "${SEED_CONTROLLER_ENV_MODE:-}" ]; then
      chmod "${SEED_CONTROLLER_ENV_MODE}" "$tmp_controller_env"
    fi
  fi
  # Create the settings path as a directory instead of a file, to exercise the
  # non-regular-file guard (a directory or dangling symlink must fail closed,
  # not be mistaken for an absent file).
  if [ -n "${SEED_CONTROLLER_ENV_DIR:-}" ]; then
    mkdir -p "$tmp_controller_env"
  fi
  # Seed the settings file but make its directory unsearchable (no execute
  # bit), so the path cannot be stat-ed. This stands in for a configured
  # location under an inaccessible parent or an unavailable mount: the file is
  # present but `[ -e ]`/`[ -L ]` are both false, which must fail closed rather
  # than be taken for an absent file. Restored before cleanup below.
  if [ -n "${SEED_CONTROLLER_ENV_PARENT_UNSEARCHABLE:-}" ]; then
    printf '%s\n' "AR_CONSULTANT_BACKEND=openrouter" > "$tmp_controller_env"
    chmod 000 "$tmp_controller_dir"
  fi

  env -i PATH="$MOCK_BIN:$PATH" HOME="${HOME:-/tmp}" \
      SECRETS_DIR="$tmp_secrets" \
      FLEET_DB_DATA_DIR="$tmp_db" \
      FLEET_GRAFANA_DATA_DIR="$tmp_grafana" \
      FLEET_COMPOSE_ENV_FILE="$tmp_compose_env" \
      FLOWTREE_CONTROLLER_ENV="$tmp_controller_env" \
      MOCK_DOCKER_LOG="$MOCK_DOCKER_LOG" \
      "$@" \
      bash "$SCRIPT" ${services[@]+"${services[@]}"} >"$out" 2>&1
  actual_exit=$?

  if [ -f "$tmp_compose_env" ]; then
    LAST_COMPOSE_ENV="$(cat "$tmp_compose_env")"
    LAST_COMPOSE_ENV_PERMS="$(ls -l "$tmp_compose_env" | cut -c1-10)"
  else
    LAST_COMPOSE_ENV=""
    LAST_COMPOSE_ENV_PERMS=""
  fi

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

  # Restore search on the controller dir in case a case removed it, so rm can
  # recurse into it.
  chmod 700 "$tmp_controller_dir" 2>/dev/null || true
  rm -rf "$tmp_secrets" "$tmp_db" "$tmp_grafana" "$(dirname "$tmp_compose_env")" \
    "$tmp_controller_dir" "$out"
  unset SEED_DB_FILE SEED_SECRETS_FILE SEED_EMPTY_SECRETS_FILE SEED_CONTROLLER_ENV \
    SEED_CONTROLLER_ENV_MODE SEED_CONTROLLER_ENV_DIR SEED_CONTROLLER_ENV_PARENT_UNSEARCHABLE
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

# check_docker_log NAME CONDITION...
#
# Records a pass or failure for NAME according to whether CONDITION (a
# command run against the docker mock log of the most recent case) succeeds.
check_docker_log() {
  local name="$1"
  shift
  if "$@"; then
    PASS=$((PASS + 1))
    echo "  PASS: $name"
  else
    FAIL=$((FAIL + 1))
    FAILED_TESTS+=("$name")
    echo "  FAIL: $name"
    echo "    --- docker mock log ---"
    sed 's/^/    /' "$MOCK_DOCKER_LOG"
  fi
}

# first_line TEXT: line number of TEXT's first occurrence in the mock log.
first_line() {
  grep -nF -- "$1" "$MOCK_DOCKER_LOG" | head -1 | cut -d: -f1
}

# prunes_follow_restart: both prunes ran, and only after the containers were
# recreated, so nothing a running container needs could have been removed.
prunes_follow_restart() {
  local up image builder
  up="$(first_line "up -d --force-recreate")"
  image="$(first_line "ARGS image prune -f")"
  builder="$(first_line "ARGS builder prune -f --max-used-space ${1}")"
  [ -n "$up" ] && [ -n "$image" ] && [ -n "$builder" ] \
    && [ "$image" -gt "$up" ] && [ "$builder" -gt "$up" ]
}

no_prune() {
  ! grep -qF " prune" "$MOCK_DOCKER_LOG"
}

echo ""
echo "rebuild.sh post-deploy prune tests"

run_case "a successful rebuild reports the prune" 0 \
    "Pruning superseded images and build cache beyond 20gb" "" \
    flowtree-controller --
check_docker_log "superseded images and build cache are pruned after the restart" \
    prunes_follow_restart 20gb

run_case "BUILD_CACHE_MAX_USED_SPACE is accepted" 0 \
    "beyond 5gb" "" \
    flowtree-controller -- \
    BUILD_CACHE_MAX_USED_SPACE=5gb
check_docker_log "BUILD_CACHE_MAX_USED_SPACE sets the build cache budget" \
    prunes_follow_restart 5gb

run_case "a failed build fails the rebuild" 1 \
    "" "Pruning superseded images" \
    flowtree-controller -- \
    MOCK_DOCKER_FAIL_ON="compose -f flowtree/runtime/controller/docker-compose.yml build"
check_docker_log "nothing is pruned when the build fails" no_prune

run_case "a failed prune is reported without failing a deploy that worked" 0 \
    "WARNING: docker builder prune failed" "" \
    flowtree-controller -- \
    MOCK_DOCKER_FAIL_ON="builder prune"

run_case "a failed image prune is reported without failing a deploy that worked" 0 \
    "WARNING: docker image prune failed" "" \
    flowtree-controller -- \
    MOCK_DOCKER_FAIL_ON="image prune"
check_docker_log "the build cache is still trimmed when the image prune fails" \
    prunes_follow_restart 20gb

# ── Safe replacement ────────────────────────────────────────────────

# in_order TEXT...: each TEXT occurs in the mock log, and the first
# occurrence of each comes before the first occurrence of the next.
in_order() {
  local previous=0 line text
  for text in "$@"; do
    line="$(first_line "${text}")"
    if [ -z "${line}" ] || [ "${line}" -le "${previous}" ]; then
      return 1
    fi
    previous="${line}"
  done
}

absent() {
  ! grep -qF -- "$1" "$MOCK_DOCKER_LOG"
}

# preflight_starts SERVICES: the preflight's `up` names exactly SERVICES
# (space-separated, in order) and nothing else.
preflight_starts() {
  grep -F -- "${PREFLIGHT_UP}" "$MOCK_DOCKER_LOG" | grep -F -- " up -d " \
    | grep -qE -- "--wait-timeout [0-9]+ $1\$"
}

SWAP_UP="up -d --force-recreate --wait"
PREFLIGHT_UP="-p controller-preflight"
AGENT_ENV_ARGS=(FLOWTREE_AGENT_ENV=/nonexistent/agent.env
    FLOWTREE_ROOT_HOST=controller.example CLAUDE_CODE_OAUTH_TOKEN=placeholder)

echo ""
echo "rebuild.sh safe replacement tests"

run_case "a full deploy preflights the new images before replacing anything" 0 \
    "Preflight passed: ar-memory ar-tracker flowtree-controller ar-manager" "" \
    -- \
    FLEET_BIND_ADDR=100.64.1.2
check_docker_log "images are built, then preflighted, then swapped, then pruned" \
    in_order "compose -f flowtree/runtime/controller/docker-compose.yml build" \
        "${PREFLIGHT_UP}" "${SWAP_UP}" "ARGS image prune -f"
check_docker_log "the preflight project is removed after it passes" \
    in_order "${PREFLIGHT_UP}" "down -v --remove-orphans" "${SWAP_UP}"

run_case "a failed preflight stops the deploy with the running stack untouched" 1 \
    "the current stack is untouched" "Pruning superseded images" \
    flowtree-controller -- \
    MOCK_DOCKER_FAIL_ON="--no-build --wait"
check_docker_log "nothing running is replaced when the preflight fails" absent "${SWAP_UP}"
check_docker_log "the preflight project is removed when it fails" \
    in_order "${PREFLIGHT_UP}" "down -v --remove-orphans"

run_case "a single-service rebuild preflights only that service" 0 \
    "Preflight passed: ar-manager" "Preflight passed: ar-memory" \
    ar-manager -- \
    FLEET_BIND_ADDR=100.64.1.2
check_docker_log "the preflight starts only the selected service" preflight_starts ar-manager
check_docker_log "the swap of the selected service follows the preflight" \
    in_order "${PREFLIGHT_UP} -f flowtree/runtime/controller/docker-compose.yml" \
        "${SWAP_UP} --wait-timeout 6000 ar-manager"

run_case "a fleet-only rebuild has nothing to preflight" 0 \
    "" "Preflight" \
    fleet-db -- \
    FLEET_BIND_ADDR=100.64.1.2
check_docker_log "no preflight project is started for fleet services" absent "${PREFLIGHT_UP}"

run_case "a swap that does not become healthy restores the previous images" 1 \
    "The previous controller stack is running again." "Pruning superseded images" \
    flowtree-controller -- \
    MOCK_DOCKER_FAIL_ON="--force-recreate --wait" \
    MOCK_DOCKER_PS_IDS=0123abcd \
    MOCK_DOCKER_INSPECT="flowtree-controller controller-flowtree-controller sha256:previous"
check_docker_log "the previous image is re-tagged and started without a rebuild" \
    in_order "${SWAP_UP}" "ARGS tag sha256:previous controller-flowtree-controller" \
        "up -d --force-recreate --no-build --wait"

run_case "a rollback whose re-tag fails does not claim the previous stack is running" 1 \
    "needs manual attention" "The previous controller stack is running again." \
    flowtree-controller -- \
    MOCK_DOCKER_FAIL_ON="--force-recreate --wait" \
    MOCK_DOCKER_FAIL_ON_2="tag sha256:previous" \
    MOCK_DOCKER_PS_IDS=0123abcd \
    MOCK_DOCKER_INSPECT="flowtree-controller controller-flowtree-controller sha256:previous"
check_docker_log "a rollback whose only re-tag fails recreates nothing rather than every service" \
    absent "up -d --force-recreate --no-build"

run_case "a failed swap with nothing previously running reports there is nothing to restore" 1 \
    "no previous version to restore" "Pruning superseded images" \
    flowtree-controller -- \
    MOCK_DOCKER_FAIL_ON="--force-recreate --wait"

run_case "an agent deploy builds the agent images before replacing the controller stack" 0 \
    "Replacing the agent pool" "" \
    flowtree-controller --agents -- \
    "${AGENT_ENV_ARGS[@]}" TRANSCRIPT_DIR_HOST="$(mktemp -d)"
check_docker_log "every image is built before any container is replaced" \
    in_order "compose -f flowtree/runtime/agent/docker-compose.yml build" "${PREFLIGHT_UP}" \
        "compose -f flowtree/runtime/controller/docker-compose.yml ${SWAP_UP}" \
        "compose -f flowtree/runtime/agent/docker-compose.yml ${SWAP_UP} --wait-timeout 6000 --remove-orphans"

run_case "a failed agent build leaves the controller stack running" 1 \
    "" "Replacing the controller stack" \
    flowtree-controller --agents -- \
    "${AGENT_ENV_ARGS[@]}" TRANSCRIPT_DIR_HOST="$(mktemp -d)" \
    MOCK_DOCKER_FAIL_ON="agent/docker-compose.yml build"
check_docker_log "no container is replaced when an agent image fails to build" absent "${SWAP_UP}"

run_case "a preflight whose ar-manager image has no documentation corpus fails" 1 \
    "no usable documentation corpus" "Replacing the controller stack" \
    -- \
    FLEET_BIND_ADDR=100.64.1.2 MOCK_DOCS_COUNT=3
check_docker_log "nothing running is replaced when the corpus is missing" absent "${SWAP_UP}"

# ── Rehearsal ───────────────────────────────────────────────────────

REHEARSAL_PREFLIGHT_UP="-p flowtree-rehearsal-preflight"

echo ""
echo "rebuild.sh rehearsal tests"

run_case "a rehearsal builds and preflights everything and replaces nothing" 0 \
    "Rehearsal passed" "Replacing the" \
    --rehearse --agents -- \
    FLEET_BIND_ADDR=127.0.0.1 FLOWTREE_HOST_DIR="$(mktemp -d)"
check_docker_log "a rehearsal builds the controller and agent images, then preflights them" \
    in_order "compose -f flowtree/runtime/controller/docker-compose.yml build --no-cache --pull" \
        "compose -f flowtree/runtime/agent/docker-compose.yml build --no-cache --pull" \
        "${REHEARSAL_PREFLIGHT_UP}"
check_docker_log "a rehearsal starts the rehearsal project's own images" \
    in_order "${REHEARSAL_PREFLIGHT_UP}"
check_docker_log "a rehearsal never swaps a stack" absent "--force-recreate"
check_docker_log "a rehearsal never prunes" absent "prune"

# ar-manager exits at startup without a token file, and a rehearsal host has
# none, so the rehearsal must supply one before the preflight starts it.
REHEARSAL_HOST_DIR="$(mktemp -d)"
run_case "a rehearsal supplies the ar-manager token file the preflight needs" 0 \
    "Rehearsal passed" "" \
    --rehearse -- \
    FLEET_BIND_ADDR=127.0.0.1 FLOWTREE_HOST_DIR="${REHEARSAL_HOST_DIR}"
if grep -q '"value": "armt_' "${REHEARSAL_HOST_DIR}/manager/manager-tokens.json" 2>/dev/null; then
  PASS=$((PASS + 1))
  echo "  PASS: the rehearsal wrote a manager token file"
else
  FAIL=$((FAIL + 1))
  FAILED_TESTS+=("the rehearsal wrote a manager token file")
  echo "  FAIL: the rehearsal wrote a manager token file"
fi

run_case "a rehearsal needs no agent credentials" 0 \
    "Rehearsal passed" "is required to rebuild the agent pool" \
    --rehearse --agents -- \
    FLEET_BIND_ADDR=127.0.0.1 FLOWTREE_HOST_DIR="$(mktemp -d)" \
    FLOWTREE_AGENT_ENV=/nonexistent/agent.env

run_case "a rehearsal fails when the new images fail the preflight" 1 \
    "did not pass the preflight" "Rehearsal passed" \
    --rehearse -- \
    FLEET_BIND_ADDR=127.0.0.1 FLOWTREE_HOST_DIR="$(mktemp -d)" \
    MOCK_DOCKER_FAIL_ON="--no-build --wait"

run_case "a rehearsal fails when an image fails to build" 1 \
    "" "Rehearsal passed" \
    --rehearse -- \
    FLEET_BIND_ADDR=127.0.0.1 FLOWTREE_HOST_DIR="$(mktemp -d)" \
    MOCK_DOCKER_FAIL_ON="controller/docker-compose.yml build"

# ── Operator settings carried from a file outside the checkout ───────
#
# The compose project's env file is gitignored and a deploy's checkout clean
# deletes it, so operator settings kept only there reset to their compose
# defaults on every deploy. rebuild.sh instead carries them from the operator
# settings file (FLOWTREE_CONTROLLER_ENV), which lives outside the checkout, into
# the compose env file. These cases assert the file is honoured when present,
# ignored when absent, and that an operator value wins over the computed one.

# check_compose_env NAME CONDITION...: records pass/fail for NAME by whether
# CONDITION (run against the LAST_COMPOSE_ENV captured by the most recent case)
# succeeds.
check_compose_env() {
  local name="$1"
  shift
  if "$@"; then
    PASS=$((PASS + 1))
    echo "  PASS: $name"
  else
    FAIL=$((FAIL + 1))
    FAILED_TESTS+=("$name")
    echo "  FAIL: $name"
    echo "    --- compose env file ---"
    printf '%s\n' "$LAST_COMPOSE_ENV" | sed 's/^/    /'
  fi
}

compose_env_has()   { printf '%s\n' "$LAST_COMPOSE_ENV" | grep -qF -- "$1"; }
compose_env_lacks() { ! printf '%s\n' "$LAST_COMPOSE_ENV" | grep -qF -- "$1"; }
# The operator file can hold credentials, so the derived compose env file must
# be readable by its owner only, whatever the umask.
compose_env_owner_only() { [ "$LAST_COMPOSE_ENV_PERMS" = "-rw-------" ]; }
# The computed FLEET_BIND_ADDR must appear exactly once — write_compose_env
# strips the operator file's own FLEET_BIND_ADDR before appending the resolved
# one, so an operator who also set it in the file gets no duplicate.
fleet_addr_once() {
  [ "$(printf '%s\n' "$LAST_COMPOSE_ENV" | grep -c '^FLEET_BIND_ADDR=')" -eq 1 ]
}

echo ""
echo "rebuild.sh operator settings tests"

SEED_CONTROLLER_ENV="AR_CONSULTANT_BACKEND=openrouter"
run_case "an operator settings file is carried into the compose env on a full deploy" 0 \
    "" "" \
    -- \
    FLEET_BIND_ADDR=100.64.1.2
check_compose_env "the operator's AR_CONSULTANT_BACKEND reaches the compose env" \
    compose_env_has "AR_CONSULTANT_BACKEND=openrouter"
check_compose_env "the computed FLEET_BIND_ADDR is still written alongside it" \
    compose_env_has "FLEET_BIND_ADDR=100.64.1.2"
check_compose_env "the compose env carrying operator settings is owner-only (0600)" \
    compose_env_owner_only

run_case "with no operator settings file the compose env holds only the computed value" 0 \
    "" "" \
    -- \
    FLEET_BIND_ADDR=100.64.1.2
check_compose_env "an absent settings file leaves no operator value behind" \
    compose_env_lacks "AR_CONSULTANT_BACKEND="
check_compose_env "an absent settings file still persists the computed FLEET_BIND_ADDR" \
    compose_env_has "FLEET_BIND_ADDR=100.64.1.2"

SEED_CONTROLLER_ENV="FLEET_BIND_ADDR=100.64.9.9"
run_case "an operator FLEET_BIND_ADDR in the settings file wins over detection" 0 \
    "Fleet services will bind to 100.64.9.9" "" \
    fleet-db --
check_compose_env "the operator's FLEET_BIND_ADDR is the one written to the compose env" \
    compose_env_has "FLEET_BIND_ADDR=100.64.9.9"
check_compose_env "the operator's FLEET_BIND_ADDR is not duplicated in the compose env" \
    fleet_addr_once

SEED_CONTROLLER_ENV="FLEET_BIND_ADDR=100.64.9.9"
run_case "an exported FLEET_BIND_ADDR wins over the settings file" 0 \
    "Fleet services will bind to 100.64.1.2" "" \
    fleet-db -- \
    FLEET_BIND_ADDR=100.64.1.2
check_compose_env "the exported FLEET_BIND_ADDR is the one written to the compose env" \
    compose_env_has "FLEET_BIND_ADDR=100.64.1.2"
check_compose_env "the settings-file FLEET_BIND_ADDR it overrode is not written" \
    compose_env_lacks "FLEET_BIND_ADDR=100.64.9.9"

SEED_CONTROLLER_ENV="AR_CONSULTANT_BACKEND=openrouter"
SEED_CONTROLLER_ENV_MODE=000
run_case "an unreadable operator settings file stops the deploy instead of resetting settings" 1 \
    "could not read the operator settings file" "Fleet services will bind to" \
    -- \
    FLEET_BIND_ADDR=100.64.1.2
check_compose_env "an unreadable settings file writes no compose env with the settings dropped" \
    compose_env_lacks "FLEET_BIND_ADDR=100.64.1.2"

SEED_CONTROLLER_ENV="FLEET_BIND_ADDR=100.64.9.9"
run_case "a settings file holding only FLEET_BIND_ADDR is not mistaken for a read failure" 0 \
    "Fleet services will bind to 100.64.9.9" "could not read" \
    fleet-db --
check_compose_env "the address-only settings file still yields one FLEET_BIND_ADDR" \
    fleet_addr_once

# An unreadable settings file must stop the deploy even when no FLEET_BIND_ADDR
# is exported — the path where the bind-address lookup reads the file first.
# Without the up-front readability guard the lookup aborts under set -e with a
# raw "sed: Permission denied" instead of the explicit refusal, and never on
# the intended message.
SEED_CONTROLLER_ENV="AR_CONSULTANT_BACKEND=openrouter"
SEED_CONTROLLER_ENV_MODE=000
run_case "an unreadable settings file stops the fleet path before the bind-address lookup" 1 \
    "could not read the operator settings file" "Permission denied" \
    fleet-db --
check_compose_env "an unreadable settings file on the no-address path writes no compose env" \
    compose_env_lacks "FLEET_BIND_ADDR="

# A path that exists but is not a regular file (a directory here, as a stand-in
# for a dangling symlink too) must fail closed rather than be taken for an
# absent file, which would carry only FLEET_BIND_ADDR and reset every setting.
SEED_CONTROLLER_ENV_DIR=1
run_case "a non-regular settings path stops the deploy instead of resetting settings" 1 \
    "is not a regular" "Fleet services will bind to" \
    -- \
    FLEET_BIND_ADDR=100.64.1.2
check_compose_env "a non-regular settings path writes no compose env with the settings dropped" \
    compose_env_lacks "FLEET_BIND_ADDR=100.64.1.2"

# A settings file that is present but unreachable because its directory is not
# searchable must fail closed too, not be taken for an absent file — the same
# silent-reset failure as the unreadable-file and non-regular-path cases, by a
# different route (an inaccessible parent or an unavailable mount).
SEED_CONTROLLER_ENV_PARENT_UNSEARCHABLE=1
run_case "an unsearchable settings directory stops the deploy instead of resetting settings" 1 \
    "is not searchable" "Fleet services will bind to" \
    -- \
    FLEET_BIND_ADDR=100.64.1.2
check_compose_env "an unsearchable settings directory writes no compose env with the settings dropped" \
    compose_env_lacks "FLEET_BIND_ADDR=100.64.1.2"

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
