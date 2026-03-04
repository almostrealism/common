# Memory Architecture Refactoring Plan

**Status**: Planning
**Created**: 2026-03-03
**Branch**: feature/memory-refactor (to be created)

---

## Executive Summary

Refactor the memory subsystem to centralize ar-memory as a standalone HTTP server, eliminate redundancy between ar-consultant and ar-manager, and enable external clients (Claude mobile, other AI agents) to access agent-stored memories through ar-manager.

---

## Current Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                     Coding Agent Context                         │
│  ┌─────────────────┐    ┌─────────────────┐                     │
│  │  ar-consultant  │    │    ar-memory    │                     │
│  │     (MCP)       │    │     (MCP)       │                     │
│  │                 │    │                 │                     │
│  │  MemoryClient ──┼────┼→ MemoryStore    │                     │
│  │  (direct import)│    │  SQLite+FAISS   │                     │
│  └─────────────────┘    └─────────────────┘                     │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                    External Context                              │
│  ┌─────────────────┐                                            │
│  │   ar-manager    │  ← No memory access                        │
│  │     (MCP)       │                                            │
│  │                 │                                            │
│  └─────────────────┘                                            │
└─────────────────────────────────────────────────────────────────┘
```

### Problems with Current Architecture

1. **Redundancy**: ar-memory and ar-consultant both run as MCP servers in the coding agent context, with ar-consultant importing ar-memory's store directly.

2. **No external access**: ar-manager cannot access memories, so Claude mobile and other external clients cannot inspect agent findings.

3. **Local-only storage**: Memories are tied to the machine running the MCP servers.

4. **Branch annotation optional**: Agents often forget to include repo_url/branch, making cross-session correlation unreliable.

5. **Duplicate FAISS indices**: ar-consultant's direct import creates separate in-memory indices from ar-memory's MCP server.

---

## Target Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                 External Memory Service                          │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │                    ar-memory (HTTP)                         ││
│  │                                                             ││
│  │  REST API:                                                  ││
│  │    POST /api/memory/store                                   ││
│  │    POST /api/memory/search                                  ││
│  │    GET  /api/memory/branch/{repo}/{branch}                  ││
│  │    DELETE /api/memory/{id}                                  ││
│  │    GET  /api/memory/list                                    ││
│  │    GET  /api/health                                         ││
│  │                                                             ││
│  │  SQLite + FAISS (single authoritative store)                ││
│  └─────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────┘
         ▲                              ▲
         │ HTTP                         │ HTTP
         │                              │
┌────────┴────────┐            ┌────────┴────────┐
│                 │            │                 │
│  ar-consultant  │            │   ar-manager    │
│     (MCP)       │            │   (MCP/HTTP)    │
│                 │            │                 │
│  ┌───────────┐  │            │  ┌───────────┐  │
│  │MemoryHTTP │  │            │  │MemoryHTTP │  │
│  │  Client   │  │            │  │  Client   │  │
│  └───────────┘  │            │  └───────────┘  │
│                 │            │                 │
│  + DocsRetriever│            │  + Controller   │
│  + LLM Inference│            │    REST calls   │
│  + History DB   │            │  + GitHub API   │
│  + Git Context  │            │  + LLM Inference│
│                 │            │                 │
└─────────────────┘            └─────────────────┘
        ▲                              ▲
        │ MCP (stdio)                  │ MCP (HTTP)
        │                              │
┌───────┴───────┐              ┌───────┴───────┐
│ Coding Agent  │              │ Claude Mobile │
│ (Claude Code) │              │ External AI   │
└───────────────┘              └───────────────┘
```

### Key Changes

1. **ar-memory becomes HTTP-only**: No longer an MCP server. Runs as a standalone HTTP service.

2. **Shared client library**: `memory_http_client.py` used by both ar-consultant and ar-manager.

3. **ar-manager gains recall**: External clients can search memories, retrieve branch context.

4. **Branch annotation mandatory**: `store()` requires `repo_url` and `branch` parameters.

5. **Service discovery**: Clients try FlowTree controller host → localhost → mac-studio.

6. **Git context auto-detection**: ar-consultant detects repo_url/branch from working directory when not provided.

7. **LLM synthesis in ar-manager**: ar-manager connects to mac-studio llama.cpp for recall synthesis (same as ar-consultant).

---

## Git Context Auto-Detection

When `repo_url` or `branch` are not explicitly provided to ar-consultant tools, the system will auto-detect them from the current working directory:

```python
def _detect_git_context() -> tuple[str, str]:
    """
    Detect repo_url and branch from git in the current working directory.

    Returns:
        Tuple of (repo_url, branch) or raises if not in a git repo.
    """
    import subprocess

    # Get remote URL (prefer origin)
    result = subprocess.run(
        ["git", "remote", "get-url", "origin"],
        capture_output=True, text=True, timeout=5,
    )
    if result.returncode != 0:
        raise ValueError("Not in a git repository or no 'origin' remote")
    repo_url = result.stdout.strip()

    # Get current branch
    result = subprocess.run(
        ["git", "rev-parse", "--abbrev-ref", "HEAD"],
        capture_output=True, text=True, timeout=5,
    )
    if result.returncode != 0:
        raise ValueError("Cannot determine current branch")
    branch = result.stdout.strip()

    return (repo_url, branch)
```

**Usage in ar-consultant**:
- `remember`: If repo_url/branch not provided, auto-detect from git
- `recall`: If filtering by branch, can auto-detect current branch
- `branch_catchup`: Can auto-detect both if not provided

**Note**: ar-manager cannot use git auto-detection (no local repo), so it must either:
- Receive explicit repo_url/branch parameters, OR
- Resolve them from workstream_id via the controller

---

## Phases

### Phase 1: Extract Shared Client Library

**Goal**: Create shared libraries for memory HTTP client, LLM inference, and git context detection that both ar-consultant and ar-manager will use.

**Location**: `tools/mcp/common/`

**Tasks**:

1.1. Create `tools/mcp/common/` directory structure with `__init__.py`

1.2. Implement `MemoryHTTPClient` class (`memory_http_client.py`):
```python
class MemoryHTTPClient:
    def __init__(self, base_url: str = None):
        """Auto-discover ar-memory server if base_url not provided."""

    def store(self, content: str, repo_url: str, branch: str,
              namespace: str = "default", tags: list[str] = None,
              source: str = None) -> dict:
        """Store memory. repo_url and branch are REQUIRED."""

    def search(self, query: str, namespace: str = "default",
               limit: int = 5, tag: str = None,
               repo_url: str = None, branch: str = None) -> list[dict]:
        """Semantic search. Filtering is optional."""

    def search_by_branch(self, repo_url: str, branch: str,
                         namespace: str = "default",
                         limit: int = 20) -> list[dict]:
        """Non-semantic lookup by branch."""

    def delete(self, entry_id: str, namespace: str = "default") -> dict:
        """Delete by ID."""

    def list_entries(self, namespace: str = "default",
                     tag: str = None, limit: int = 20,
                     offset: int = 0) -> list[dict]:
        """List entries, newest first."""

    def health(self) -> dict:
        """Health check."""
```

1.3. Implement service discovery logic:
```python
def _discover_memory_server() -> str:
    """
    Try in order:
    1. AR_MEMORY_URL environment variable
    2. FlowTree controller host (AR_CONTROLLER_URL) + port 8020
    3. localhost:8020
    4. mac-studio:8020

    Returns the first responding URL.
    """
```

1.4. Add connection pooling and retry logic

1.5. Add timeout handling and error normalization

1.6. Extract inference module (`inference.py`):
```python
# Move from tools/mcp/consultant/inference.py to tools/mcp/common/inference.py
# Both ar-consultant and ar-manager will use this

SYSTEM_PROMPT = "..."

class InferenceBackend:
    @property
    def name(self) -> str: ...
    @property
    def available(self) -> bool: ...
    def generate(self, prompt: str, ...) -> str: ...

def create_backend() -> InferenceBackend:
    """
    Auto-discover LLM backend (same logic as ar-consultant):
    1. llama.cpp at localhost:8083
    2. llama.cpp at mac-studio:8083
    3. Ollama at localhost:11434
    4. Passthrough (no synthesis)
    """
```

1.7. Create git context module (`git_context.py`):
```python
def detect_git_context(working_dir: str = None) -> tuple[str, str]:
    """
    Detect (repo_url, branch) from git in the specified directory.
    Raises ValueError if not in a git repo.
    """

def normalize_repo_url(url: str) -> str:
    """
    Normalize git URLs for consistent matching:
    - git@github.com:owner/repo.git -> https://github.com/owner/repo
    - https://github.com/owner/repo.git -> https://github.com/owner/repo
    """
```

**Deliverables**:
- `tools/mcp/common/__init__.py`
- `tools/mcp/common/memory_http_client.py`
- `tools/mcp/common/inference.py`
- `tools/mcp/common/git_context.py`
- `tools/mcp/common/requirements.txt`

---

### Phase 2: Add HTTP API to ar-memory

**Goal**: Convert ar-memory from MCP-only to HTTP+MCP hybrid (MCP deprecated but retained for migration).

**Tasks**:

2.1. Add FastAPI/Starlette HTTP routes to `tools/mcp/memory/server.py`:
```python
# REST endpoints alongside existing MCP
@app.post("/api/memory/store")
@app.post("/api/memory/search")
@app.get("/api/memory/branch/{repo_url}/{branch}")
@app.delete("/api/memory/{entry_id}")
@app.get("/api/memory/list")
@app.get("/api/health")
```

2.2. Enforce mandatory branch annotation in HTTP API:
```python
class StoreRequest(BaseModel):
    content: str
    repo_url: str  # Required
    branch: str    # Required
    namespace: str = "default"
    tags: list[str] | None = None
    source: str | None = None
```

2.3. Add authentication support (optional bearer token)

2.4. Add CORS headers for browser-based clients

2.5. Update environment variables:
```
AR_MEMORY_HTTP_PORT=8020
AR_MEMORY_AUTH_TOKEN=<optional>
```

2.6. Add startup mode selection:
```bash
# HTTP-only mode (target)
python server.py --http-only

# MCP-only mode (legacy)
python server.py --mcp-only

# Hybrid mode (migration)
python server.py  # default during migration
```

**Deliverables**:
- Updated `tools/mcp/memory/server.py` with HTTP endpoints
- Updated `tools/mcp/memory/README.md`
- New `tools/mcp/memory/http_api.py` (FastAPI routes)

---

### Phase 3: Update ar-consultant to Use HTTP Client

**Goal**: Replace direct MemoryStore import with MemoryHTTPClient.

**Tasks**:

3.1. Replace `memory_client.py` internals:
```python
# Before (direct import)
from store import MemoryStore
self._store = MemoryStore(...)

# After (HTTP client)
from common.memory_http_client import MemoryHTTPClient
self._client = MemoryHTTPClient()
```

3.2. Update `remember` tool with git auto-detection:
```python
from common.git_context import detect_git_context

@mcp.tool()
def remember(
    content: str,
    repo_url: str = None,   # Auto-detected if not provided
    branch: str = None,     # Auto-detected if not provided
    namespace: str = "default",
    tags: list[str] = None,
    source: str = None,
) -> dict:
    """Store a memory with mandatory branch context.

    If repo_url/branch not provided, auto-detects from current git directory.
    Fails if not in a git repo and parameters not provided.
    """
    if not repo_url or not branch:
        detected_url, detected_branch = detect_git_context()
        repo_url = repo_url or detected_url
        branch = branch or detected_branch

    # Proceed with storage...
```

3.3. Update `branch_catchup` to use HTTP client with optional git auto-detection

3.4. Update `recall` to use HTTP client (filtering remains optional)

3.5. Add graceful degradation if ar-memory unavailable:
```python
def _check_memory_available(self) -> bool:
    """Return False if ar-memory server is unreachable."""
```

3.6. Update error messages to guide users toward fixing ar-memory connectivity

3.7. Remove ar-memory from direct dependencies (no more importing `store.py`)

**Deliverables**:
- Updated `tools/mcp/consultant/memory_client.py`
- Updated `tools/mcp/consultant/server.py`
- Updated `tools/mcp/consultant/requirements.txt`

---

### Phase 4: Add Memory Tools to ar-manager

**Goal**: Enable external clients to access memories through ar-manager with LLM synthesis.

**Tasks**:

4.1. Add shared client and LLM imports:
```python
from common.memory_http_client import MemoryHTTPClient
from common.inference import create_backend, SYSTEM_PROMPT

memory = MemoryHTTPClient()
llm = create_backend()  # Connects to mac-studio llama.cpp
```

4.2. Extract inference module to shared library:
- Move `tools/mcp/consultant/inference.py` to `tools/mcp/common/inference.py`
- Update ar-consultant to import from common
- ar-manager uses same LLM discovery (mac-studio:8083 llama.cpp with qwen)

4.3. Implement `memory_recall` tool (mirrors ar-consultant's recall with synthesis):
```python
@mcp.tool()
def memory_recall(
    query: str,
    namespace: str = "default",
    limit: int = 5,
    repo_url: str = None,
    branch: str = None,
    workstream_id: str = None,
) -> dict:
    """Search memories with LLM-synthesized summary.

    Can resolve repo_url/branch from workstream_id if provided.
    Uses llama.cpp backend on mac-studio for synthesis.
    """
```

4.3. Implement `memory_branch_context` tool:
```python
@mcp.tool()
def memory_branch_context(
    workstream_id: str = "",
    repo_url: str = "",
    branch: str = "",
    limit: int = 20,
) -> dict:
    """Get all memories for a branch.

    Can resolve repo_url/branch from workstream_id if provided.
    """
```

4.4. Implement `memory_store` tool for external note-taking:
```python
@mcp.tool()
def memory_store(
    content: str,
    workstream_id: str = "",  # Resolves to repo_url/branch
    repo_url: str = "",
    branch: str = "",
    namespace: str = "default",
    tags: list[str] = None,
) -> dict:
    """Store a memory from external client.

    Either workstream_id or (repo_url + branch) required.
    """
```

4.5. Add "memory" scope to authentication:
```json
{
  "tokens": [
    {
      "value": "tok_abc123",
      "scopes": ["read", "write", "pipeline", "memory"]
    }
  ]
}
```

4.6. Update tool documentation and next_steps guidance

**Deliverables**:
- Updated `tools/mcp/manager/server.py` with memory tools
- Updated `tools/mcp/manager/README.md`

---

### Phase 5: Remove ar-memory from MCP Configuration

**Goal**: ar-memory no longer runs as an MCP server in the coding agent context.

**Tasks**:

5.1. Update `.mcp.json` to remove ar-memory:
```json
{
  "mcpServers": {
    "ar-consultant": { ... },
    "ar-manager": { ... },
    // ar-memory REMOVED
  }
}
```

5.2. Update CLAUDE.md guidelines:
- Remove references to `mcp__ar-memory__*` tools
- Update memory usage instructions to go through ar-consultant
- Document that ar-memory is now an external service

5.3. Add ar-memory to FlowTree controller startup:
```java
// FlowTreeController.java
private void startExternalServices() {
    // Start ar-memory HTTP server
    ProcessBuilder pb = new ProcessBuilder(
        "python3", "tools/mcp/memory/server.py", "--http-only"
    );
    pb.environment().put("AR_MEMORY_HTTP_PORT", "8020");
    ...
}
```

5.4. Create systemd/launchd service definitions for standalone deployment

5.5. Update docker-compose.yml if applicable

**Deliverables**:
- Updated `.mcp.json`
- Updated `CLAUDE.md`
- Service definitions in `tools/mcp/memory/deploy/`
- Controller integration (Java side)

---

### Phase 6: Migration and Cleanup

**Goal**: Remove deprecated code paths, consolidate scattered databases, and finalize documentation.

**Tasks**:

6.1. Remove MCP code from ar-memory server.py (keep HTTP only)

6.2. Remove `MemoryClient` class that directly imported MemoryStore

6.3. Update all documentation:
- `tools/mcp/README.md`
- `tools/mcp/memory/README.md`
- `tools/mcp/consultant/README.md`
- `tools/mcp/manager/README.md`

6.4. Add migration notes for existing deployments

6.5. Update test infrastructure to start ar-memory HTTP server

6.6. Archive old MCP-based memory integration code

**Deliverables**:
- Cleaned up codebase
- Complete documentation
- Migration guide

---

### Phase 7: Database Consolidation

**Goal**: Migrate all scattered local memory databases into the centralized ar-memory server.

**Background**: Multiple local memory databases exist across different machines and working directories:
- Developer laptops with local `tools/mcp/memory/data/` directories
- CI/CD environments with ephemeral databases
- Historical databases from prior agent sessions

**Tasks**:

7.1. Create database discovery tool:
```python
# tools/mcp/memory/migrate/discover.py
def find_memory_databases(search_paths: list[str]) -> list[str]:
    """
    Search for memory.db files in common locations:
    - tools/mcp/memory/data/
    - tools/mcp/consultant/data/
    - ~/.config/ar/memory/
    - /tmp/ar-memory/
    """
```

7.2. Create database export tool:
```python
# tools/mcp/memory/migrate/export.py
def export_database(db_path: str, output_path: str) -> dict:
    """
    Export a local memory database to portable JSON format.
    Includes all entries with embeddings, namespaces, and metadata.

    Returns:
        {"entries": [...], "exported_at": "...", "source": db_path}
    """
```

7.3. Create database import tool:
```python
# tools/mcp/memory/migrate/import.py
def import_database(
    export_path: str,
    target_url: str,
    default_repo_url: str = None,
    default_branch: str = None,
    dedup_strategy: str = "skip",  # "skip", "overwrite", "rename"
) -> dict:
    """
    Import exported entries into centralized ar-memory server.

    For entries without repo_url/branch:
    - Use provided defaults, OR
    - Infer from source path if possible, OR
    - Place in a "legacy" namespace

    Deduplication:
    - "skip": Skip entries with matching content hash
    - "overwrite": Replace existing entries
    - "rename": Add suffix to avoid collision
    """
```

7.4. Create migration CLI:
```bash
# Discover all databases
python -m tools.mcp.memory.migrate discover --search-path /Users --search-path /home

# Export a specific database
python -m tools.mcp.memory.migrate export /path/to/memory.db --output export.json

# Import into centralized server
python -m tools.mcp.memory.migrate import export.json \
    --target http://mac-studio:8020 \
    --default-repo https://github.com/almostrealism/common \
    --default-branch master \
    --dedup skip

# Full migration (discover + export + import)
python -m tools.mcp.memory.migrate full \
    --search-path /Users/developer \
    --target http://mac-studio:8020
```

7.5. Create HTTP endpoints for bulk import:
```python
# POST /api/memory/import
{
    "entries": [...],
    "source": "migration",
    "dedup_strategy": "skip"
}

# Response
{
    "imported": 150,
    "skipped": 23,
    "errors": []
}
```

7.6. Document migration workflow for existing deployments

**Deliverables**:
- `tools/mcp/memory/migrate/` package
- Migration CLI with discover/export/import commands
- Bulk import HTTP endpoint
- Migration documentation

---

## Service Discovery Detail

The `MemoryHTTPClient` discovers ar-memory using this priority:

```python
def _discover_memory_server() -> str:
    # 1. Explicit environment variable
    if url := os.environ.get("AR_MEMORY_URL"):
        if _ping(url):
            return url

    # 2. Same host as FlowTree controller (production)
    if controller := os.environ.get("AR_CONTROLLER_URL"):
        host = urlparse(controller).hostname
        url = f"http://{host}:8020"
        if _ping(url):
            return url

    # 3. Localhost (development)
    if _ping("http://localhost:8020"):
        return "http://localhost:8020"

    # 4. Known infrastructure host (fallback)
    if _ping("http://mac-studio:8020"):
        return "http://mac-studio:8020"

    # 5. Docker host (container environment)
    if os.path.exists("/.dockerenv"):
        if _ping("http://host.docker.internal:8020"):
            return "http://host.docker.internal:8020"

    raise ConnectionError("ar-memory server not found")
```

---

## API Schema

### Store Request
```json
POST /api/memory/store
{
    "content": "string (required)",
    "repo_url": "string (required)",
    "branch": "string (required)",
    "namespace": "string (default: 'default')",
    "tags": ["string"],
    "source": "string"
}
```

### Store Response
```json
{
    "id": "uuid",
    "namespace": "string",
    "content": "string",
    "repo_url": "string",
    "branch": "string",
    "tags": ["string"],
    "source": "string",
    "created_at": "ISO-8601"
}
```

### Search Request
```json
POST /api/memory/search
{
    "query": "string (required)",
    "namespace": "string (default: 'default')",
    "limit": 5,
    "tag": "string",
    "repo_url": "string",
    "branch": "string"
}
```

### Search Response
```json
{
    "results": [
        {
            "id": "uuid",
            "content": "string",
            "score": 0.123,
            "repo_url": "string",
            "branch": "string",
            "tags": ["string"],
            "created_at": "ISO-8601"
        }
    ],
    "count": 5
}
```

### Branch Context Request
```json
GET /api/memory/branch/{repo_url_encoded}/{branch}?namespace=default&limit=20
```

### Health Check
```json
GET /api/health

Response:
{
    "ok": true,
    "version": "1.0.0",
    "namespaces": ["default", "decisions", "bugs"],
    "total_entries": 1234
}
```

---

## Backward Compatibility

### During Migration (Phases 2-4)
- ar-memory runs in hybrid mode (MCP + HTTP)
- ar-consultant can use either path (feature flag)
- Existing agent sessions continue to work

### After Migration (Phases 5-6)
- ar-memory MCP removed from agent context
- All memory access through HTTP API
- Old `mcp__ar-memory__*` calls will fail with clear error message

### Data Migration
- No data migration needed (same SQLite + FAISS backend)
- Existing memories without branch annotation remain searchable
- New memories require branch annotation

---

## Testing Strategy

### Unit Tests
- `MemoryHTTPClient` against mock server
- ar-memory HTTP endpoints
- ar-consultant with mocked HTTP client
- ar-manager memory tools with mocked HTTP client

### Integration Tests
- ar-memory HTTP server startup and health
- ar-consultant → ar-memory round-trip
- ar-manager → ar-memory round-trip
- Service discovery logic

### End-to-End Tests
- Coding agent stores memory via ar-consultant
- External client retrieves memory via ar-manager
- Branch context retrieval across both paths

---

## Rollback Plan

If issues arise during migration:

1. **Phase 2-4**: Revert ar-consultant to direct import, keep ar-memory MCP
2. **Phase 5**: Restore ar-memory to .mcp.json
3. **Phase 6**: Restore archived code

Git tags at each phase completion enable targeted rollback.

---

## Environment Variables Summary

### ar-memory (HTTP server)
```
AR_MEMORY_HTTP_PORT=8020           # HTTP server port
AR_MEMORY_DATA_DIR=...             # SQLite + FAISS directory
AR_MEMORY_AUTH_TOKEN=...           # Optional bearer token
AR_MEMORY_BACKEND=fastembed        # Embedding backend
```

### ar-consultant / ar-manager (HTTP clients)
```
AR_MEMORY_URL=http://...:8020      # Explicit memory server URL
AR_CONTROLLER_URL=http://...:7780  # Used for service discovery
```

---

## Success Criteria

1. **Functional**: External clients can search and retrieve agent-stored memories
2. **Reliable**: ar-memory server handles concurrent requests from multiple clients
3. **Observable**: Health endpoint reports server status and entry counts
4. **Maintainable**: Single source of truth for memory storage (no duplicate indices)
5. **Backward-compatible**: Existing memories remain accessible

---

## Open Questions

1. **Authentication**: Should ar-memory require authentication, or trust network isolation?
   - Recommendation: Optional bearer token, disabled by default for local development

2. **Rate limiting**: Should ar-memory implement rate limiting?
   - Recommendation: Not initially; add if needed based on usage patterns

3. **Replication**: Should ar-memory support multi-instance deployment?
   - Recommendation: Out of scope for initial refactor; SQLite limits this anyway

4. **Branch validation**: Should ar-memory validate that repo_url/branch exist?
   - Recommendation: No; treat as opaque strings, validation is caller's responsibility

---

## Timeline Estimate

| Phase | Description | Complexity |
|-------|-------------|------------|
| 1 | Shared client library (memory + inference) | Low |
| 2 | HTTP API for ar-memory | Medium |
| 3 | Update ar-consultant (HTTP client + git detection) | Medium |
| 4 | Add memory + LLM to ar-manager | Medium |
| 5 | Remove ar-memory MCP | Low |
| 6 | Cleanup and documentation | Low |
| 7 | Database consolidation and migration tools | Medium |
| Follow-up | Request history centralization | Medium |

**Parallelization**:
- Phases 1-2 can be done in parallel with phases 3-4 preparation
- Phase 7 can begin as soon as Phase 2 is complete (HTTP API available)
- Follow-up work is independent and can be scheduled after core refactoring stabilizes

---

## Follow-Up Work: Request History Consolidation

**Background**: ar-consultant maintains a request history log (`tools/mcp/consultant/data/history.db`) that records every tool invocation with:
- Input parameters
- Documentation chunks retrieved
- Memory hits
- LLM prompts and responses
- Latency metrics
- Session associations

This history is used for:
- Quality evaluation of responses
- Retrieval accuracy assessment
- Fine-tuning dataset construction
- Debugging agent behavior

**Problem**: The history pattern is very similar to ar-memory:
- SQLite storage
- Per-entry metadata
- Query/retrieval operations
- Export capabilities

**Recommendation**: After the core refactoring, consider making request history a native feature of the ar-memory server:

### Option A: Separate History Endpoints in ar-memory

Add dedicated history endpoints alongside memory endpoints:

```python
# POST /api/history/record
{
    "tool_name": "consult",
    "repo_url": "...",
    "branch": "...",
    "input_params": {...},
    "doc_chunks": [...],
    "memory_hits": [...],
    "prompt_text": "...",
    "llm_response": "...",
    "latency_ms": 1234,
    "session_id": "..."
}

# POST /api/history/search
# GET /api/history/export
```

**Advantages**:
- Single service for all persistent agent data
- Centralized history across all agents (not just local)
- ar-manager can also record its request history
- Unified backup/restore

### Option B: History as a Memory Namespace

Treat history records as memories in a special namespace:

```python
memory.store(
    content=json.dumps(history_record),
    repo_url=repo_url,
    branch=branch,
    namespace="ar-consultant-history",
    tags=["tool:consult", f"session:{session_id}"],
)
```

**Advantages**:
- No new API surface
- Semantic search over history ("find requests about attention layers")
- Simpler implementation

**Disadvantages**:
- JSON blob in content field is awkward
- Loses structured query capabilities

### Recommended Approach

Start with **Option A** (separate endpoints) because:
1. History records have different query patterns than memories
2. Export for fine-tuning needs structured access to prompts/responses
3. Latency tracking and metrics are first-class concerns

**Tasks for Follow-Up**:

1. Add history table and endpoints to ar-memory HTTP server
2. Update ar-consultant to use centralized history instead of local history.db
3. Add history recording to ar-manager
4. Create history migration tool (similar to memory migration)
5. Update export_request_history to pull from centralized server

---

## References

- Current ar-memory: `tools/mcp/memory/`
- Current ar-consultant: `tools/mcp/consultant/`
- Current ar-manager: `tools/mcp/manager/`
- Current ar-consultant history: `tools/mcp/consultant/history.py`
- FlowTree controller: `rings/src/.../FlowTreeController.java`
