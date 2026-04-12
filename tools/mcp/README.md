# AR MCP Servers

Model Context Protocol (MCP) servers that provide specialized tooling for AI-assisted development. These servers extend Claude Code with project-specific tools for documentation, memory, testing, messaging, and more.

## Architecture: ar-manager as Single Entry Point

ar-manager is the **single centralized MCP tool** for all agent jobs. It provides messaging, memory, GitHub, and workstream tools through one HTTP endpoint with HMAC-based temporary authentication.

```
┌─────────────────────────────────────────────────────────────┐
│                    Agent Contexts                            │
│                                                             │
│  Interactive (this repo)   FlowTree Job    FlowTree Job     │
│  ┌──────────────┐        ┌──────────────┐ ┌──────────────┐  │
│  │ Claude Code  │        │ Claude Code  │ │ Claude Code  │  │
│  │ (local)      │        │ (container)  │ │ (container)  │  │
│  │              │        │ other repo   │ │ this repo    │  │
│  └──────┬───────┘        └──────┬───────┘ └──────┬───────┘  │
│         │ stdio                 │ HTTP+HMAC      │ HTTP+HMAC│
│         ▼                       ▼                ▼          │
│  ┌──────────────────────────────────────────────────────┐   │
│  │                    ar-manager                         │   │
│  │              (port 8010, Docker service)               │   │
│  │                                                       │   │
│  │  Tools: send_message, memory_recall, memory_store,    │   │
│  │         memory_branch_context, github_pr_find,        │   │
│  │         github_pr_review_comments, github_pr_reply,   │   │
│  │         github_create_pr, workstream_get_status, ...  │   │
│  └───┬──────────────┬──────────────┬─────────────────────┘   │
│      │              │              │                         │
│      ▼              ▼              ▼                         │
│  ┌────────┐   ┌──────────┐   ┌──────────┐                  │
│  │Controller│   │ar-memory │   │GitHub API│                  │
│  │ :7780   │   │  :8020   │   │          │                  │
│  │         │   │          │   │          │                  │
│  │ messages│   │ store    │   │ PRs      │                  │
│  │ notify  │   │ search   │   │ commits  │                  │
│  │ stats   │   │ branch   │   │ workflows│                  │
│  └─────────┘   └──────────┘   └──────────┘                  │
└─────────────────────────────────────────────────────────────┘
```

### Three Contexts, One Tool

| Context | How agent reaches ar-manager | Auth |
|---|---|---|
| Interactive (this repo) | stdio subprocess via `.mcp.json` | None (local process) |
| FlowTree job (this repo) | HTTP to ar-manager service | HMAC temp token |
| FlowTree job (other repo) | HTTP to ar-manager service | HMAC temp token |

### HMAC Temporary Tokens

The controller generates a temporary HMAC token at job submission time:
- Token format: `armt_tmp_{hmac}:{payload}` where payload = `{workstreamId}:{jobId}:{expiry}`
- Both controller and ar-manager share `AR_MANAGER_SHARED_SECRET`
- Tokens are valid for 12 hours and grant `read`, `write`, and `memory` scopes
- The workstream_id and job_id are embedded in the token, so tools like `send_message` and `github_pr_find` automatically resolve context

### Tools Available to Agents

| Tool | Purpose |
|------|---------|
| `send_message` | Send a message (stored in memory, optionally notified via Slack) |
| `memory_recall` | Semantic search across agent memories |
| `memory_store` | Store a new memory entry |
| `memory_branch_context` | Get all memories + commit history for a branch |
| `github_pr_find` | Find open PR for a branch |
| `github_pr_review_comments` | Get code review comments on a PR |
| `github_pr_conversation` | Get PR conversation (issue comments) |
| `github_pr_reply` | Reply to a review comment |
| `github_list_open_prs` | List open pull requests |
| `github_create_pr` | Create a pull request |
| `workstream_get_status` | Get job timing statistics |
| `controller_health` | Check controller liveness |

**Note:** ar-manager currently calls the GitHub API directly for PR operations. We plan to route these through the controller proxy (like messages) in a future update.

### Project-Local Tools

For agents working on **this repo** (almostrealism/common), additional project-local tools are available via `.mcp.json`:

| MCP Tool | Description |
|---|---|
| **ar-consultant** | Documentation-aware assistant with LLM inference and memory |
| **ar-build-validator** | Static analysis: checkstyle, code policy, test timeouts, duplicate code, ErrorProne |
| **ar-test-runner** | Async test execution with structured result parsing |
| **ar-jmx** | JVM memory diagnostics via JDK tools |
| **ar-profile-analyzer** | Profile XML analysis for performance investigation |
| **ar-docs** | Documentation search |

These run locally via stdio and are only available when the project's `.mcp.json` defines them.

### Configuration

For FlowTree jobs to have ar-manager access, set these environment variables on the controller and ar-manager Docker services:

| Variable | Where | Purpose |
|---|---|---|
| `AR_MANAGER_URL` | Controller | URL of the ar-manager service (default: `http://ar-manager:8010`) |
| `AR_MANAGER_SHARED_SECRET` | Controller + ar-manager | Shared secret for HMAC token generation. Generate with `openssl rand -base64 32` |

See `flowtree/src/main/resources/workstreams-example.yaml` for full configuration reference.

## Servers

| Server | Directory | Description |
|--------|-----------|-------------|
| **ar-manager** | [manager/](manager/) | Centralized MCP endpoint for messaging, memory, GitHub, and workstream management |
| **ar-consultant** | [consultant/](consultant/) | Documentation-aware assistant with local LLM inference, memory, and doc retrieval |
| **ar-memory** | [memory/](memory/) | Centralized HTTP memory service with embedding-based search |
| **ar-build-validator** | [build-validator/](build-validator/) | Static analysis: checkstyle, code policy, test timeouts, duplicate code, ErrorProne |
| **ar-test-runner** | [test-runner/](test-runner/) | Async test execution with structured result parsing |
| **ar-jmx** | [jmx/](jmx/) | JVM memory diagnostics via JDK tools (jcmd, jstat, JFR) |
| **ar-profile-analyzer** | [profile-analyzer/](profile-analyzer/) | Profile XML analysis for performance investigation |

### Shared Libraries

| Directory | Description |
|-----------|-------------|
| **common/** | `MemoryHTTPClient`, `InferenceBackend`, `git_context` — shared by ar-consultant and ar-manager |

## Docker Infrastructure

ar-memory, the FlowTree controller, and ar-manager run as Docker services
defined in `tools/docker-compose.yml`:

```bash
# Pre-build the controller JAR (from repo root)
mvn package -pl flowtree -am -DskipTests
mvn dependency:copy-dependencies -pl flowtree -DoutputDirectory=target/dependency

# Start all services
docker compose -f tools/docker-compose.yml up -d
```

**Host directories:**
- `/Users/Shared/flowtree/controller/` — `workstreams.yaml`, notification tokens
- `/Users/Shared/flowtree/manager/` — `manager-tokens.json`
- `/Users/Shared/flowtree/memory-data/` — SQLite DB and FAISS indices

See `tools/docker-compose.yml` for full configuration.

## Installation

Install all server dependencies at once:

```bash
pip install -r tools/mcp/requirements.txt
```

Individual servers also have their own `requirements.txt` files.

## LLM Backend Setup (ar-consultant)

The `ar-consultant` server requires a local LLM for documentation synthesis. Without one, it falls back to passthrough mode (returning raw docs without synthesis).

**See [consultant/README.md](consultant/README.md) for detailed backend setup instructions.**

## Server Details

Each server has its own README with tool reference and configuration:

- [ar-manager](manager/README.md) -- authentication, deployment, tool tiers
- [ar-consultant](consultant/README.md) -- backend setup, environment variables, troubleshooting
- [ar-memory](memory/README.md) -- HTTP API reference, Docker deployment, URL normalization
- [ar-test-runner](test-runner/README.md) -- test parameters, depth filtering, result parsing
- [ar-jmx](jmx/README.md) -- JVM attachment, heap analysis, JFR recording workflows
- [ar-profile-analyzer](profile-analyzer/README.md) -- profile XML analysis
