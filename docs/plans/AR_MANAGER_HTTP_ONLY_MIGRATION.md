# ar-manager HTTP-Only Migration

Status: **IN PROGRESS — Phases 0–3 implemented in this PR; Phase 4 (docs/tests
cleanup) and the server.py split are the remaining follow-ups**
Author: planning session, 2026-06-16

## Decisions (owner, 2026-06-16)

- **claude.ai / Claude mobile (OAuth) is the primary use case and MUST be
  preserved unchanged.** This is by far the majority of ar-manager usage. The
  OAuth 2.1 + PKCE + DCR flow that mcp.almostrealism.ai advertises stays. This
  consolidation only changes usage that *originates from inside the repo*
  (interactive Claude Code and coding-agent jobs) so that it respects auth — it
  does **not** touch the external connector flow.
- **Repo-originated interactive auth = long-lived personal tokens.** For a
  developer running Claude Code against this repo, a personal bearer token —
  never committed (shell env / user-scoped file, referenced from a *user-scoped*
  MCP config) — replaces the old tokenless stdio entry.
- **Endpoint shape = whatever is simplest, but it must serve BOTH** OAuth (for
  claude.ai/mobile) and presented bearer tokens (for repo interactive + jobs) on
  the same server. No preference between one route vs two; pick the least-code
  option that keeps both working. (Earlier draft floated dropping OAuth — that is
  rejected: OAuth is the main path.)
- **`--strict-mcp-config` is OFF the table.** Flowtree coding-agent jobs must
  keep receiving the MCP servers this repo's `.mcp.json` requests
  (ar-test-runner, ar-build-validator, ar-docs, ar-jmx, …). We therefore do
  **not** lock the worker to the controller-supplied config. Instead:
  1. Remove the `ar-manager` entry from this repo's `.mcp.json`, and
  2. Harden the ar-manager implementation so it is difficult/impossible to use
     the way the old `.mcp.json` entry used it (tokenless python stdio). The
     escape hatch is closed at the **server**, not at the client config.

This makes the consolidation robust regardless of how any `.mcp.json` (this repo's
or another's) is written or merged: even if a stdio `ar-manager` entry exists, the
server refuses to operate without an authenticated HTTP token.

## 1. Problem statement

`ar-manager` is reachable two completely different ways today, and they have
different security properties:

1. **HTTP + bearer token** — used by flowtree coding-agent jobs. The controller
   mints a short-lived HMAC token that encodes `workstreamId:jobId:expiry`, and
   `McpConfigBuilder` emits an `ar-manager` HTTP entry with
   `Authorization: Bearer armt_tmp_…`. Every tool call carries the token, so the
   server can bind `send_message` / `memory_store` / scope checks to the job.

2. **Python stdio, no token** — used interactively (and, dangerously, also
   present inside coding-agent worker checkouts). The repo's root `.mcp.json`
   defines:

   ```json
   "ar-manager": {
     "command": "python3",
     "args": ["tools/mcp/manager/server.py"],
     "env": { "AR_CONTROLLER_URL": "http://mac-studio:7780" }
   }
   ```

   In this mode the server runs with **no auth** (`server.py` prints
   "WARNING: No auth tokens configured — running without authentication" and
   every scope check becomes a no-op — see §3.3).

The core defect: **the token carries the identity and authority of the caller**
(which job, which workspace, what permissions), and the stdio path discards it.
A job can be launched with a correct job-scoped token, yet perform ar-manager
operations through the tokenless stdio path that are not attributed to the job
and are not constrained by the token's scopes. The two paths are not equivalent,
so "which one am I on?" silently changes behavior. We want a single model:
**HTTP only, token always required, no tokenless escape hatch anywhere.**

This document began as a pure study of the current implementation (evidence,
hazards, and a phased plan). It has since been carried into implementation:
Phases 0–3 are done (see the per-phase status in §8), and the changes they
describe land in this PR. The "Evidence" and "Design" sections below describe
the pre-migration state and the target design respectively; §8 is the source of
truth for what is actually implemented.

## 2. Evidence — how it works today

### 2.1 Token minting (controller, Java)

- `SecretsRequestHandler.generateTemporaryToken(workstreamId, jobId, sharedSecret, ttlSeconds)`
  (`flowtree/runtime/.../api/SecretsRequestHandler.java`, ~L180–216).
  - Payload string: `workstreamId + ":" + jobId + ":" + expiry`.
  - Token: `armt_tmp_<base64url(HMAC-SHA256(payload))>:<base64url(payload)>`.
  - Secret: `AR_MANAGER_SHARED_SECRET` (or `…_FILE`), via
    `FlowTreeController.loadSharedSecret()`.
  - TTL currently **43200s (12h)**.
- Called from `FlowTreeApiEndpoint` (~L880–890) at job creation; the token is
  pushed onto `CodingAgentJobFactory.setArManagerToken(...)` (base64-stored in
  job state), copied to `CodingAgentJob`, and applied to the builder in
  `CodingAgentJob.configureMcpBuilder()` (~L1005–1008).

### 2.2 Token delivery to the worker

- `McpConfigBuilder.buildMcpConfig()`
  (`flowtree/runtime/.../jobs/McpConfigBuilder.java`, L390–445) emits, **only when
  both URL and token are set**:
  ```json
  "ar-manager": { "type":"http", "url":"…", "headers":{"Authorization":"Bearer …"} }
  ```
- `ClaudeCodeRunner.buildCommandLine()` passes this JSON via **`--mcp-config`**
  (`flowtree/agents/.../agent/ClaudeCodeRunner.java:240`). **`--strict-mcp-config`
  is NOT passed** (verified: only `--mcp-config` appears).
- OpenCode: `OpencodeConfigBuilder.translateMcpServers()` (L360–426) converts the
  HTTP entry to `type:"remote"` and, when an `Authorization` header is present,
  sets **`oauth:false`** so OpenCode uses the static bearer verbatim instead of
  initiating OAuth discovery against ar-manager's 401 (L376–404).
- `McpConfigBuilder.applyAgentEnvironment()` (L630–641) also exports
  `AR_MANAGER_TOKEN`, `AR_WORKSTREAM_URL`, `AR_CONTROLLER_URL`,
  `AR_WORKSTREAM_ID` into the worker env — consumed by the in-container
  `ar-secrets` MCP server (`tools/mcp/secrets/server.py`), not by ar-manager.

### 2.3 Token validation (two independent implementations)

- **Python** `tools/mcp/manager/server.py`:
  - `MCP_TRANSPORT` selects `stdio` (default, `mcp.run()`), `http`, or `sse`.
  - In `http`/`sse` mode a middleware stack wraps the app:
    Health → RateLimit → OAuth → **BearerAuth** → MCP app.
  - `BearerAuthMiddleware` (L569–763): matches static tokens (timing-safe), else
    falls back to `_validate_temp_token()` (L410–463) which re-checks the HMAC
    with `AR_MANAGER_SHARED_SECRET`, parses `workstream_id:job_id:expiry`, checks
    expiry, and grants scopes
    `["read","write","submit","github","memory-read","memory-write"]`, setting
    request-scoped token context (`ws_id`, `job_id`) used by `send_message`,
    `memory_store`, etc.
  - OAuth 2.1 + PKCE + DCR is advertised for interactive remote clients
    (`/.well-known/oauth-*`, `/oauth/*` are auth-exempt).
- **Java** `SecretsRequestHandler.extractWorkstreamIdFromTempToken()` (L296–362)
  validates the same token format for the controller's `/api/secrets` endpoint.

### 2.4 mcp.almostrealism.ai

Reverse proxy (Tailscale Funnel / Caddy / nginx) terminating TLS in front of the
ar-manager HTTP server (port 8010), which in turn talks to the FlowTree
controller (port 7780) on the private network. See `tools/mcp/manager/README.md`
and `docs/mcp/claude-ai-connector-defect.md`.

## 3. The escape hatches (why "HTTP-only" is not automatic)

### 3.1 `--mcp-config` MERGES with the worker's checked-out `.mcp.json`

Claude Code merges `--mcp-config` with project/user `.mcp.json` **unless
`--strict-mcp-config` is passed**. We do not pass it. The worker checks out this
repo, whose `.mcp.json` defines `ar-manager` as a **python stdio, no-token**
server. Result: a name collision on `ar-manager` between the controller's HTTP
entry and the repo's stdio entry, with merge precedence that is version-dependent
and not guaranteed to favor the HTTP entry. **This is the primary escape hatch on
the client side** — a worker can end up talking to a tokenless python ar-manager
that it spawned from its own checkout.

`McpConfigBuilder.discoverProjectMcpServers()` deliberately skips `ar-manager`
(L667–668) when building the controller config, which prevents *duplication
within the generated JSON* — but does nothing about the *separately-merged*
project `.mcp.json`.

**Per the owner decision, we do NOT close this with `--strict-mcp-config`** — the
worker must keep getting the repo's other MCP servers via merge. Instead we (a)
remove `ar-manager` from the repo `.mcp.json` so this repo no longer requests a
stdio ar-manager, and (b) make the *server* reject the stdio/no-token mode (§5.1),
so any leftover or third-party stdio `ar-manager` entry simply fails to function.

### 3.2 No-auth mode in the controller (Java)

`SecretsRequestHandler.isAdminToken()` returns **`true` when the shared secret is
null/empty** (~L384–396) — i.e. an unconfigured controller treats every request,
even with no `Authorization` header, as admin.

### 3.3 No-auth mode in the server (Python)

`server.py` `_require_scope()` / `_get_scopes()` (L491–503) and
`_require_workstream_in_scope()` (L230–254) **permit everything when no tokens are
configured** (stdio mode). Documented in `tools/mcp/manager/README.md` as
"no-auth mode … for trusted LAN use".

These three together mean "use HTTP with a token" is currently a *convention*,
not an *invariant*. Removing the hatches is the substance of requirement (3).

## 4. Claude Code remote-MCP auth constraints (interactive requirement)

From current Claude Code / MCP behavior (researched this session):

- HTTP MCP entries support a static bearer via `headers.Authorization`, with
  `${ENV_VAR}` substitution (expanded at session start from the shell env; no
  `.env` autoloading).
- Interactive OAuth uses **OAuth 2.1 + PKCE**, RFC 9728 protected-resource
  metadata discovery, RFC 7591 dynamic client registration, and an **RFC 8252
  loopback redirect** to `http://localhost:<ephemeral-port>/callback` (opens the
  system browser).
- **Over SSH there is no browser and no localhost redirect.** Known options:
  - `ssh -N -L <port>:127.0.0.1:<port>` to tunnel the loopback callback back to
    the local browser (works today, manual).
  - Device-code flow (RFC 8628) / manual code paste — **not implemented by Claude
    Code today**; would require our own UX.
- **Bug #59467**: when a server advertises OAuth, Claude Code may *ignore* a
  statically-configured bearer header and fall into OAuth discovery. Our existing
  flowtree path avoids this for OpenCode via `oauth:false`; for Claude Code the
  static bearer in `--mcp-config` is currently honored, but this is fragile if the
  same endpoint also advertises OAuth.

**Design consequence:** headless bearer auth and interactive OAuth should not
fight over the same behavior on the same endpoint. We separate them (see §5.3).

## 5. Proposed design

### 5.1 Single transport: HTTP, token mandatory — enforced at the server

This is the load-bearing change. Because we are not using `--strict-mcp-config`,
the *only* reliable way to guarantee "no tokenless ar-manager anywhere" is to make
the server itself incapable of running that way.

- **Remove stdio mode from `server.py`.** Today `MCP_TRANSPORT` unset →
  `mcp.run()` (stdio). Change the entrypoint so the server only ever runs as an
  authenticated HTTP/SSE service. If invoked as `python3 server.py` without HTTP
  transport configured, it must **exit immediately with a clear error**
  ("ar-manager runs only as an authenticated HTTP server; point your MCP client
  at mcp.almostrealism.ai"). This means the old `.mcp.json`-style stdio entry
  cannot start a working server, regardless of merge behavior — closing §3.1 at
  the source.
- **Server refuses to start without auth configured** (shared secret and/or token
  file). Delete the "no-auth mode" branch (§3.3): no tokens ⇒ fatal startup
  error, not a warning, and no implicit all-scopes grant.
- **Controller refuses to treat a missing secret as admin** (§3.2): remove the
  `sharedSecret == null → true` branch in `isAdminToken()`. An unconfigured
  controller rejects privileged requests instead of allowing them.

After these, the three escape hatches of §3 are all structurally gone: there is
no tokenless transport, no no-auth server mode, and no no-secret admin bypass.

### 5.2 Workers keep the repo's MCP config; only ar-manager changes

- **Keep merge (no `--strict-mcp-config`).** The worker continues to receive the
  repo's requested MCP servers (ar-test-runner, ar-build-validator, ar-docs,
  ar-jmx, …) via the normal `.mcp.json` merge, exactly as today. No regression to
  the rest of the tool surface.
- **Remove the `ar-manager` entry from the repo root `.mcp.json`.** The repo no
  longer requests a stdio ar-manager. Jobs still get ar-manager as the HTTP entry
  `McpConfigBuilder` injects (unchanged). The existing `ar-manager` skip in
  `discoverProjectMcpServers()` (L667–668) becomes a harmless no-op and can stay.
- **Fail the job loudly if a token is missing** but ar-manager is expected. Today
  `buildMcpConfig()` silently omits ar-manager when the token is empty; under the
  new model that is a hard configuration error for an agent job, not a silent
  drop to "no ar-manager."

Defense in depth: even if some other repo's `.mcp.json` (or a stale branch of
this one) still carries a stdio `ar-manager` entry, §5.1 makes that entry fail to
produce a working server, so it cannot become a tokenless back-channel.

### 5.3 Interactive access for humans — long-lived personal tokens

Decision: **long-lived personal tokens**, never committed to the repo.

Mechanism (simplest viable, reuses what already exists):

- A personal token is a **static bearer entry in the server's token table**
  (`manager-tokens.json`, default `~/.config/ar/manager-tokens.json`, or
  `AR_MANAGER_TOKENS`) carrying human scopes and **no job binding**. The existing
  `BearerAuthMiddleware` static-token path already matches and authorizes these
  (L569–763) — no new token format required. The entry schema (confirmed at
  `server.py` L598–609) is:
  ```json
  {"tokens": [
    {"value": "<random-secret>",
     "scopes": ["read","write","submit","github","memory-read","memory-write"],
     "label": "michael-laptop",
     "workspaceScopes": []}
  ]}
  ```
  `workspaceScopes: []` (or omitted) = unscoped/superadmin; a list narrows to
  those Slack workspace IDs. Minting (per owner decision) = **hand-editing this
  file** on the server. No CLI for now.
- The developer registers ar-manager in their **user-scoped** Claude config (not
  in any repo file) with the token value in the header:
  `claude mcp add --transport http --scope user ar-manager \
   https://mcp.almostrealism.ai/… --header "Authorization: Bearer armt_…"`.
  This writes the entry to `~/.claude.json` (outside any repo); `--scope user`
  applies it to every session. **Owner decision: store the literal token in
  `~/.claude.json` (chmod 600) rather than an env var** — an env var is inherited
  by every child process of the shell, a larger exposure surface than one
  600-perm config file. (`${ENV}` expansion is still supported as an alternative
  for anyone who prefers it.)
- **Remote over SSH just works** with this approach: a static bearer needs no
  browser and no localhost redirect. This is *why* personal tokens cleanly solve
  the SSH case that OAuth cannot — no device-code flow or `ssh -L` tunnel needed.

OAuth stays advertised on the server — it is the path claude.ai and Claude mobile
use, and that is the dominant use case. So the server must serve **both** a
presented bearer (jobs' HMAC tokens, devs' personal tokens) **and** the OAuth flow
(external connectors). The existing middleware order already does this:
`BearerAuthMiddleware` accepts a valid bearer and only falls through to OAuth when
none is presented.

Guard against bug #59467: when a *repo-originated* client supplies a static
bearer, it must be honored without the client falling into OAuth discovery. The
OpenCode path already forces this with `oauth:false` when an `Authorization`
header is present (L376–404); the Claude Code path honors the injected bearer in
`--mcp-config` today. This is purely a client-config concern for repo-originated
sessions; it does not change what the server advertises to external connectors.

### 5.4 Token model

Three coexisting credential types, all validated by the same HTTP server:
- **OAuth tokens** — claude.ai / Claude mobile (external connectors). Primary use
  case. Unchanged.
- **`armt_tmp_` HMAC tokens** — coding-agent jobs (workstream+job+expiry).
  Unchanged.
- **Personal/human tokens** — repo interactive devs. Static table entries with
  scopes and no job binding (§5.3) — no new format, no HMAC-payload change.

What changes is only the removal of the *fourth, illegitimate* case: no token at
all. Job TTL (12h) and revocation are out of scope for this consolidation.

## 6. Affected surface (from grep)

Code:
- `tools/mcp/manager/server.py` — **primary change**: remove stdio mode (refuse to
  run without HTTP transport + auth), delete the no-auth branch; touches
  `oauth.py`, `test_oauth.py`, `test_server.py`, `test_dispatch_capable*.py`,
  `tests/test_send_message_transport.py`.
- `flowtree/runtime/.../api/SecretsRequestHandler.java` — remove no-secret-admin
  branch (`isAdminToken()` no longer returns true on missing secret).
- `flowtree/runtime/.../jobs/McpConfigBuilder.java` — make a missing token a hard
  error for agent jobs instead of a silent ar-manager omission.
- `flowtree/runtime/.../api/FlowTreeApiEndpoint.java` — token minting call site
  (unchanged shape; confirm a job without a mintable token fails).
- `ClaudeCodeRunner.java` / `OpencodeConfigBuilder.java` — **no `--strict-mcp-config`**;
  OpenCode's existing `oauth:false` handling stays. Confirm the Claude Code path
  honors the injected static bearer without OAuth fallback.

Config:
- `.mcp.json` (root) — **remove the `ar-manager` stdio entry**.
- `.opencode/opencode.json`, `.claude/settings.json` — check for ar-manager refs.
- `flowtree/runtime/controller/docker-compose.yml` — ar-manager service env
  (shared secret mandatory).

Docs (update after code lands):
- `tools/mcp/README.md`, `tools/mcp/manager/README.md`, `tools/mcp/CLAUDE.md`,
  `tools/mcp/SECRETS.md`, `flowtree/runtime/docs/{architecture,coding-agent,
  claude-code-job,configuration,ci-integration}.md`, `flowtree/README.md`,
  root `CLAUDE.md` (the ar-manager rules block).

Tests touching the contract:
- `McpConfigBuilderTest`, `CodingAgentJobPushedToolsTest`,
  `CodingAgentJobDispatchTest`, `WorkstreamDispatchCapableTest` (Java);
  `test_oauth.py`, `test_server.py`, `tests/test_send_message_transport.py`
  (Python).

## 7. Open questions

Resolved by owner (2026-06-16):
- ~~SSH interactive auth~~ → **long-lived personal tokens** (static bearer, env-
  stored, never committed). Solves SSH with no browser/tunnel needed (§5.3).
- ~~Human token shape~~ → **reuse the static-token table** (no new format).
- ~~Strict-mode regression~~ → **not applicable**; we are not using
  `--strict-mcp-config`. The hatch is closed server-side (§5.1) instead.

Resolved: **OAuth stays.** claude.ai / Claude mobile are the dominant use case;
the OAuth flow on mcp.almostrealism.ai is preserved exactly. The server keeps
serving OAuth *and* bearer tokens on the same endpoint (existing middleware
already does both). Do not drop OAuth.

Resolved:
- **Personal-token minting UX** → admin **hand-edits** `manager-tokens.json` (add
  an entry with human scopes, no job binding). No CLI for now.
- **Scope = ar-manager only.** The other servers in this repo's `.mcp.json`
  (`ar-consultant`, `ar-test-runner`, `ar-build-validator`, `ar-profile-analyzer`,
  `ar-docs`, `ar-jmx`) stay local stdio with no auth and are **not** affected.
  They are local dev tools that carry no job/workspace/permission context and have
  no per-caller server-side state — the token-leakage problem is unique to
  `ar-manager`. We are not implicitly committing them to HTTP+auth.

Still open:
1. **Regression check on the no-auth removal**: confirm that deleting the
   "no tokens ⇒ permit everything" branch does not affect the OAuth path (it
   shouldn't — OAuth issues real tokens with scopes), only the genuinely
   tokenless path. Validate against `test_oauth.py`.
2. **Job token TTL & revocation**: keep 12h as-is (deferred).

## 8. Suggested phasing

- **Phase 0 — stand up interactive personal-token access (prerequisite).**
  - ✅ Recipe documented in `tools/mcp/manager/README.md` ("Interactive access
    from a repo checkout (personal tokens)"), reusing the existing
    `generate-token.sh` to mint the entry. No new tooling needed.
  - ⏳ **Owner verification (operational, not agent-doable):** mint a personal
    token via `generate-token.sh`, restart ar-manager, and confirm an interactive
    Claude Code session reaches mcp.almostrealism.ai with the personal bearer
    **both** locally **and** over SSH. This must pass *before* Phase 1 removes the
    stdio `.mcp.json` entry, or interactive users lose ar-manager.
- **Phase 1 — remove the stdio path for this repo.** ✅ **Done.** Deleted the
  `ar-manager` entry from root `.mcp.json` (§5.2); JSON re-validated, remaining
  six servers (ar-docs, ar-test-runner, ar-build-validator, ar-profile-analyzer,
  ar-consultant, ar-jmx) + onyx intact. Workers keep all other servers via merge.
  Interactive devs now reach ar-manager via the user-scoped HTTP entry (Phase 0).
- **Phase 2 — make the server token-only (closes the hatch).** ✅ **Done.**
  `server.py` `__main__` now `sys.exit(1)`s unless `MCP_TRANSPORT` is `http`/`sse`
  AND tokens are configured (removed the stdio `mcp.run()` and no-auth HTTP
  branches); `_require_scope` fails closed (no scopes ⇒ `PermissionError`, was
  permit-all); `SecretsRequestHandler.isAdminToken()` returns `false` when no
  shared secret (was `true`/admin). Verified: confirmed OAuth/claude.ai is
  unaffected because OAuth access tokens are entries in the same token table
  (`oauth.py:686–699,828`) so `BearerAuthMiddleware` sets scopes. Unscoped/
  superadmin workspace semantics (`_is_workspace_allowed` → True when no workspace
  scopes) left intact — that is the personal interactive token, not a hole. Tests:
  Python 417 + 176 pass, new `TestStartupGuard` (stdio refused, http-without-tokens
  refused) + renamed `test_no_scopes_denies_all`; Java `SecretsEndpointTest` 15/15;
  checkstyle 0 violations.
- **Phase 3 — fail jobs without a token.** ✅ **Done.** `McpConfigBuilder`
  (`arManagerEnabled()`) throws `IllegalStateException` when an ar-manager URL is
  configured but no token is present; both `buildMcpConfig` and
  `buildAllowedTools` use it. No URL configured ⇒ ar-manager simply absent (valid,
  no error). OAuth is untouched throughout.
- **Phase 4 — docs/tests.** Update the doc set in §6 and the affected tests;
  verify `test_oauth.py` still passes (claude.ai/mobile path intact).

Phase 0 gates the rest (don't break repo interactive use). Phases 1–2 are the
substance; 3–4 finish and clean up. At no point does the external OAuth connector
flow change.

## 9. Known remaining auth-less gaps (explicit follow-up, NOT closed here)

This project closes the `ar-manager` tokenless hole. It does **not** close every
auth-less path in the system, and that is deliberate — scoping the change to
`ar-manager` keeps it reviewable. A security review of this effort will (and
should) immediately observe that other ways to reach FlowTree-controlled state
*without the tool knowing who the caller is* still exist. This section records
them so that observation lands as **"known and deferred,"** not "missed."

The general shape of the remaining problem: a tool can talk to the **FlowTree
controller API**, the **memory store / memory API**, or the **task tracker**
without presenting a per-caller token that identifies the job/principal and
constrains permissions. Whenever identity/permission context is optional, we get
the same class of escape hatch this project is closing for `ar-manager`.

Known instances to address in the follow-up:

- **ar-consultant is partially duplicative of ar-manager.** It performs memory
  operations (`recall` / `remember`) and other lookups that overlap ar-manager's
  `memory_recall` / `memory_store`, and reaches backing services along its own
  path rather than through ar-manager's token-scoped one. ar-consultant is
  **kept** (not removed or gutted) — but the portion of it that touches
  shared/controlled state falls under this same "caller identity is optional"
  umbrella and must be brought under the token model in the follow-up. (Note:
  ar-consultant doc claims it does not bypass auth; the owner confirms it does so
  *partially* in practice — verify against the implementation when that follow-up
  begins, don't take the doc at face value.)
- **Direct FlowTree controller API access** on the private network (e.g.
  `SecretsRequestHandler`'s trust model) — see §3.2; this project removes the
  no-secret-admin bypass but does not audit every controller endpoint for
  mandatory caller identity.
- **Memory API / memory store** reachable without a caller-scoped token.
- **Task tracker API** reachable without a caller-scoped token.

Recommended follow-up: a dedicated effort that audits every path into the
controller / memory / tracker for "does the callee know who is calling, and is
that identity mandatory?" — applying the same "no optional identity" principle
established here. Track it separately from this plan; do not let it expand the
scope of the ar-manager consolidation.

## 10. Incidental: `server.py` is far over the file-length limit

`tools/mcp/manager/server.py` is ~6679 lines against a 1500-line soft limit (4×).
Phase 2 edits this file (removing stdio mode + the no-auth branch). The
auth-related pieces are natural seams for a split that should accompany or
immediately follow Phase 2 rather than being deferred indefinitely:

- token loading + `BearerAuthMiddleware` + `_validate_temp_token` (auth)
- the OAuth machinery (already partly in `oauth.py`)
- the tool functions, grouped by domain (workstream / github / tracker / memory /
  secrets), which dominate the line count

This is noted so the cleanup is planned, not silently skipped. It is **not** a
prerequisite for the auth consolidation and should not block Phases 0–1; fold the
split into Phase 2/4 where the file is already being touched.
