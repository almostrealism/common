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

import os
import re
import json
from pathlib import Path
from typing import Optional
from mcp.server import Server
from mcp.server.stdio import stdio_server
from mcp.types import Tool, TextContent

# Initialize server
server = Server("ar-docs")

# Paths relative to this script
SCRIPT_DIR = Path(__file__).parent
COMMON_DIR = SCRIPT_DIR.parent.parent  # /workspace/project/common
DOCS_DIR = COMMON_DIR / "docs"
MODULES_DIR = DOCS_DIR / "modules"

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


def search_in_file(path: Path, query: str, context_lines: int = 2) -> list[dict]:
    """Search for query in file, return matches with context."""
    results = []
    try:
        content = path.read_text(encoding="utf-8")

        # For HTML files, extract text first
        if path.suffix == ".html":
            content = extract_text_from_html(content)

        lines = content.split('\n')
        query_lower = query.lower()

        for i, line in enumerate(lines):
            if query_lower in line.lower():
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
            description="Search Almost Realism documentation for a keyword or phrase. Returns matching excerpts with context.",
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
        )
    ]


@server.call_tool()
async def call_tool(name: str, arguments: dict) -> list[TextContent]:
    """Handle tool calls."""

    if name == "search_ar_docs":
        query = arguments.get("query", "")
        module_filter = arguments.get("module")

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

            # README files
            for module in MODULES:
                readme = COMMON_DIR / module / "README.md"
                if readme.exists():
                    files_to_search.append(readme)

            # Quick reference
            quick_ref = DOCS_DIR / "QUICK_REFERENCE.md"
            if quick_ref.exists():
                files_to_search.append(quick_ref)

            # CLAUDE.md
            claude_md = COMMON_DIR / "CLAUDE.md"
            if claude_md.exists():
                files_to_search.append(claude_md)

        # Search files
        for file_path in files_to_search:
            results = search_in_file(file_path, query)
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

        # Try HTML first, then README
        module_html = MODULES_DIR / f"{module}.html"
        module_readme = COMMON_DIR / module / "README.md"

        content = ""
        if module_html.exists():
            html_content = read_file_safely(module_html)
            content = extract_text_from_html(html_content)
        elif module_readme.exists():
            content = read_file_safely(module_readme)
        else:
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

    else:
        return [TextContent(type="text", text=f"Unknown tool: {name}")]


async def main():
    """Run the MCP server."""
    async with stdio_server() as (read_stream, write_stream):
        await server.run(read_stream, write_stream, server.create_initialization_options())


if __name__ == "__main__":
    import asyncio
    asyncio.run(main())
