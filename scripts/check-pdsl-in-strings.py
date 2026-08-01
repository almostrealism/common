#!/usr/bin/env python3
"""
check-pdsl-in-strings.py — Fail the build when PDSL source code is embedded
in Java string literals instead of being loaded from .pdsl resource files.

PDSL code must live in .pdsl files and be loaded at runtime via:
    PdslLoader.parse(Paths.get("path/to/file.pdsl"))

Embedding PDSL in Java string literals (single-line, concatenated, or text-block)
makes the DSL impossible to review, syntax-highlight, or validate independently.

Exit codes:
    0 — no violations found
    1 — one or more violations found
"""

import re
import sys
import os
from pathlib import Path


# ---------------------------------------------------------------------------
# PDSL-specific patterns.  These combinations do not occur in normal Java code.
# Each entry is (pattern, human-readable description).
# ---------------------------------------------------------------------------
PDSL_SIGNATURES = [
    # layer declaration with PDSL type annotations.
    # "layer" is not a Java keyword; ": weight" / ": float" / ": int" /
    # ": scalar" are PDSL-only type annotations.
    (re.compile(
        r'layer\s+\w+\s*\([^)]*:\s*(?:weight|float|int|scalar)\b',
        re.DOTALL),
     "PDSL layer declaration with type-annotated parameters"),

    # accum { ... } — PDSL residual-connection block.
    # In Java, "accum {" is not valid syntax (labels require a colon).
    (re.compile(r'\baccum\s*\{'),
     "PDSL accum { } residual-connection block"),

    # product( { ... }, { ... } ) — PDSL element-wise composition.
    (re.compile(r'\bproduct\s*\(\s*\{'),
     "PDSL product( { } ) composition construct"),
]


def extract_string_regions(source: str) -> list[tuple[int, int, str]]:
    """Return a list of (start_line, end_line, content) for every string
    literal in the Java source, including text blocks (triple-quoted strings).

    Comments are excluded.  Escaped quotes inside strings are handled.

    Args:
        source: full Java source text.

    Returns:
        List of tuples (start_line_1indexed, end_line_1indexed, string_content).
    """
    regions = []
    i = 0
    n = len(source)
    line_num = 1  # 1-indexed current line

    def count_newlines(s: str) -> int:
        return s.count('\n')

    while i < n:
        # Track line numbers as we advance
        c = source[i]

        # Skip single-line comments
        if c == '/' and i + 1 < n and source[i + 1] == '/':
            end = source.find('\n', i)
            if end == -1:
                break
            line_num += count_newlines(source[i:end + 1])
            i = end + 1
            continue

        # Skip block comments
        if c == '/' and i + 1 < n and source[i + 1] == '*':
            end = source.find('*/', i + 2)
            if end == -1:
                break
            block = source[i:end + 2]
            line_num += count_newlines(block)
            i = end + 2
            continue

        # Text block: """..."""
        if c == '"' and source[i:i + 3] == '"""':
            start_line = line_num
            i += 3  # skip opening """
            # skip optional whitespace/newline after opening """
            end = source.find('"""', i)
            if end == -1:
                break
            content = source[i:end]
            line_num += count_newlines(source[i - 3:end + 3])
            regions.append((start_line, line_num, content))
            i = end + 3
            continue

        # Regular string literal: "..."
        if c == '"':
            start_line = line_num
            i += 1
            content_chars = []
            while i < n:
                sc = source[i]
                if sc == '\\':
                    # escaped character — include both and skip
                    if i + 1 < n:
                        content_chars.append(source[i:i + 2])
                        if source[i + 1] == '\n':
                            line_num += 1
                        i += 2
                    else:
                        i += 1
                    continue
                if sc == '"':
                    i += 1
                    break
                if sc == '\n':
                    # Unterminated string (shouldn't happen in valid Java
                    # outside text blocks) — stop here
                    line_num += 1
                    i += 1
                    break
                content_chars.append(sc)
                i += 1
            content = ''.join(content_chars)
            regions.append((start_line, line_num, content))
            continue

        if c == '\n':
            line_num += 1
        i += 1

    return regions


def check_file(path: Path) -> list[tuple[int, str, str]]:
    """Check a single Java file for PDSL embedded in string literals.

    Args:
        path: path to the .java file.

    Returns:
        List of (line_number, matched_text, description) tuples.
    """
    try:
        source = path.read_text(encoding='utf-8', errors='replace')
    except OSError:
        return []

    violations = []
    for start_line, _end_line, content in extract_string_regions(source):
        for pattern, description in PDSL_SIGNATURES:
            m = pattern.search(content)
            if m:
                # Estimate the line within the string where the match starts
                prefix = content[:m.start()]
                match_line = start_line + prefix.count('\n')
                violations.append((match_line, m.group(0).strip(), description))
                break  # one violation per string region is enough

    return violations


def main() -> int:
    """Scan Java source files for PDSL embedded in string literals.

    Returns:
        Exit code: 0 if clean, 1 if violations found.
    """
    args = sys.argv[1:]

    # Determine roots to scan
    if args:
        roots = [Path(a) for a in args]
    else:
        # Default: scan the whole repo from the script's parent directory
        repo_root = Path(__file__).resolve().parent.parent
        roots = [repo_root]

    # Collect Java files, excluding generated sources and build output
    java_files = []
    for root in roots:
        for java_file in root.rglob('*.java'):
            parts = java_file.parts
            if 'target' in parts or 'generated' in parts:
                continue
            java_files.append(java_file)

    java_files.sort()

    total_violations = 0
    for java_file in java_files:
        file_violations = check_file(java_file)
        for line_num, matched, description in file_violations:
            print(f"{java_file}:{line_num}: [pdsl-in-string] {description}")
            print(f"    Found: {matched!r}")
            total_violations += 1

    if total_violations > 0:
        print()
        print(f"FAIL: {total_violations} PDSL-in-string violation(s) found.")
        print("  PDSL code belongs in .pdsl resource files, not Java string literals.")
        print("  Load with: PdslLoader.parse(Paths.get(\"path/to/model.pdsl\"))")
        return 1

    print(f"OK: No PDSL code found in Java string literals "
          f"({len(java_files)} files scanned).")
    return 0


if __name__ == '__main__':
    sys.exit(main())
