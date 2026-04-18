# AR Consultant MCP Server

A documentation-aware assistant that combines documentation retrieval, semantic memory, and local LLM inference into a single interface. It provides grounded answers about the AR codebase, contextualized memory recall, and terminology-consistent memory storage.

## Architecture

The consultant wraps three subsystems:

- **DocsRetriever** -- searches the AR documentation corpus (module docs, internals, quick reference, source comments)
- **MemoryClient** -- reads/writes the centralized ar-memory HTTP service via `MemoryHTTPClient` (from `tools/mcp/common/`)
- **InferenceBackend** -- a local LLM that synthesizes retrieved context into concise answers

When no LLM is available, the server falls back to **passthrough mode**, returning raw retrieved documentation without synthesis.

Memory operations require the ar-memory HTTP server to be running (see `tools/mcp/memory/README.md`). When ar-memory is unavailable, memory tools degrade gracefully — returning empty results rather than errors.

Git context (repo_url, branch) is auto-detected from the working directory when not explicitly provided to `remember` and `recall` tools.

## Inference Backends

The consultant supports four inference backends. The backend is selected at server startup via auto-detection or the `AR_CONSULTANT_BACKEND` environment variable.

### Auto-Detection Order

When `AR_CONSULTANT_BACKEND` is unset or `"auto"` (the default), the server tries backends in this order:

1. **llama.cpp** -- checks `localhost:8083`, then `mac-studio:8083` as fallback
2. **Ollama** -- checks `localhost:11434`
3. **MLX** -- checks if `mlx-lm` is importable (Apple Silicon only)
4. **Passthrough** -- always available, no model needed

The backend is created once at startup. If the LLM server becomes available after the consultant starts, **you must restart the MCP server** (toggle it off/on in `/mcp` or restart Claude Code).

### llama.cpp (Recommended)

The recommended backend for most setups. Runs a llama.cpp server on any machine and connects via the OpenAI-compatible HTTP API.

#### Starting the Server

```bash
llama-server \
    -m /path/to/model.gguf \
    --host 0.0.0.0 \
    --port 8083 \
    -ngl 99 \
    -c 8192
```

Key flags:
- `--host 0.0.0.0` -- listen on all interfaces (required for remote access)
- `--port 8083` -- the default port the consultant checks
- `-ngl 99` -- offload all layers to GPU
- `-c 8192` -- context window size

Recommended models (Q4_K_M or Q5_K_M quantization):
- `qwen2.5-coder-32b-instruct` -- best quality for code understanding
- `qwen2.5-coder-14b-instruct` -- good balance of speed and quality

#### Remote Setup (e.g., Mac Studio)

When running llama-server on a remote machine (e.g., `mac-studio`) and the consultant on your laptop:

1. Start llama-server on the remote host with `--host 0.0.0.0`
2. The consultant auto-detects `mac-studio:8083` as a fallback when `localhost:8083` is unreachable

To explicitly set the URL:
```bash
export AR_CONSULTANT_LLAMACPP_URL=http://mac-studio:8083
```

#### macOS Firewall (Common Issue)

**If the server is running but unreachable from another machine**, the macOS Application Firewall is almost certainly blocking it. Symptoms:

- `nc -z mac-studio 8083` succeeds (TCP connects)
- `curl http://mac-studio:8083/health` hangs and eventually times out
- `curl http://localhost:8083/health` works on the server machine itself

The macOS firewall accepts the TCP connection at the kernel level (so `nc` succeeds) but blocks the application from receiving data (so HTTP requests hang indefinitely with no response).

**Fix:**

```bash
# Check firewall state
/usr/libexec/ApplicationFirewall/socketfilterfw --getglobalstate

# Allow llama-server through the firewall
sudo /usr/libexec/ApplicationFirewall/socketfilterfw --add $(which llama-server)
sudo /usr/libexec/ApplicationFirewall/socketfilterfw --unblockapp $(which llama-server)

# Restart llama-server after changing firewall rules
```

This applies to any llama.cpp binary name (`llama-server`, `llama-server-metal`, etc.).

#### Container Setup (Docker/Linux)

When the consultant runs inside a container and llama-server runs on the Docker host:

```bash
export AR_CONSULTANT_LLAMACPP_URL=http://host.docker.internal:8083
```

This is the default when a container is detected (via `/.dockerenv`).

### Ollama

Uses the Ollama HTTP API. Ollama must be running and the model must be pulled.

#### Starting the Server

```bash
# Start Ollama (if not already running as a service)
ollama serve

# Pull the default model
ollama pull qwen2.5-coder:32b-instruct-q4_K_M
```

#### Configuration

```bash
# Custom model
export AR_CONSULTANT_MODEL=qwen2.5-coder:14b-instruct-q4_K_M

# Custom Ollama URL (default: http://localhost:11434)
export AR_CONSULTANT_OLLAMA_URL=http://mac-studio:11434
```

### MLX (Apple Silicon)

Native inference on Apple Silicon using MLX-LM. No separate server needed, but the model loads into the consultant process memory.

#### Installation

```bash
pip install mlx>=0.4.0 mlx-lm>=0.4.0
```

#### Configuration

```bash
# Custom model (default: mlx-community/Qwen2.5-Coder-32B-Instruct-4bit)
export AR_CONSULTANT_MLX_MODEL=mlx-community/Qwen2.5-Coder-14B-Instruct-4bit
```

The model is downloaded on first use from Hugging Face.

### Passthrough (No Model)

Returns retrieved documentation context directly without LLM synthesis. Useful for testing the retrieval pipeline or when no GPU is available.

```bash
export AR_CONSULTANT_BACKEND=passthrough
```

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `AR_CONSULTANT_BACKEND` | `auto` | Backend selection: `llamacpp`, `ollama`, `mlx`, `passthrough`, or `auto` |
| `AR_CONSULTANT_LLAMACPP_URL` | `localhost:8083` (host) / `host.docker.internal:8083` (container) | llama.cpp server URL |
| `AR_CONSULTANT_OLLAMA_URL` | `http://localhost:11434` | Ollama server URL |
| `AR_CONSULTANT_MODEL` | `qwen2.5-coder:32b-instruct-q4_K_M` | Ollama model name |
| `AR_CONSULTANT_MLX_MODEL` | `mlx-community/Qwen2.5-Coder-32B-Instruct-4bit` | MLX model path |
| `AR_CONSULTANT_HISTORY_DIR` | `tools/mcp/consultant/data` | Directory for `history.db` |
| `AR_MEMORY_URL` | (auto-discovered) | ar-memory HTTP server URL |

## Available Tools

| Tool | Purpose |
|------|---------|
| `consult` | Ask a question, get a documentation-grounded answer |
| `search_docs` | Search docs with consultant summary |
| `recall` | Search memories contextualized with docs |
| `remember` | Store a memory with consultant reformulation |
| `start_consultation` | Begin a multi-turn session |
| `continue_consultation` | Follow up in a session |
| `end_consultation` | End session and optionally store summary |
| `consultant_status` | Check backend health and configuration |
| `list_request_history` | List recent tool invocations |
| `export_request_history` | Export full history for analysis |

## Troubleshooting

### "passthrough (no model)" in consultant_status

The consultant could not find any LLM backend at startup. Check:

1. Is llama-server / Ollama actually running? (`curl http://localhost:8083/health`)
2. If running on a remote host, is it reachable? (`curl http://mac-studio:8083/health`)
3. If reachable via `nc` but not `curl`, check the [macOS firewall](#macos-firewall-common-issue) section
4. After fixing, **restart the MCP server** -- the backend is cached at startup

### "memory_available: false" in consultant_status

The ar-memory HTTP server is not reachable. Check:

1. Is ar-memory running? (`curl http://localhost:8020/api/health`)
2. If using Docker: `docker compose -f flowtree/controller/docker-compose.yml ps ar-memory`
3. Set `AR_MEMORY_URL` explicitly if auto-discovery fails

### Slow first response

The first request after startup may be slow because:
- MLX backend lazy-loads the model on first inference
- llama.cpp may be loading the model into GPU memory
- Documentation embeddings are computed on first search

### Request history

All tool invocations are recorded in `data/history.db` (SQLite). Use `list_request_history` to inspect recent calls or `export_request_history` to export for analysis.
