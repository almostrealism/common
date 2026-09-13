#!/usr/bin/env bash
# ─── Shared rendering for the agent prompt builders ────────────────
#
# Sourced, not executed, by the build-*-prompt.sh scripts in this
# directory. It gives every builder one way to turn a template into
# a prompt, so that a block of instructions shared by several tasks
# — the review-feedback policy in pr-feedback.txt, for instance —
# lives in exactly one file and is patched into each prompt from
# there rather than copied into each of them.
#
# A template is plain text with two kinds of markup:
#
#   ${NAME}            replaced by the environment variable NAME, for
#                      every NAME the caller lists (unlisted ones are
#                      left alone, and the prompt-builder tests reject
#                      any placeholder that survives to the output)
#   @include <file>    a whole line, replaced by the named fragment
#                      from this directory; the fragment's own
#                      placeholders are substituted the same way
#
# Usage (from a builder that has already validated its variables):
#
#   source "${SCRIPT_DIR}/prompt-render.sh"
#   render_prompt "${SCRIPT_DIR}/general-review.txt" BRANCH BASE_BRANCH COMMIT_SHA > "$OUTPUT_FILE"
#   render_prompt "${SCRIPT_DIR}/pr-feedback.txt" BRANCH >> "$OUTPUT_FILE"

PROMPT_FRAGMENT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Prints a template with every `@include <file>` line replaced by that
# file's contents. Fragments are not themselves expanded for includes:
# one level is all the prompts need, and it keeps a fragment from ever
# including itself.
expand_prompt_includes() {
    local template="$1"
    local line
    while IFS= read -r line || [ -n "$line" ]; do
        case "$line" in
            "@include "*)
                local fragment="${PROMPT_FRAGMENT_DIR}/${line#@include }"
                if [ ! -f "$fragment" ]; then
                    echo "ERROR: Fragment not found at ${fragment}" >&2
                    return 1
                fi
                cat "$fragment"
                ;;
            *)
                printf '%s\n' "$line"
                ;;
        esac
    done < "$template"
}

# Escapes a value for use as the replacement half of a `sed s|...|...|`
# expression: a literal backslash or `&` is otherwise special to sed, and a
# literal `|` would otherwise close the expression early. Backslashes are
# escaped first, so escaping the later characters cannot introduce a
# backslash that then gets mistaken for part of the original value.
escape_sed_replacement() {
    local value="$1"
    value="${value//\\/\\\\}"
    value="${value//&/\\&}"
    value="${value//|/\\|}"
    printf '%s' "$value"
}

# Prints a template with its includes expanded and each named variable
# substituted for its ${NAME} placeholder.
#
#   render_prompt <template-file> [NAME...]
render_prompt() {
    local template="$1"
    shift
    if [ ! -f "$template" ]; then
        echo "ERROR: Template not found at ${template}" >&2
        return 1
    fi
    local expressions=()
    local name
    for name in "$@"; do
        expressions+=(-e "s|\${${name}}|$(escape_sed_replacement "${!name}")|g")
    done
    if [ "${#expressions[@]}" -eq 0 ]; then
        expand_prompt_includes "$template"
    else
        expand_prompt_includes "$template" | sed "${expressions[@]}"
    fi
}

# Appends a rendered fragment from this directory to a prompt that is
# being assembled in pieces, as the builders that write their prompt
# with heredocs do, keeping one blank line on each side of it whatever
# the surrounding heredocs end and begin with.
#
#   append_prompt_fragment <fragment-name> <output-file> [NAME...]
append_prompt_fragment() {
    local fragment="${PROMPT_FRAGMENT_DIR}/$1"
    local output="$2"
    shift 2
    if [ -s "$output" ] && [ -n "$(tail -c 2 "$output" | tr -d '\n')" ]; then
        printf '\n' >> "$output"
    fi
    { render_prompt "$fragment" "$@"; printf '\n'; } >> "$output"
}
