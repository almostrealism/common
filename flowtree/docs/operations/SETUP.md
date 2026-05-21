# Operator Setup

How to bring up a FlowTree controller stack and agent pool on a fresh
host. This document is the operator's index — it points to the existing,
authoritative reference for each piece rather than restating it.

For background on what the stack is, see
[../../README.md](../../README.md) (top-level FlowTree overview) and
[../../runtime/README.md](../../runtime/README.md) (controller / agent
deployment details).

---

## One-command bring-up

```sh
./flowtree/runtime/rebuild.sh                # controller stack only
./flowtree/runtime/rebuild.sh --agents       # controller stack + agent pool
./flowtree/runtime/rebuild.sh --agents-only  # agent pool only
./flowtree/runtime/rebuild.sh --with-llm     # also start a local LLM server for opencode
```

The script is documented in detail in
[../../runtime/README.md](../../runtime/README.md). The `--with-llm` flag
is documented in [OPENCODE.md](OPENCODE.md).

---

## Where things live

| Concern | Where it is configured | Reference |
|---------|------------------------|-----------|
| Workstreams (Slack channel ↔ git repo / branch mapping) | `/Users/Shared/flowtree/controller/workstreams.yaml` (operator-managed; example at `flowtree/runtime/src/main/resources/workstreams-example.yaml`) | [runtime/README.md § Workstream configuration](../../runtime/README.md#workstream-configuration) |
| Slack tokens, GitHub orgs, ar-manager bearer secret | `/Users/Shared/flowtree/secrets/` (operator-managed; the shared secret is auto-generated on first `rebuild.sh` run) | [`tools/mcp/SECRETS.md`](../../../tools/mcp/SECRETS.md) |
| Tracker / project board integration | `tools/mcp/manager/server.py` (`tracker_*` MCP tools) | [`tools/mcp/CLAUDE.md`](../../../tools/mcp/CLAUDE.md) |
| Agent container env (`FLOWTREE_ROOT_HOST`, `CLAUDE_CODE_OAUTH_TOKEN`) | `flowtree/runtime/agent/.env` (created interactively on first `rebuild.sh --agents` run) | [`runtime/README.md § Running the Agent Pool`](../../runtime/README.md#running-the-agent-pool) |
| MCP tool inventory pushed to agents (`AR_MANAGER_TOOL_NAMES`) | controller env / `WorkstreamConfig` | [`tools/mcp/CLAUDE.md`](../../../tools/mcp/CLAUDE.md) |
| Per-phase agent runner selection | per-job (`workstream_submit_task`), workstream defaults (`WorkstreamConfig`), or controller default | [../architecture/PHASES.md](../architecture/PHASES.md), [RECIPES.md](RECIPES.md) |

---

## First-time bring-up checklist

1. Clone the repo and `cd` into it.
2. Provision `/Users/Shared/flowtree/controller/workstreams.yaml`. See the
   example file in `flowtree/runtime/src/main/resources/`.
3. Run `./flowtree/runtime/rebuild.sh`. This generates the ar-manager shared
   secret if absent, builds the flowtree JARs, and starts the controller
   stack (`flowtree-controller`, `ar-manager`, `ar-memory`).
4. (Optional — for a local LLM for opencode) Run
   `./flowtree/runtime/rebuild.sh --with-llm`. See [OPENCODE.md](OPENCODE.md).
5. Run `./flowtree/runtime/rebuild.sh --agents-only`. The script prompts for the
   controller host and a Claude Code OAuth token on first run; subsequent
   runs reuse the saved `.env`.
6. (Optional, only if enabling the tmux launch backend) Log the agent
   containers in to Claude. By default `ClaudeCodeRunner` uses the direct
   `ProcessBuilder` launch path and relies on the headless OAuth token
   provided via `.env`; no extra step is needed.

   To switch to the tmux-backed launch (see
   [../architecture/AGENT_RUNNERS.md § Subprocess launch backends](../architecture/AGENT_RUNNERS.md#subprocess-launch-backends)),
   set `AR_AGENT_USE_TMUX=enabled` in `flowtree/runtime/agent/.env` and
   bounce the container. That mode requires interactively cached
   credentials, not a long-lived headless token, so for each agent
   container run:

   ```sh
   docker exec -it <agent-container> claude login
   ```

   `claude` prints a URL and a verification code; open the URL in your
   browser, paste the code, and the CLI writes credentials under
   `~/.claude/`. Mount that directory as a persistent volume so the
   credentials survive container restarts. Subsequent jobs run
   unattended. Re-run the login if a job ever fails with an auth error.
7. (Optional) Configure Tailscale Funnel for public access to ar-manager.
   The `rebuild.sh` Funnel check at the end of every run reports up/down
   status and prints the command to restore service. See
   [runtime/README.md](../../runtime/README.md) for context.

---

## Day-to-day operations

| Task | Command |
|------|---------|
| Restart the controller stack | `./flowtree/runtime/rebuild.sh` |
| Restart the agent pool | `./flowtree/runtime/rebuild.sh --agents-only` |
| Restart a single controller service | `./flowtree/runtime/rebuild.sh flowtree-controller` (or `ar-manager`, etc.) |
| Tail controller logs | `docker compose -f flowtree/runtime/controller/docker-compose.yml logs -f flowtree-controller` |
| Tail agent logs | `docker compose -f flowtree/runtime/agent/docker-compose.yml logs -f` |
