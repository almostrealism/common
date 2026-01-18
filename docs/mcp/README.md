# Almost Realism Documentation MCP Server

An MCP (Model Context Protocol) server that provides AI coding agents with searchable access to Almost Realism framework documentation.

## Overview

This server exposes the following tools to AI agents:

| Tool | Description |
|------|-------------|
| `search_ar_docs` | Search documentation by keyword/phrase |
| `read_ar_module` | Read a specific module's documentation |
| `list_ar_modules` | List all available modules |
| `read_quick_reference` | Get the condensed API reference |
| `read_ar_guidelines` | Read CLAUDE.md development guidelines |

## Installation

### Prerequisites

- Python 3.10+
- pip

### Setup

```bash
# Navigate to MCP server directory
cd /workspace/project/common/docs/mcp

# Create virtual environment (recommended)
python -m venv venv
source venv/bin/activate  # On Windows: venv\Scripts\activate

# Install dependencies
pip install -r requirements.txt
```

## Configuration

### Claude Code

Add to your Claude Code MCP settings (`~/.claude/claude_desktop_config.json` or project `.mcp.json`):

```json
{
  "mcpServers": {
    "ar-docs": {
      "command": "python",
      "args": ["/workspace/project/common/docs/mcp/server.py"],
      "env": {}
    }
  }
}
```

Or create a `.mcp.json` in your project root:

```json
{
  "mcpServers": {
    "ar-docs": {
      "command": "python",
      "args": ["docs/mcp/server.py"]
    }
  }
}
```

### Cursor

Create `.cursor/mcp.json`:

```json
{
  "servers": {
    "ar-docs": {
      "command": "python",
      "args": ["/workspace/project/common/docs/mcp/server.py"]
    }
  }
}
```

## Usage Examples

Once configured, the AI agent can use these tools:

### Search Documentation

```
Search for "PackedCollection" across all docs:
> search_ar_docs(query="PackedCollection")

Search within a specific module:
> search_ar_docs(query="attention", module="ml")
```

### Read Module Documentation

```
Read algebra module docs:
> read_ar_module(module="algebra")

Read specific section:
> read_ar_module(module="ml", section="attention")
```

### List Modules

```
> list_ar_modules()

Returns:
# Almost Realism Modules

## Foundation
- **uml**: Annotations, lifecycle, metadata

## Core
- **io**: Logging, metrics, file I/O
- **relation**: Producer/Evaluable pattern
...
```

### Quick Reference

```
> read_quick_reference()

Returns the condensed API cheatsheet with all essential patterns.
```

## Tool Details

### search_ar_docs

**Parameters:**
- `query` (required): Search term or phrase
- `module` (optional): Limit search to specific module

**Returns:** Matching excerpts with file locations and context

**Searches:**
- Module HTML documentation (docs/modules/*.html)
- Module README files (*/README.md)
- Quick Reference (docs/QUICK_REFERENCE.md)
- CLAUDE.md guidelines

### read_ar_module

**Parameters:**
- `module` (required): Module name (e.g., "algebra", "ml")
- `section` (optional): Specific section header to extract

**Returns:** Full module documentation (extracted from HTML) or specific section

**Available modules:**
- Foundation: uml
- Core: io, relation
- Data: code, collect, hardware
- Math: algebra, geometry, time, stats
- Domain: graph, ml, color, space, physics, heredity, chemistry
- Application: optimize, render, utils

### list_ar_modules

**Parameters:** None

**Returns:** Categorized list of all modules with descriptions

### read_quick_reference

**Parameters:** None

**Returns:** Contents of QUICK_REFERENCE.md (~8KB condensed API guide)

### read_ar_guidelines

**Parameters:** None

**Returns:** Contents of CLAUDE.md (development guidelines)

## Development

### Testing the Server

```bash
# Test server starts correctly
python server.py

# The server uses stdio, so it will wait for MCP protocol messages
# Press Ctrl+C to exit
```

### Adding New Tools

Edit `server.py` and:

1. Add to `@server.list_tools()` function
2. Add handler in `@server.call_tool()` function

### Updating Module List

Edit the `MODULES` dictionary in `server.py` to add/remove modules.

## Troubleshooting

### Server Won't Start

```bash
# Check Python version
python --version  # Should be 3.10+

# Check MCP installed
pip show mcp

# Try reinstalling
pip install --upgrade mcp
```

### No Results from Search

- Check file paths are correct (COMMON_DIR should point to /workspace/project/common)
- Verify documentation files exist
- Try broader search terms

### Module Not Found

- Run `list_ar_modules()` to see available modules
- Check spelling (case-insensitive)

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     AI Coding Agent                          │
│                   (Claude Code, Cursor)                      │
└─────────────────────────┬───────────────────────────────────┘
                          │ MCP Protocol (stdio)
                          ▼
┌─────────────────────────────────────────────────────────────┐
│                    ar-docs MCP Server                        │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────────────────┐│
│  │search_ar_   │ │read_ar_     │ │list_ar_modules          ││
│  │docs         │ │module       │ │read_quick_reference     ││
│  └──────┬──────┘ └──────┬──────┘ └───────────┬─────────────┘│
└─────────┼───────────────┼────────────────────┼──────────────┘
          │               │                    │
          ▼               ▼                    ▼
┌─────────────────────────────────────────────────────────────┐
│                    Documentation Files                       │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────────────────┐│
│  │docs/modules/│ │*/README.md  │ │CLAUDE.md                ││
│  │*.html       │ │             │ │QUICK_REFERENCE.md       ││
│  └─────────────┘ └─────────────┘ └─────────────────────────┘│
└─────────────────────────────────────────────────────────────┘
```

## License

Part of the Almost Realism framework. See main repository for license.
