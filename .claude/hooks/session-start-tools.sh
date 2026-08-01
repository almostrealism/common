#!/usr/bin/env bash
# SessionStart — emit paths and versions of common development tools.
#
# Runs once when the Claude Code session begins. Writes to stdout so the
# content is injected into the session context. This saves the agent from
# burning 3–5 round trips at the start of each task discovering which
# binaries live where and whether they are current.
#
# Additions are cheap: append another entry to the TOOLS array. Keep
# version-probing commands to a single subprocess each — anything that
# spawns a VM (Java) is slow enough to hurt session startup.

set -uo pipefail  # NOTE: no -e; one broken tool probe must not skip the rest

# Each entry: "<command>|<version-args>"
# Order is loosely by frequency-of-use in this project.
declare -a TOOLS=(
    "java|-version"
    "mvn|-v"
    "python3|--version"
    "pip3|--version"
    "node|--version"
    "npm|--version"
    "git|--version"
    "gh|--version"
    "curl|--version"
    "docker|--version"
    "make|--version"
    "gcc|--version"
    "g++|--version"
)

printf '── session-tools: detected development binaries ──────────────────────\n'

missing=()
for entry in "${TOOLS[@]}"; do
    cmd="${entry%%|*}"
    args="${entry#*|}"
    path="$(command -v "$cmd" 2>/dev/null || true)"
    if [[ -z "$path" ]]; then
        missing+=("$cmd")
        continue
    fi

    # Probe version. Some tools write to stderr (java), some to stdout.
    # Capture both, take the first non-empty line, strip ANSI colour codes,
    # cap to a reasonable width (curl's banner is ~400 chars).
    ver="$("$cmd" $args 2>&1 | sed -E 's/\x1B\[[0-9;]*[A-Za-z]//g' | head -n 1 || true)"
    ver="${ver#"${ver%%[![:space:]]*}"}"
    ver="${ver%"${ver##*[![:space:]]}"}"
    if [[ ${#ver} -gt 80 ]]; then
        ver="${ver:0:80}…"
    fi

    printf '  %-10s %s   (%s)\n' "$cmd" "$path" "${ver:-unknown}"
done

if [[ ${#missing[@]} -gt 0 ]]; then
    printf '  not on PATH: %s\n' "${missing[*]}"
fi

printf '── end session-tools ─────────────────────────────────────────────────\n'

exit 0
