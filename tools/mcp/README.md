# AR MCP Servers

Model Context Protocol (MCP) servers that provide specialized tooling for AI-assisted development of the Almost Realism framework. These servers run as sidecar processes alongside Claude Code, extending its capabilities with project-specific tools.

## Servers

| Server | Directory | Description |
|--------|-----------|-------------|
| **ar-consultant** | [consultant/](consultant/) | Documentation-aware assistant with local LLM inference, memory, and doc retrieval |
| **ar-memory** | [memory/](memory/) | Persistent semantic memory with embedding-based search |
| **ar-test-runner** | [test-runner/](test-runner/) | Async test execution with structured result parsing |
| **ar-jmx** | [jmx/](jmx/) | JVM memory diagnostics via JDK tools (jcmd, jstat, JFR) |
| **ar-profile-analyzer** | [profile-analyzer/](profile-analyzer/) | Profile XML analysis for performance investigation |
| **ar-github** | [github/](github/) | GitHub PR review comments and conversation tools |
| **ar-slack** | [slack/](slack/) | Slack messaging for agent status updates |

## Installation

Install all server dependencies at once:

```bash
pip install -r tools/mcp/requirements.txt
```

Individual servers also have their own `requirements.txt` files.

## MCP Configuration

All servers are registered in the project's `.mcp.json` file at the repository root. Claude Code discovers and launches them automatically. Enable/disable specific servers via `.claude/settings.json`.

## LLM Backend Setup (ar-consultant)

The `ar-consultant` server requires a local LLM for documentation synthesis. Without one, it falls back to passthrough mode (returning raw docs without synthesis).

**See [consultant/README.md](consultant/README.md) for detailed backend setup instructions**, including:

- **llama.cpp server** (recommended) -- how to start, configure, and troubleshoot remote setups
- **Ollama** -- model pulling and configuration
- **MLX** -- native Apple Silicon inference
- **macOS firewall issues** -- a common gotcha when running the LLM server on a remote Mac (e.g., Mac Studio) where TCP connects but HTTP requests hang

### Quick Start (llama.cpp)

On the machine with a GPU:

```bash
llama-server -m /path/to/model.gguf --host 0.0.0.0 --port 8083 -ngl 99 -c 8192
```

If running on a remote machine, ensure the macOS firewall allows llama-server:

```bash
sudo /usr/libexec/ApplicationFirewall/socketfilterfw --add $(which llama-server)
sudo /usr/libexec/ApplicationFirewall/socketfilterfw --unblockapp $(which llama-server)
```

The consultant auto-detects the server at `localhost:8083` or `mac-studio:8083`.

## Server Details

Each server has its own README with tool reference and configuration:

- [ar-consultant](consultant/README.md) -- backend setup, environment variables, troubleshooting
- [ar-memory](memory/README.md) -- embedding backends, data directory configuration
- [ar-test-runner](test-runner/README.md) -- test parameters, depth filtering, result parsing
- [ar-jmx](jmx/README.md) -- JVM attachment, heap analysis, JFR recording workflows
- [ar-profile-analyzer](profile-analyzer/README.md) -- profile loading, timing breakdown, operation search
