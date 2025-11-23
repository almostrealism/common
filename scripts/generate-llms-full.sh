#!/bin/bash
# Generate llms-full.txt from documentation sources
# Run from common/ directory: ./scripts/generate-llms-full.sh

set -e

OUTPUT="llms-full.txt"
DOCS_DIR="docs"

echo "Generating $OUTPUT..."

cat > "$OUTPUT" << 'HEADER'
# Almost Realism Framework - Complete Documentation

> Hardware-accelerated computation framework for Java with support for CPU, GPU (OpenCL), and Metal backends.

This file contains concatenated documentation for AI agents. For navigation, see llms.txt.

---

HEADER

# Add Quick Reference
echo "## Quick Reference" >> "$OUTPUT"
echo "" >> "$OUTPUT"
cat docs/QUICK_REFERENCE.md >> "$OUTPUT"
echo "" >> "$OUTPUT"
echo "---" >> "$OUTPUT"
echo "" >> "$OUTPUT"

# Add CLAUDE.md (development guidelines)
echo "## Development Guidelines (CLAUDE.md)" >> "$OUTPUT"
echo "" >> "$OUTPUT"
cat CLAUDE.md >> "$OUTPUT"
echo "" >> "$OUTPUT"
echo "---" >> "$OUTPUT"
echo "" >> "$OUTPUT"

# Add key module READMEs
MODULES="relation code collect algebra graph ml hardware time"

for module in $MODULES; do
    if [ -f "$module/README.md" ]; then
        echo "## Module: $module" >> "$OUTPUT"
        echo "" >> "$OUTPUT"
        cat "$module/README.md" >> "$OUTPUT"
        echo "" >> "$OUTPUT"
        echo "---" >> "$OUTPUT"
        echo "" >> "$OUTPUT"
    fi
done

# Add ML-specific CLAUDE.md if exists
if [ -f "ml/CLAUDE.md" ]; then
    echo "## ML Development Notes" >> "$OUTPUT"
    echo "" >> "$OUTPUT"
    cat ml/CLAUDE.md >> "$OUTPUT"
    echo "" >> "$OUTPUT"
fi

echo "Generated $OUTPUT ($(wc -c < "$OUTPUT") bytes)"
