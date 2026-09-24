#!/bin/bash

# Read JSON input from stdin
input=$(cat)

# Extract git branch
git_branch=$(cd "$(echo "$input" | sed -n 's/.*"current_dir"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')" 2>/dev/null && git branch --show-current 2>/dev/null || echo "")

# Extract context window percentage using sed (no jq dependency)
used_pct=$(echo "$input" | sed -n 's/.*"used_percentage"[[:space:]]*:[[:space:]]*\([0-9.]*\).*/\1/p' | head -1)

# Session spend, as Claude Code itself reports it (cost.total_cost_usd).
#
# This used to be computed here, multiplying token counts by hardcoded Opus
# 4.5 rates, which was wrong twice over: the rates went stale with every
# model release (Opus 5.5 is $4/$20 per Mtok, not $15/$75), and
# total_input_tokens/total_output_tokens are the tokens *currently in the
# context window*, not the session's cumulative usage — so the figure was
# not a session total at all. The field below is the real one, priced per
# the model actually running, and needs no maintenance here. It resets to $0
# when /clear starts a new session.
total_spend=$(echo "$input" | sed -n 's/.*"total_cost_usd"[[:space:]]*:[[:space:]]*\([0-9.]*\).*/\1/p' | head -1)
total_spend=${total_spend:-0}
spend_display=$(printf "\$%.4f" "$total_spend")

# Build progress bar for context window usage
bar_width=20
used_pct=${used_pct:-0}
if command -v bc &>/dev/null; then
    filled=$(echo "scale=0; $used_pct * $bar_width / 100" | bc)
else
    filled=$(awk "BEGIN {printf \"%d\", $used_pct * $bar_width / 100}")
fi
filled=${filled:-0}
empty=$((bar_width - filled))

bar=""
for ((i=0; i<filled; i++)); do bar+="="; done
for ((i=0; i<empty; i++)); do bar+=" "; done
context_display="[$bar] ${used_pct%.*}%"

# ANSI color codes
RED='\033[31m'
GREEN='\033[32m'
BLUE='\033[34m'
RESET='\033[0m'

# Color the git branch based on name
if [ -n "$git_branch" ]; then
    case "$git_branch" in
        master|main)
            colored_branch="${RED}${git_branch}${RESET}"
            ;;
        develop)
            colored_branch="${GREEN}${git_branch}${RESET}"
            ;;
        *)
            colored_branch="${BLUE}${git_branch}${RESET}"
            ;;
    esac
fi

# Format output with 2 spaces around pipes
output=""
[ -n "$git_branch" ] && output="${colored_branch}  |  "
output+="${context_display}  |  ${spend_display}"

printf "%b" "$output"
