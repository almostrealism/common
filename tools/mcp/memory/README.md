# MCP Memory Server

An MCP server providing persistent semantic memory for AI agents working on the Almost Realism codebase. Entries are stored in SQLite with FAISS vector indices for embedding-based similarity search.

## Features

- **Semantic Search**: Find entries by meaning, not just keywords
- **Namespace Isolation**: Separate memory spaces per workstream or topic
- **Tag Filtering**: Categorical filtering on search and list operations
- **Persistent Storage**: SQLite + FAISS indices survive across sessions
- **Pluggable Embeddings**: FastEmbed (default, lightweight) or SentenceTransformers (optional)

## Installation

```bash
pip install -r requirements.txt
```

The default FastEmbed backend uses ONNX Runtime (~200 MB install). The embedding model (~50 MB) downloads automatically on first use and is cached locally.

## MCP Configuration

Already configured in `.mcp.json`:

```json
{
  "mcpServers": {
    "ar-memory": {
      "command": "python3",
      "args": ["tools/mcp/memory/server.py"],
      "description": "Semantic memory storage and retrieval server"
    }
  }
}
```

## Available Tools

### memory_store

Store a new memory entry with semantic embedding.

**Parameters:**
| Name | Type | Required | Description |
|------|------|----------|-------------|
| `content` | string | Yes | The text content to store |
| `namespace` | string | No | Logical grouping (default: `"default"`) |
| `tags` | string[] | No | Tags for categorical filtering |
| `source` | string | No | Origin identifier (e.g., file path, PR number) |

**Returns:** The created entry with `id`, `namespace`, `content`, `tags`, `source`, and `created_at`.

### memory_search

Search entries by semantic similarity to a natural language query.

**Parameters:**
| Name | Type | Required | Description |
|------|------|----------|-------------|
| `query` | string | Yes | Natural language search query |
| `namespace` | string | No | Namespace to search (default: `"default"`) |
| `limit` | int | No | Max results (default: 5) |
| `tag` | string | No | Filter to entries with this tag |

**Returns:** Ranked list of entries with an added `score` field (L2 distance; lower is more similar).

### memory_delete

Delete an entry by ID. Rebuilds the FAISS index for the namespace after deletion.

**Parameters:**
| Name | Type | Required | Description |
|------|------|----------|-------------|
| `entry_id` | string | Yes | UUID of the entry to delete |
| `namespace` | string | No | Namespace the entry belongs to (default: `"default"`) |

### memory_list

List entries ordered by creation time (newest first), with optional tag filtering and pagination.

**Parameters:**
| Name | Type | Required | Description |
|------|------|----------|-------------|
| `namespace` | string | No | Namespace to list (default: `"default"`) |
| `tag` | string | No | Filter by tag |
| `limit` | int | No | Max entries (default: 20) |
| `offset` | int | No | Pagination offset (default: 0) |

## Architecture

```
tools/mcp/memory/
  server.py          # FastMCP server, tool registration
  store.py           # SQLite metadata + FAISS vector indices
  embedder.py        # Embedder interface + backend implementations
  requirements.txt   # Python dependencies
  data/              # Created at runtime (gitignored)
    memory.db        # SQLite database
    *.index          # FAISS index files (one per namespace)
    *.ids.json       # ID mappings (FAISS position -> SQLite rowid)
```

```
server.py --> store.py --> Embedder (abstract)
                                |
                    +-----------+-----------+
                    |                       |
            FastEmbedEmbedder    SentenceTransformerEmbedder
            (ONNX, default)      (PyTorch, optional)
```

### Data Model

SQLite table `entries`:

| Column | Type | Description |
|--------|------|-------------|
| `id` | TEXT PRIMARY KEY | UUID |
| `namespace` | TEXT NOT NULL | Scope identifier |
| `content` | TEXT NOT NULL | The stored text |
| `tags` | TEXT | JSON array of tags |
| `source` | TEXT | Origin label |
| `created_at` | TEXT NOT NULL | ISO-8601 timestamp |

FAISS: One flat L2 index per namespace. Dimension is 384 for both default backends.

## Environment Variables

All optional:

| Variable | Default | Description |
|----------|---------|-------------|
| `AR_MEMORY_DATA_DIR` | `tools/mcp/memory/data` | Directory for SQLite DB and index files |
| `AR_MEMORY_BACKEND` | `fastembed` | Embedding backend: `fastembed` or `sentence-transformers` |
| `AR_MEMORY_MODEL` | *(backend default)* | Override model name |
| `AR_MEMORY_CACHE_DIR` | *(backend default)* | Model download cache directory |

## Embedding Backends

| Backend | Library | Install Size | Default Model | Dimensions |
|---------|---------|-------------|---------------|------------|
| `fastembed` | fastembed (ONNX Runtime) | ~200 MB | `BAAI/bge-small-en-v1.5` | 384 |
| `sentence-transformers` | sentence-transformers (PyTorch) | ~1-2 GB | `all-MiniLM-L6-v2` | 384 |

To use the SentenceTransformers backend:
```bash
pip install sentence-transformers
export AR_MEMORY_BACKEND=sentence-transformers
```

## Recommended Namespaces

| Namespace | Purpose |
|-----------|---------|
| `decisions` | Design choices and their rationale |
| `bugs` | Issues encountered and their root causes/fixes |
| `context` | Codebase knowledge not captured in ar-docs |
| `progress` | Multi-session task tracking and next steps |
| `default` | General-purpose entries |

## Troubleshooting

**Model download fails:**
- Check network connectivity; the model downloads from Hugging Face on first use
- Set `AR_MEMORY_CACHE_DIR` to a writable directory if the default cache location is not writable

**Import errors on startup:**
- Run `pip install -r tools/mcp/memory/requirements.txt`
- For SentenceTransformers backend, also `pip install sentence-transformers`

**Search returns empty results:**
- Verify the namespace matches between store and search calls
- Check that entries exist with `memory_list`
