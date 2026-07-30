#!/bin/bash

# Read JSON input from stdin
input=$(cat)

# Extract git branch
git_branch=$(cd "$(echo "$input" | sed -n 's/.*"current_dir"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')" 2>/dev/null && git branch --show-current 2>/dev/null || echo "")

# Extract context window percentage using sed (no jq dependency)
used_pct=$(echo "$input" | sed -n 's/.*"used_percentage"[[:space:]]*:[[:space:]]*\([0-9.]*\).*/\1/p' | head -1)

# Extract token counts for spend calculation
total_input=$(echo "$input" | sed -n 's/.*"total_input_tokens"[[:space:]]*:[[:space:]]*\([0-9]*\).*/\1/p' | head -1)
total_output=$(echo "$input" | sed -n 's/.*"total_output_tokens"[[:space:]]*:[[:space:]]*\([0-9]*\).*/\1/p' | head -1)

# Calculate total spend (Opus 4.5 pricing: $15 input, $75 output per million tokens)
total_input=${total_input:-0}
total_output=${total_output:-0}
if command -v bc &>/dev/null; then
    total_spend=$(echo "scale=4; ($total_input * 15 + $total_output * 75) / 1000000" | bc)
else
    # Fallback: rough calculation using awk
    total_spend=$(awk "BEGIN {printf \"%.4f\", ($total_input * 15 + $total_output * 75) / 1000000}")
fi
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
