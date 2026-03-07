# AR Memory Server

A centralized HTTP service providing persistent semantic memory for AI agents
working on the Almost Realism codebase. Entries are stored in SQLite with FAISS
vector indices for embedding-based similarity search.

ar-memory runs as a standalone HTTP server (typically via Docker) and is
accessed by ar-consultant and ar-manager through the shared
`MemoryHTTPClient` in `tools/mcp/common/`.

## Architecture

```
┌─────────────────────────────────────────────────┐
│              ar-memory (HTTP)                    │
│                                                 │
│  REST API:                                      │
│    POST /api/memory/store     Store an entry    │
│    POST /api/memory/search    Semantic search   │
│    POST /api/memory/branch    Branch context    │
│    DELETE /api/memory/{id}    Delete by ID      │
│    GET  /api/memory/list      List entries      │
│    POST /api/memory/import    Bulk import       │
│    GET  /api/health           Health check      │
│                                                 │
│  SQLite + FAISS (single authoritative store)    │
└────────────┬───────────────────┬────────────────┘
             │ HTTP              │ HTTP
             │                  │
     ┌───────┴───────┐  ┌──────┴────────┐
     │ ar-consultant │  │  ar-manager   │
     │   (agents)    │  │  (external)   │
     └───────────────┘  └───────────────┘
```

## Running

### Docker (recommended)

ar-memory is defined as a service in `tools/docker-compose.yml`:

```bash
docker compose -f tools/docker-compose.yml up -d ar-memory
```

Data persists at `/Users/Shared/flowtree/memory-data/` on the host.

### Standalone

```bash
pip install -r requirements.txt
python server.py --http-only
```

## REST API

### POST /api/memory/store

Store a new memory entry with semantic embedding.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `content` | string | Yes | The text content to store |
| `repo_url` | string | Yes | Repository URL (normalized to SSH form) |
| `branch` | string | Yes | Branch name |
| `namespace` | string | No | Logical grouping (default: `"default"`) |
| `tags` | string[] | No | Tags for categorical filtering |
| `source` | string | No | Origin identifier |

### POST /api/memory/search

Semantic search by natural language query.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `query` | string | Yes | Natural language search query |
| `namespace` | string | No | Namespace to search (default: `"default"`) |
| `limit` | int | No | Max results (default: 5) |
| `tag` | string | No | Filter to entries with this tag |
| `repo_url` | string | No | Filter by repository URL |
| `branch` | string | No | Filter by branch name |

### POST /api/memory/branch

Non-semantic lookup of all memories for a specific repo and branch.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `repo_url` | string | Yes | Repository URL |
| `branch` | string | Yes | Branch name |
| `namespace` | string | No | Namespace (default: `"default"`) |
| `limit` | int | No | Max results (default: 20) |

### DELETE /api/memory/{entry_id}

Delete an entry by ID. Query param: `namespace` (default: `"default"`).

### GET /api/memory/list

List entries newest-first. Query params: `namespace`, `tag`, `limit`, `offset`.

### POST /api/memory/import

Bulk import entries. Body: `{"entries": [...], "dedup_strategy": "skip"}`.

### GET /api/health

Returns server status, namespace list, and entry counts.

## Repo URL Normalization

All repository URLs are normalized to canonical SSH form on storage and query:

- `https://github.com/org/repo` → `git@github.com:org/repo.git`
- `git@github.com:org/repo` → `git@github.com:org/repo.git`

This ensures consistent matching regardless of which URL format callers use.
Existing entries are migrated automatically on server startup.

## Data Model

SQLite table `entries`:

| Column | Type | Description |
|--------|------|-------------|
| `id` | TEXT PRIMARY KEY | UUID |
| `namespace` | TEXT NOT NULL | Scope identifier |
| `content` | TEXT NOT NULL | The stored text |
| `tags` | TEXT | JSON array of tags |
| `source` | TEXT | Origin label |
| `created_at` | TEXT NOT NULL | ISO-8601 timestamp |
| `repo_url` | TEXT | Repository URL (SSH form) |
| `branch` | TEXT | Branch name |

FAISS: One flat L2 index per namespace. Dimension is 384 for both default backends.

## File Structure

```
tools/mcp/memory/
  server.py          # Startup and mode selection (--http-only, --mcp-only)
  store.py           # SQLite metadata + FAISS vector indices + URL normalization
  http_api.py        # Starlette REST endpoints
  embedder.py        # Embedder interface + backend implementations
  migrate.py         # Database migration utilities
  Dockerfile         # Container image for docker-compose
  requirements.txt   # Python dependencies
  data/              # Created at runtime (gitignored)
    memory.db        # SQLite database
    *.index          # FAISS index files (one per namespace)
    *.ids.json       # ID mappings (FAISS position -> SQLite rowid)
```

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `AR_MEMORY_DATA_DIR` | `tools/mcp/memory/data` | Directory for SQLite DB and index files |
| `AR_MEMORY_HTTP_PORT` | `8020` | HTTP server port |
| `AR_MEMORY_AUTH_TOKEN` | (none) | Optional bearer token for authentication |
| `AR_MEMORY_BACKEND` | `fastembed` | Embedding backend: `fastembed` or `sentence-transformers` |
| `AR_MEMORY_MODEL` | *(backend default)* | Override model name |

## Embedding Backends

| Backend | Library | Install Size | Default Model | Dimensions |
|---------|---------|-------------|---------------|------------|
| `fastembed` | fastembed (ONNX Runtime) | ~200 MB | `BAAI/bge-small-en-v1.5` | 384 |
| `sentence-transformers` | sentence-transformers (PyTorch) | ~1-2 GB | `all-MiniLM-L6-v2` | 384 |

## Recommended Namespaces

| Namespace | Purpose |
|-----------|---------|
| `decisions` | Design choices and their rationale |
| `bugs` | Issues encountered and their root causes/fixes |
| `context` | Codebase knowledge not captured in ar-docs |
| `progress` | Multi-session task tracking and next steps |
| `default` | General-purpose entries |
