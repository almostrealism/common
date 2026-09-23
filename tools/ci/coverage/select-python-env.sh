#!/usr/bin/env bash
# ─── Select a Python interpreter and provision (or reuse) a venv for it ──
#
# Prints the path to a venv's python3 executable on stdout — nothing else.
# All diagnostics (::notice::/::error::) go to stderr so
# `PYTHON=$(select-python-env.sh ...)` stays clean.
#
# The venv is cached at VENV_DIR across invocations. Reuse is gated on a
# marker file (${VENV_DIR}/.provision-marker) recording the selected
# interpreter's path and version, a hash of REQUIREMENTS_FILE, and a hash
# of the extra pip package arguments ("$@"). Any mismatch — a newer
# interpreter appears on PATH, requirements.txt changes, or the caller's
# extra package list changes — triggers `rm -rf "$VENV_DIR"` and a full
# reprovision. Without this, a venv created once by an old interpreter (or
# against a stale requirements.txt) is reused forever: pip filters package
# releases by `Requires-Python`, so an old interpreter can silently make
# every release of a dependency look unavailable ("from versions: none").
#
# Candidates are validated by their actual reported sys.version_info, not
# by trusting the binary name: a bare `python3` can resolve to an
# OS-bundled interpreter far older than its name implies.
#
# Usage:
#   REQUIREMENTS_FILE=<path> [VENV_DIR=<path>] [MIN_PYTHON_VERSION=3.10] \
#       select-python-env.sh [extra pip package]...
#
# Required environment variables:
#   REQUIREMENTS_FILE - path to a pip requirements file to install
#
# Optional environment variables:
#   VENV_DIR            - venv cache directory (default: ~/.cache/ar-coverage-qa/venv)
#   MIN_PYTHON_VERSION  - minimum required "major.minor" version (default: 3.10)
#
# Exit codes:
#   0 - a provisioned (or reused) venv's python3 path was printed
#   1 - invalid arguments, or no interpreter on PATH satisfies MIN_PYTHON_VERSION

set -euo pipefail

if [ -z "${REQUIREMENTS_FILE:-}" ]; then
    echo "::error::REQUIREMENTS_FILE must be set" >&2
    exit 1
fi
if [ ! -f "$REQUIREMENTS_FILE" ]; then
    echo "::error::REQUIREMENTS_FILE '${REQUIREMENTS_FILE}' does not exist" >&2
    exit 1
fi

VENV_DIR="${VENV_DIR:-${HOME}/.cache/ar-coverage-qa/venv}"
MIN_PYTHON_VERSION="${MIN_PYTHON_VERSION:-3.10}"
MIN_MAJOR="${MIN_PYTHON_VERSION%%.*}"
MIN_MINOR="${MIN_PYTHON_VERSION##*.}"

# ─── Select the newest interpreter on PATH that actually satisfies the
# minimum version, verified via sys.version_info rather than trusted from
# the candidate's name. ───────────────────────────────────────────────
interpreter_version() {
    "$1" -c 'import sys; print("%d.%d" % sys.version_info[:2])' 2>/dev/null
}

version_at_least() {
    local major="${1%%.*}" minor="${1##*.}"
    [ "$major" -gt "$MIN_MAJOR" ] || { [ "$major" -eq "$MIN_MAJOR" ] && [ "$minor" -ge "$MIN_MINOR" ]; }
}

PYTHON_BIN=""
PYTHON_VERSION=""
for candidate in python3.14 python3.13 python3.12 python3.11 python3.10 python3; do
    if ! command -v "$candidate" >/dev/null 2>&1; then
        continue
    fi
    version=$(interpreter_version "$candidate") || continue
    if version_at_least "$version"; then
        PYTHON_BIN=$(command -v "$candidate")
        PYTHON_VERSION="$version"
        break
    fi
done

if [ -z "$PYTHON_BIN" ]; then
    echo "::error::No python3 interpreter on PATH satisfies the minimum required version ${MIN_PYTHON_VERSION} (checked python3.14, python3.13, python3.12, python3.11, python3.10, python3)" >&2
    exit 1
fi

# ─── Provision (or reuse) the venv, gated on a marker recording everything
# that determines its contents. sha256sum is a GNU coreutil and is absent
# from a stock macOS runner; shasum -a 256 is the BSD/macOS equivalent. ──
sha256_hash() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$@"
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$@"
    else
        echo "::error::Neither sha256sum nor shasum is available on PATH" >&2
        exit 1
    fi
}

REQUIREMENTS_HASH=$(sha256_hash "$REQUIREMENTS_FILE" | cut -d' ' -f1)
# One argument per line (not "$*", which joins on a single space and makes
# ["a b"] and ["a", "b"] hash identically) so the marker distinguishes any
# change to the caller's extra pip-package list.
EXTRA_ARGS_HASH=$(printf '%s\n' "$@" | sha256_hash | cut -d' ' -f1)
EXPECTED_MARKER="${PYTHON_BIN}@${PYTHON_VERSION} ${REQUIREMENTS_HASH} ${EXTRA_ARGS_HASH}"
MARKER_FILE="${VENV_DIR}/.provision-marker"

CURRENT_MARKER=""
if [ -f "$MARKER_FILE" ]; then
    CURRENT_MARKER=$(cat "$MARKER_FILE")
fi

if [ "$CURRENT_MARKER" != "$EXPECTED_MARKER" ]; then
    if [ -n "$CURRENT_MARKER" ]; then
        echo "::notice::Recreating stale coverage-qa Python venv at ${VENV_DIR} (interpreter, requirements, or extra packages changed)" >&2
    else
        echo "::notice::Creating coverage-qa Python venv with ${PYTHON_BIN} (${PYTHON_VERSION})" >&2
    fi
    rm -rf "$VENV_DIR"
    mkdir -p "$(dirname "$VENV_DIR")"
    "$PYTHON_BIN" -m venv "$VENV_DIR"

    VENV_PYTHON="${VENV_DIR}/bin/python3"
    "$VENV_PYTHON" -m pip install --quiet --upgrade pip
    "$VENV_PYTHON" -m pip install --quiet -r "$REQUIREMENTS_FILE" "$@"

    echo "$EXPECTED_MARKER" > "$MARKER_FILE"
else
    echo "::notice::Reusing coverage-qa Python venv at ${VENV_DIR}" >&2
fi

echo "${VENV_DIR}/bin/python3"
