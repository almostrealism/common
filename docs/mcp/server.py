#!/usr/bin/env python3
"""
Almost Realism Documentation MCP Server

Provides tools for AI agents to search and read AR framework documentation.

Usage:
    python server.py

Tools exposed:
    - search_ar_docs: Search documentation by keyword
    - read_ar_module: Read a specific module's documentation
    - list_ar_modules: List available modules
    - read_quick_reference: Get the condensed API reference
"""

import json
import os
import re
from mcp.server import Server
from mcp.server.stdio import stdio_server
from mcp.types import Tool, TextContent
from pathlib import Path
from typing import Optional, Set

# Common synonyms for AR framework concepts
SYNONYMS = {
    "assignment": {"assign", "destination", "target", "write"},
    "destination": {"assignment", "target", "output", "dest"},
    "provider": {"producer", "supplier", "source", "wavedataprovider"},
    "producer": {"provider", "supplier", "computation"},
    "index": {"indices", "indexing", "offset", "position"},
    "traverse": {"traversal", "walk", "iterate", "iteration"},
    "dynamic": {"computed", "runtime", "variable"},
    "short-circuit": {"shortcircuit", "fast-path", "fastpath", "optimization"},
    "collection": {"array", "buffer", "memory", "data"},
    "expression": {"expr", "computation", "calculation"},
    "scope": {"context", "block", "statement"},
    "evaluate": {"eval", "compute", "execute", "run"},
    "compile": {"compilation", "generate", "codegen"},
    # Memory copy operations
    "copy": {"setmem", "memcpy", "transfer", "bulk", "memorydatacopy", "clone"},
    "setmem": {"copy", "transfer", "memcpy", "bulk", "write"},
    "transfer": {"copy", "setmem", "memcpy", "move", "bulk"},
    "memcpy": {"copy", "setmem", "transfer", "bulk"},
    "bulk": {"copy", "batch", "setmem", "transfer", "memcpy"},
    "memorydatacopy": {"copy", "setmem", "transfer", "bulk", "memcpy"},
    "memorydata": {"memory", "packedcollection", "buffer", "data", "setmem"},
    "into": {"destination", "target", "output", "assignment"},
    # Math/trigonometry - these are in GeometryFeatures
    "sin": {"sine", "trigonometry", "trig", "sinusoidal"},
    "cos": {"cosine", "trigonometry", "trig"},
    "tan": {"tangent", "trigonometry", "trig"},
    "tanh": {"hyperbolic", "trigonometry", "trig"},
    "trigonometry": {"trig", "sin", "cos", "tan", "sine", "cosine", "tangent", "geometry"},
    "math": {"mathematical", "arithmetic", "calculation", "trigonometry"},
    # Audio/library concepts
    "identifier": {"id", "hash", "md5", "content-hash", "getidentifier"},
    "key": {"path", "filepath", "file-path", "location", "getkey"},
    "path": {"filepath", "file-path", "location", "key", "directory"},
    "file": {"filepath", "path", "location", "wav", "audio"},
    "resolve": {"find", "lookup", "locate", "get"},
    "library": {"audiolibrary", "collection", "samples"},
    "persistence": {"save", "load", "protobuf", "serialize"},
    "protobuf": {"persistence", "proto", "serialize", "binary"},
    "similarity": {"similar", "matching", "compare", "distance"},
    "wave": {"audio", "wavedata", "sample", "wav"},
    "sample": {"audio", "wave", "sound", "clip"},
}


def get_word_variants(word: str) -> Set[str]:
    """Get word variants including stems and synonyms."""
    word_lower = word.lower()
    variants = {word_lower}

    # Add common suffixes/prefixes
    base = word_lower
    for suffix in ["ing", "tion", "ation", "ed", "er", "s", "es", "able", "ible"]:
        if base.endswith(suffix) and len(base) > len(suffix) + 2:
            stem = base[:-len(suffix)]
            variants.add(stem)
            # Add back common suffixes
            for new_suffix in ["", "e", "ing", "tion", "ed", "s"]:
                variants.add(stem + new_suffix)

    # Add synonyms
    for key, syns in SYNONYMS.items():
        if word_lower == key or word_lower in syns:
            variants.add(key)
            variants.update(syns)

    return variants


def fuzzy_match(query: str, text: str) -> bool:
    """Check if query fuzzy-matches text using stemming and synonyms."""
    text_lower = text.lower()
    query_lower = query.lower()

    # Direct substring match (fastest)
    if query_lower in text_lower:
        return True

    # Word-by-word matching with variants
    query_words = re.split(r'\s+', query_lower)
    for qword in query_words:
        if len(qword) < 3:
            continue
        variants = get_word_variants(qword)
        # Check if any variant appears in text
        found = False
        for variant in variants:
            if variant in text_lower:
                found = True
                break
        if not found:
            return False

    return len(query_words) > 0

# Initialize server
server = Server("ar-docs")

# Paths relative to this script
SCRIPT_DIR = Path(__file__).parent
COMMON_DIR = SCRIPT_DIR.parent.parent  # /workspace/project/common
DOCS_DIR = COMMON_DIR / "docs"
MODULES_DIR = DOCS_DIR / "modules"
INTERNALS_DIR = DOCS_DIR / "internals"

# Module metadata
MODULES = {
    "uml": {"badge": "Foundation", "desc": "Annotations, lifecycle, metadata"},
    "io": {"badge": "Core", "desc": "Logging, metrics, file I/O"},
    "relation": {"badge": "Core", "desc": "Producer/Evaluable pattern"},
    "code": {"badge": "Data", "desc": "Expression trees, code generation"},
    "collect": {"badge": "Data", "desc": "PackedCollection, multi-dimensional arrays"},
    "hardware": {"badge": "Data", "desc": "Hardware acceleration backends"},
    "algebra": {"badge": "Math", "desc": "Vector, Scalar, linear algebra"},
    "geometry": {"badge": "Math", "desc": "3D geometry, ray tracing primitives"},
    "time": {"badge": "Math", "desc": "Temporal operations, FFT, filtering"},
    "stats": {"badge": "Math", "desc": "Probability distributions"},
    "graph": {"badge": "Domain", "desc": "Neural network layers, Cell pattern"},
    "ml": {"badge": "Domain", "desc": "Transformer models, attention"},
    "audio": {"badge": "Domain", "desc": "Audio synthesis, AudioLibrary, sample management"},
    "compose": {"badge": "Domain", "desc": "Audio persistence, protobuf, PrototypeDiscovery"},
    "color": {"badge": "Domain", "desc": "RGB, lighting, shaders"},
    "space": {"badge": "Domain", "desc": "Scene management, meshes"},
    "physics": {"badge": "Domain", "desc": "Quantum mechanics, atoms"},
    "heredity": {"badge": "Domain", "desc": "Genetic algorithms"},
    "chemistry": {"badge": "Domain", "desc": "Periodic table, elements"},
    "optimize": {"badge": "Application", "desc": "Loss functions, training"},
    "render": {"badge": "Application", "desc": "Ray tracing engine"},
    "utils": {"badge": "Application", "desc": "Testing framework"},
}


def read_file_safely(path: Path, max_chars: int = 50000) -> str:
    """Read a file with size limit."""
    try:
        if not path.exists():
            return f"File not found: {path}"
        content = path.read_text(encoding="utf-8")
        if len(content) > max_chars:
            content = content[:max_chars] + f"\n\n... [truncated, {len(content)} total chars]"
        return content
    except Exception as e:
        return f"Error reading {path}: {e}"


def extract_text_from_html(html: str) -> str:
    """Extract readable text from HTML, preserving code blocks."""
    # Remove script and style tags
    html = re.sub(r'<script[^>]*>.*?</script>', '', html, flags=re.DOTALL | re.IGNORECASE)
    html = re.sub(r'<style[^>]*>.*?</style>', '', html, flags=re.DOTALL | re.IGNORECASE)

    # Preserve code blocks
    html = re.sub(r'<pre[^>]*>(.*?)</pre>', r'\n```\n\1\n```\n', html, flags=re.DOTALL)
    html = re.sub(r'<code[^>]*>(.*?)</code>', r'`\1`', html, flags=re.DOTALL)

    # Convert headers
    html = re.sub(r'<h1[^>]*>(.*?)</h1>', r'\n# \1\n', html, flags=re.DOTALL | re.IGNORECASE)
    html = re.sub(r'<h2[^>]*>(.*?)</h2>', r'\n## \1\n', html, flags=re.DOTALL | re.IGNORECASE)
    html = re.sub(r'<h3[^>]*>(.*?)</h3>', r'\n### \1\n', html, flags=re.DOTALL | re.IGNORECASE)

    # Convert lists
    html = re.sub(r'<li[^>]*>(.*?)</li>', r'- \1\n', html, flags=re.DOTALL | re.IGNORECASE)

    # Convert paragraphs and breaks
    html = re.sub(r'<p[^>]*>(.*?)</p>', r'\1\n\n', html, flags=re.DOTALL | re.IGNORECASE)
    html = re.sub(r'<br\s*/?>', '\n', html, flags=re.IGNORECASE)

    # Remove remaining tags
    html = re.sub(r'<[^>]+>', '', html)

    # Clean up whitespace
    html = re.sub(r'\n{3,}', '\n\n', html)
    html = re.sub(r' +', ' ', html)

    # Decode HTML entities
    html = html.replace('&lt;', '<').replace('&gt;', '>').replace('&amp;', '&')
    html = html.replace('&quot;', '"').replace('&#39;', "'").replace('&nbsp;', ' ')

    return html.strip()


def extract_javadoc_comments(content: str) -> list[tuple[int, str]]:
    """Extract JavaDoc comments with their line numbers."""
    comments = []
    lines = content.split('\n')
    in_javadoc = False
    current_comment = []
    start_line = 0

    for i, line in enumerate(lines):
        stripped = line.strip()
        if stripped.startswith('/**'):
            in_javadoc = True
            start_line = i + 1
            current_comment = [stripped]
        elif in_javadoc:
            current_comment.append(stripped)
            if '*/' in stripped:
                in_javadoc = False
                comments.append((start_line, '\n'.join(current_comment)))
                current_comment = []

    return comments


def search_javadoc(path: Path, query: str, context_lines: int = 2, use_fuzzy: bool = True) -> list[dict]:
    """Search JavaDoc comments in a Java file."""
    results = []
    try:
        content = path.read_text(encoding="utf-8")

        # Search in JavaDoc comments
        for line_num, comment in extract_javadoc_comments(content):
            matches = fuzzy_match(query, comment) if use_fuzzy else query.lower() in comment.lower()
            if matches:
                # Get the class/method name following this comment
                lines = content.split('\n')
                context_end = min(len(lines), line_num + comment.count('\n') + 3)
                context = '\n'.join(lines[line_num - 1:context_end])
                results.append({
                    "file": str(path),
                    "line": line_num,
                    "context": context[:500]  # Limit context size
                })
                if len(results) >= 3:
                    break

    except Exception:
        pass

    return results


def search_in_file(path: Path, query: str, context_lines: int = 2, use_fuzzy: bool = True) -> list[dict]:
    """Search for query in file, return matches with context."""
    results = []
    try:
        content = path.read_text(encoding="utf-8")

        # For HTML files, extract text first
        if path.suffix == ".html":
            content = extract_text_from_html(content)

        lines = content.split('\n')

        for i, line in enumerate(lines):
            matches = fuzzy_match(query, line) if use_fuzzy else query.lower() in line.lower()
            if matches:
                start = max(0, i - context_lines)
                end = min(len(lines), i + context_lines + 1)
                context = '\n'.join(lines[start:end])
                results.append({
                    "file": str(path.relative_to(COMMON_DIR)),
                    "line": i + 1,
                    "context": context
                })

                # Limit matches per file
                if len(results) >= 5:
                    break

    except Exception as e:
        pass

    return results


@server.list_tools()
async def list_tools() -> list[Tool]:
    """List available tools."""
    return [
        Tool(
            name="search_ar_docs",
            description="Search Almost Realism documentation for a keyword or phrase. Uses fuzzy matching with stemming and synonyms by default.",
            inputSchema={
                "type": "object",
                "properties": {
                    "query": {
                        "type": "string",
                        "description": "The search term or phrase"
                    },
                    "module": {
                        "type": "string",
                        "description": "Optional: limit search to specific module (e.g., 'algebra', 'ml')"
                    },
                    "fuzzy": {
                        "type": "boolean",
                        "description": "Use fuzzy matching with stemming and synonyms (default: true)"
                    }
                },
                "required": ["query"]
            }
        ),
        Tool(
            name="read_ar_module",
            description="Read documentation for a specific Almost Realism module. Returns the module's detailed documentation.",
            inputSchema={
                "type": "object",
                "properties": {
                    "module": {
                        "type": "string",
                        "description": "Module name (e.g., 'algebra', 'ml', 'graph', 'hardware')"
                    },
                    "section": {
                        "type": "string",
                        "description": "Optional: specific section to read (e.g., 'overview', 'usage')"
                    }
                },
                "required": ["module"]
            }
        ),
        Tool(
            name="list_ar_modules",
            description="List all available Almost Realism modules with brief descriptions.",
            inputSchema={
                "type": "object",
                "properties": {}
            }
        ),
        Tool(
            name="read_quick_reference",
            description="Get the condensed API quick reference for Almost Realism. Contains essential patterns and syntax.",
            inputSchema={
                "type": "object",
                "properties": {}
            }
        ),
        Tool(
            name="read_ar_guidelines",
            description="Read the CLAUDE.md development guidelines for Almost Realism.",
            inputSchema={
                "type": "object",
                "properties": {}
            }
        ),
        Tool(
            name="search_source_comments",
            description="Search JavaDoc comments in Almost Realism source code. Use this for implementation-level details not in high-level docs.",
            inputSchema={
                "type": "object",
                "properties": {
                    "query": {
                        "type": "string",
                        "description": "The search term or phrase to find in JavaDoc comments"
                    },
                    "module": {
                        "type": "string",
                        "description": "Optional: limit search to specific module (e.g., 'hardware', 'collect')"
                    },
                    "include_tests": {
                        "type": "boolean",
                        "description": "Include test files in search (default: false)"
                    }
                },
                "required": ["query"]
            }
        ),
        Tool(
            name="read_internals",
            description="Read implementation notes from the internals directory. Topics: assignment-optimization, dynamic-indexing, expression-evaluation, kernel-count-propagation.",
            inputSchema={
                "type": "object",
                "properties": {
                    "topic": {
                        "type": "string",
                        "description": "Topic name (e.g., 'assignment-optimization', 'dynamic-indexing', 'expression-evaluation')"
                    }
                },
                "required": ["topic"]
            }
        )
    ]


@server.call_tool()
async def call_tool(name: str, arguments: dict) -> list[TextContent]:
    """Handle tool calls."""

    if name == "search_ar_docs":
        query = arguments.get("query", "")
        module_filter = arguments.get("module")
        use_fuzzy = arguments.get("fuzzy", True)

        if not query:
            return [TextContent(type="text", text="Error: query is required")]

        all_results = []

        # Determine which files to search
        files_to_search = []

        if module_filter:
            # Search specific module
            module_html = MODULES_DIR / f"{module_filter}.html"
            module_readme = COMMON_DIR / module_filter / "README.md"
            if module_html.exists():
                files_to_search.append(module_html)
            if module_readme.exists():
                files_to_search.append(module_readme)
        else:
            # Search all documentation
            # HTML module pages
            for html_file in MODULES_DIR.glob("*.html"):
                files_to_search.append(html_file)

            # README files and module docs directories
            for module in MODULES:
                readme = COMMON_DIR / module / "README.md"
                if readme.exists():
                    files_to_search.append(readme)
                # Also search docs subdirectory for each module (e.g., audio/docs/*.md)
                module_docs_dir = COMMON_DIR / module / "docs"
                if module_docs_dir.exists():
                    for md_file in module_docs_dir.glob("*.md"):
                        files_to_search.append(md_file)

            # Quick reference
            quick_ref = DOCS_DIR / "QUICK_REFERENCE.md"
            if quick_ref.exists():
                files_to_search.append(quick_ref)

            # CLAUDE.md (root and submodules)
            claude_md = COMMON_DIR / "CLAUDE.md"
            if claude_md.exists():
                files_to_search.append(claude_md)

            # Submodule CLAUDE.md files
            for module in MODULES:
                module_claude = COMMON_DIR / module / "CLAUDE.md"
                if module_claude.exists():
                    files_to_search.append(module_claude)

            # Implementation notes (internals)
            if INTERNALS_DIR.exists():
                for md_file in INTERNALS_DIR.glob("*.md"):
                    files_to_search.append(md_file)

        # Search files
        for file_path in files_to_search:
            results = search_in_file(file_path, query, use_fuzzy=use_fuzzy)
            all_results.extend(results)

            # Limit total results
            if len(all_results) >= 20:
                break

        if not all_results:
            return [TextContent(type="text", text=f"No results found for '{query}'")]

        # Format results
        output = f"Found {len(all_results)} matches for '{query}':\n\n"
        for r in all_results:
            output += f"**{r['file']}** (line {r['line']}):\n```\n{r['context']}\n```\n\n"

        return [TextContent(type="text", text=output)]

    elif name == "read_ar_module":
        module = arguments.get("module", "").lower()
        section = arguments.get("section")

        if module not in MODULES:
            available = ", ".join(sorted(MODULES.keys()))
            return [TextContent(type="text", text=f"Unknown module '{module}'. Available: {available}")]

        # Try HTML first, then README, then docs directory
        module_html = MODULES_DIR / f"{module}.html"
        module_readme = COMMON_DIR / module / "README.md"
        module_docs_dir = COMMON_DIR / module / "docs"

        content = ""
        if module_html.exists():
            html_content = read_file_safely(module_html)
            content = extract_text_from_html(html_content)
        elif module_readme.exists():
            content = read_file_safely(module_readme)

        # Also include docs from module's docs/ directory
        if module_docs_dir.exists():
            for md_file in sorted(module_docs_dir.glob("*.md")):
                doc_content = read_file_safely(md_file, max_chars=20000)
                if content:
                    content += f"\n\n---\n\n# {md_file.stem}\n\n{doc_content}"
                else:
                    content = f"# {md_file.stem}\n\n{doc_content}"

        if not content:
            return [TextContent(type="text", text=f"No documentation found for module '{module}'")]

        # If section specified, try to extract it
        if section:
            section_lower = section.lower()
            lines = content.split('\n')
            in_section = False
            section_content = []

            for line in lines:
                if line.startswith('#'):
                    if section_lower in line.lower():
                        in_section = True
                    elif in_section and line.startswith('## '):
                        break
                if in_section:
                    section_content.append(line)

            if section_content:
                content = '\n'.join(section_content)
            else:
                content = f"Section '{section}' not found. Full content:\n\n" + content[:10000]

        return [TextContent(type="text", text=content)]

    elif name == "list_ar_modules":
        output = "# Almost Realism Modules\n\n"

        # Group by badge
        by_badge = {}
        for mod, info in MODULES.items():
            badge = info["badge"]
            if badge not in by_badge:
                by_badge[badge] = []
            by_badge[badge].append((mod, info["desc"]))

        for badge in ["Foundation", "Core", "Data", "Math", "Domain", "Application"]:
            if badge in by_badge:
                output += f"## {badge}\n"
                for mod, desc in sorted(by_badge[badge]):
                    output += f"- **{mod}**: {desc}\n"
                output += "\n"

        output += "\nUse `read_ar_module` to get detailed documentation for any module."

        return [TextContent(type="text", text=output)]

    elif name == "read_quick_reference":
        quick_ref = DOCS_DIR / "QUICK_REFERENCE.md"
        content = read_file_safely(quick_ref)
        return [TextContent(type="text", text=content)]

    elif name == "read_ar_guidelines":
        claude_md = COMMON_DIR / "CLAUDE.md"
        content = read_file_safely(claude_md)
        return [TextContent(type="text", text=content)]

    elif name == "search_source_comments":
        query = arguments.get("query", "")
        module_filter = arguments.get("module")
        include_tests = arguments.get("include_tests", False)

        if not query:
            return [TextContent(type="text", text="Error: query is required")]

        all_results = []

        # Determine which directories to search
        if module_filter:
            search_dirs = [COMMON_DIR / module_filter / "src" / "main" / "java"]
            if include_tests:
                search_dirs.append(COMMON_DIR / module_filter / "src" / "test" / "java")
        else:
            search_dirs = []
            for module in MODULES:
                src_dir = COMMON_DIR / module / "src" / "main" / "java"
                if src_dir.exists():
                    search_dirs.append(src_dir)
                if include_tests:
                    test_dir = COMMON_DIR / module / "src" / "test" / "java"
                    if test_dir.exists():
                        search_dirs.append(test_dir)

        # Search Java files
        for src_dir in search_dirs:
            if not src_dir.exists():
                continue
            for java_file in src_dir.rglob("*.java"):
                results = search_javadoc(java_file, query)
                for r in results:
                    # Make path relative to COMMON_DIR
                    try:
                        r["file"] = str(Path(r["file"]).relative_to(COMMON_DIR))
                    except ValueError:
                        pass
                all_results.extend(results)

                if len(all_results) >= 15:
                    break
            if len(all_results) >= 15:
                break

        if not all_results:
            return [TextContent(type="text", text=f"No JavaDoc comments found matching '{query}'")]

        output = f"Found {len(all_results)} JavaDoc matches for '{query}':\n\n"
        for r in all_results:
            output += f"**{r['file']}** (line {r['line']}):\n```java\n{r['context']}\n```\n\n"

        return [TextContent(type="text", text=output)]

    elif name == "read_internals":
        topic = arguments.get("topic", "").lower().replace(" ", "-")

        if not topic:
            # List available topics
            topics = []
            if INTERNALS_DIR.exists():
                topics = [f.stem for f in INTERNALS_DIR.glob("*.md")]
            return [TextContent(type="text", text=f"Available topics: {', '.join(topics)}")]

        # Try to read the topic file
        topic_file = INTERNALS_DIR / f"{topic}.md"
        if not topic_file.exists():
            topics = [f.stem for f in INTERNALS_DIR.glob("*.md")] if INTERNALS_DIR.exists() else []
            return [TextContent(type="text", text=f"Topic '{topic}' not found. Available: {', '.join(topics)}")]

        content = read_file_safely(topic_file)
        return [TextContent(type="text", text=content)]

    else:
        return [TextContent(type="text", text=f"Unknown tool: {name}")]


async def main():
    """Run the MCP server."""
    async with stdio_server() as (read_stream, write_stream):
        await server.run(read_stream, write_stream, server.create_initialization_options())


if __name__ == "__main__":
    import asyncio
    asyncio.run(main())
