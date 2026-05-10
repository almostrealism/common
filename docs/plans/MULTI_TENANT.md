# Multi-Tenant FlowTree Controller

## Goal

Make the FlowTree controller multi-tenant, starting with support for multiple Slack workspaces
where different workstreams (and their associated GitHub org tokens) are visible to different
workspaces. This is Phase 1 of a broader effort to make the entire ar-manager system
multi-tenant.

---

## Current Architecture

### Single-Workspace Slack Integration

The controller connects to exactly one Slack workspace through three components:

**`FlowTreeController`** (`flowtree/src/main/java/io/flowtree/slack/FlowTreeController.java`):
- Holds a single `String botToken` (line 92) and `String appToken` (line 94)
- Constructs one `SlackNotifier(botToken)` (line 161) and one `SlackListener(notifier)` (line 162)
- At `start()` (line 383), creates one Bolt `App` with `AppConfig.builder().singleTeamBotToken(botToken)` (lines 424–427)
- Creates one `SocketModeApp(appToken, app)` (line 448) and calls `startAsync()`
- All three event handlers (`/flowtree`, `app_mention`, `message`) are registered on this single App (lines 476–527)

**`SlackTokens`** (`flowtree/src/main/java/io/flowtree/slack/SlackTokens.java`):
- A simple value object with `String botToken` and `String appToken`
- Resolved from: explicit file → `slack-tokens.json` → env vars `SLACK_BOT_TOKEN` / `SLACK_APP_TOKEN`
- Only one pair of tokens at a time

**`SlackNotifier`** (`flowtree/src/main/java/io/flowtree/slack/SlackNotifier.java`):
- Constructs a single `MethodsClient client = Slack.getInstance().methods(botToken)` (line 99)
- All `chatPostMessage`, `conversationsCreate`, `conversationsInvite` calls use this single client
- `createChannel(String name)` at line 148 calls `client.conversationsCreate(...)` — workspace-unaware

**`SlackListener`** (`flowtree/src/main/java/io/flowtree/slack/SlackListener.java`):
- Routes incoming events by channel ID: `Map<String, Workstream> channelToWorkstream` (line 95)
- All incoming slash commands and messages arrive via the single Bolt App — no workspace disambiguation

### Workstream Configuration

**`WorkstreamConfig`** (`flowtree/src/main/java/io/flowtree/slack/WorkstreamConfig.java`):
- Global YAML config has `githubOrgs: Map<String, GitHubOrgEntry>` (line 71) — org name → token
- Each `WorkstreamEntry` has a `githubOrg: String` field (line 113) for per-workstream org selection
- No workspace/tenant identifier on workstream entries or at the global config level

**`Workstream`** (`flowtree/src/main/java/io/flowtree/slack/Workstream.java`):
- Runtime representation of one workstream — has `channelId`, `channelName`, `githubOrg`, etc.
- No workspace identifier

### GitHub Token Flow

**`GitHubProxyHandler`** (`flowtree/src/main/java/io/flowtree/slack/GitHubProxyHandler.java`):
- Holds `Map<String, String> githubOrgTokens` (org name → token)
- Token priority: `?org=` query param → org extracted from URL path `/repos/{org}/{repo}/...` → single default
- Populated in `FlowTreeController.startApiEndpoint()` at lines 805–816 from `WorkstreamConfig.getGithubOrgs()`
- All workstreams share the same token map — no workspace isolation

---

## Phase 1 Plan: Multiple Slack Workspaces + Workstream Assignment

### Overview of Changes

The fundamental change is to move from a single `(botToken, appToken)` pair to a list of
workspace configurations, where each workspace has its own `(botToken, appToken)` pair and
owns a set of workstreams. The controller manages one Bolt App + SocketModeApp per workspace.

### 1. Configuration Schema Changes

#### New: Workspace Entry in YAML Config

Add a top-level `slackWorkspaces` section to `workstreams.yaml`:

```yaml
slackWorkspaces:
  - workspaceId: "T0123456789"
    name: "my-org"
    tokensFile: "/config/slack-tokens-my-org.json"   # OR inline:
    botToken: "xoxb-..."                              # one of tokensFile / inline tokens
    appToken: "xapp-..."
    defaultChannel: "C0987654321"
    channelOwnerUserId: "U0123456789"
    githubOrgs:
      my-org:
        token: "ghp_..."

workstreams:
  - workstreamId: "ws-abc"
    channelId: "C0123456789"
    channelName: "#project-agent"
    slackWorkspaceId: "T0123456789"   # NEW: ties this workstream to a workspace
    defaultBranch: "feature/work"
    githubOrg: "my-org"
```

**Backward compatibility:** If `slackWorkspaces` is absent, the controller falls back to
the existing `SlackTokens.resolve()` path (single workspace, current behavior unchanged).
If a workstream has no `slackWorkspaceId`, it is assigned to the first/only workspace.

#### New config classes to add to `WorkstreamConfig`:

```java
@JsonIgnoreProperties(ignoreUnknown = true)
public static class SlackWorkspaceEntry {
    private String workspaceId;      // Slack team ID (T...)
    private String name;             // Human-readable label
    private String tokensFile;       // Path to JSON tokens file
    private String botToken;         // Inline bot token (alternative to tokensFile)
    private String appToken;         // Inline app token
    private String defaultChannel;
    private String channelOwnerUserId;
    private Map<String, GitHubOrgEntry> githubOrgs;
    // getters/setters...
}
```

Add to `WorkstreamConfig`:
```java
private List<SlackWorkspaceEntry> slackWorkspaces = new ArrayList<>();
```

Add `slackWorkspaceId` field to `WorkstreamEntry` (and corresponding `Workstream` field).

**Files to modify:**
- `flowtree/src/main/java/io/flowtree/slack/WorkstreamConfig.java` — add `SlackWorkspaceEntry`, `slackWorkspaces` list, `slackWorkspaceId` on `WorkstreamEntry`
- `flowtree/src/main/java/io/flowtree/slack/Workstream.java` — add `slackWorkspaceId` field

---

### 2. SlackTokens Expansion

`SlackTokens` currently resolves a single pair. Extend it to support a list:

```java
public class SlackTokens {
    // existing single-token fields remain for backward compat
    private String botToken;
    private String appToken;

    // NEW: resolve from a workspace entry
    public static SlackTokens from(WorkstreamConfig.SlackWorkspaceEntry entry) throws IOException {
        if (entry.getTokensFile() != null) {
            return loadFromFile(new File(entry.getTokensFile()));
        }
        return new SlackTokens(entry.getBotToken(), entry.getAppToken());
    }
}
```

**Files to modify:**
- `flowtree/src/main/java/io/flowtree/slack/SlackTokens.java` — add `from(SlackWorkspaceEntry)` factory

---

### 3. Per-Workspace Slack Connection (FlowTreeController)

Replace the single `botToken`/`appToken`/`App`/`SocketModeApp` with a map of workspace connections.

#### New inner class `WorkspaceConnection`:

```java
/** Runtime state for one Slack workspace connection. */
private static class WorkspaceConnection {
    final String workspaceId;
    final String botToken;
    App app;
    SocketModeApp socketModeApp;
    String botUserId;
    SlackNotifier notifier;
}
```

#### In `FlowTreeController`:

Remove:
```java
private final String botToken;
private final String appToken;
private final SlackNotifier notifier;
private App app;
private SocketModeApp socketModeApp;
private String botUserId;
```

Add:
```java
/** Per-workspace Slack connections, keyed by workspaceId. */
private final Map<String, WorkspaceConnection> workspaceConnections = new LinkedHashMap<>();
/** Fallback single-workspace connection (backward compat when no slackWorkspaces in config). */
private WorkspaceConnection defaultConnection;
```

The `SlackListener` is shared across workspaces (it routes by channel ID, not workspace ID).
The listener needs to know which notifier to call per workstream, so it uses the workstream's
`slackWorkspaceId` to look up the correct `WorkspaceConnection.notifier`.

#### `loadConfig()` changes:

After parsing `WorkstreamConfig`, if `slackWorkspaces` is non-empty:
1. For each `SlackWorkspaceEntry`, create a `WorkspaceConnection` with its tokens
2. Build the per-workspace notifier: `new SlackNotifier(entry.getBotToken())`
3. Register workstreams into the correct notifier based on `slackWorkspaceId`

If `slackWorkspaces` is empty, fall back to current behavior (single token pair).

#### `start()` changes:

Instead of one `App` + one `SocketModeApp`, iterate `workspaceConnections`:

```java
for (WorkspaceConnection conn : workspaceConnections.values()) {
    AppConfig appConfig = AppConfig.builder()
        .singleTeamBotToken(conn.botToken)
        .build();
    conn.app = new App(appConfig);
    registerEventHandlers(conn.app, conn.workspaceId);
    conn.socketModeApp = new SocketModeApp(conn.appToken, conn.app);
    conn.socketModeApp.startAsync();
}
```

#### `registerEventHandlers()` changes:

Add `String workspaceId` parameter. The event handler looks up the workspace in incoming
events via `req.getPayload().getTeamId()` (available on slash command payloads) and routes
accordingly. For `AppMentionEvent` and `MessageEvent`, the workspace ID is available as
`event.getTeamId()`.

The handler signature becomes:
```java
private void registerEventHandlers(App app, String workspaceId)
```

Events are routed to `listener.handleMessage(channelId, userId, text, messageTs, threadTs, workspaceId)`
where the workspaceId disambiguates which workspace sent the event.

**Files to modify:**
- `flowtree/src/main/java/io/flowtree/slack/FlowTreeController.java` — major refactor of fields and startup

---

### 4. SlackListener: Workspace-Aware Routing

`SlackListener` currently routes only by channel ID. With multiple workspaces, the same
channel ID could theoretically exist in two workspaces (Slack channel IDs are workspace-scoped
in practice, but the model should be explicit).

#### Changes:

1. Add `workspaceId` parameter to `handleMessage()` and `handleSlashCommand()`:

```java
public void handleMessage(String channelId, String userId, String text,
                          String messageTs, String threadTs, String workspaceId)

public void handleSlashCommand(String text, String channelId, String channelName,
                               SlashCommandResponder responder, String workspaceId)
```

2. The `channelToWorkstream` map key becomes `workspaceId + ":" + channelId` to be unique:

```java
private final Map<String, Workstream> channelToWorkstream = new HashMap<>();

// key:
private static String channelKey(String workspaceId, String channelId) {
    return workspaceId == null ? channelId : workspaceId + ":" + channelId;
}
```

3. Slash command filtering: commands in workspace A only see workstreams with `slackWorkspaceId == A`
   in handlers like `handleSlashActiveCommand()`, `handleSlashStatusCommand()`, etc.

4. The `/flowtree setup` command creates a channel in the requesting workspace's notifier —
   `notifier.createChannel(name)` must be called on the correct workspace's notifier.
   Pass the workspace's notifier into the slash command handlers (or look it up from a
   `Map<String, SlackNotifier> notifiersByWorkspace` held by the listener).

**Files to modify:**
- `flowtree/src/main/java/io/flowtree/slack/SlackListener.java` — channel key, method signatures, per-workspace notifier dispatch

---

### 5. SlackNotifier: Per-Workspace Client

`SlackNotifier` currently has a single `MethodsClient client`. With multi-workspace, two
approaches are possible:

**Option A (Preferred): One notifier per workspace.** Each `WorkspaceConnection` owns
its own `SlackNotifier` instance. The `SlackListener` selects the right notifier by
looking up the workstream's `slackWorkspaceId`.

This requires minimal changes to `SlackNotifier` itself — it already encapsulates one
`MethodsClient`. The only change: when `SlackListener` routes a message or job event,
it resolves `workstream.getSlackWorkspaceId()` → `workspaceConnections.get(id).notifier`
and calls methods on that notifier.

**Option B:** A single multi-workspace notifier with an internal `Map<String, MethodsClient>`.
More complex, not recommended.

**Proceed with Option A.**

The `FlowTreeApiEndpoint` holds a reference to a single `SlackNotifier`. With multi-workspace,
it needs to resolve the notifier per workstream ID. Two sub-options:
- Pass a `Function<String, SlackNotifier>` (workstreamId → notifier) to the endpoint
- Keep the existing `SlackNotifier` parameter but have the endpoint delegate to a
  `notifierResolver` lambda

For minimal invasiveness: add an overload to `FlowTreeApiEndpoint`:
```java
public void setNotifierResolver(Function<String, SlackNotifier> resolver)
```

**Files to modify:**
- `flowtree/src/main/java/io/flowtree/slack/FlowTreeApiEndpoint.java` — notifier resolver
- `flowtree/src/main/java/io/flowtree/slack/SlackNotifier.java` — no changes needed for Option A

---

### 6. GitHub Token Isolation

Currently `FlowTreeController.startApiEndpoint()` populates `apiEndpoint.setGithubOrgTokens(orgTokens)`
from the global `config.getGithubOrgs()` map. With multi-workspace, each workspace has its own
`githubOrgs` section.

The `GitHubProxyHandler` already resolves tokens by org name. The needed change:

1. Merge all per-workspace `githubOrgs` maps into the global `orgTokens` map, but **namespace
   by workspace prefix** when org names collide across workspaces:

```
// Merge: workspace "my-org" has org "almostrealism"
// workspace "other-org" also has org "almostrealism"
// Keys: "my-org::almostrealism", "other-org::almostrealism"
// Fallback key: "almostrealism" (for backward compat with single-workspace)
```

2. Add `workspaceId` context to proxy requests: when an agent makes a GitHub proxy request,
   it includes the workstream ID in the request header `X-Workstream-Id`. The proxy handler
   uses this to resolve the workspace → restrict to that workspace's token set.

3. Alternatively (simpler for Phase 1): org names are globally unique across workspaces
   (enforce this at config load time). Then the merged map works as-is with no namespacing.

**Recommendation for Phase 1:** Enforce globally unique org names and merge maps as today.
Add workspace-scoped token resolution in Phase 2.

**Files to modify:**
- `flowtree/src/main/java/io/flowtree/slack/FlowTreeController.java` — merge all workspace githubOrgs
- `flowtree/src/main/java/io/flowtree/slack/GitHubProxyHandler.java` — add `X-Workstream-Id` header handling (Phase 2)

---

### 7. ar-manager MCP Multi-Tenancy (Future Direction — Out of Scope for Phase 1)

This section outlines how workspace isolation extends to the ar-manager MCP server for
future implementation.

**Current state:** `ar-manager` (`tools/mcp/manager/server.py`) runs as a single service.
All MCP clients connect with a shared HMAC secret derived from `AR_MANAGER_SHARED_SECRET`.
Workstream visibility is not restricted by client identity.

**Future goal:**
- Each Slack workspace gets its own `AR_MANAGER_SHARED_SECRET` (or sub-secret derived from it)
- MCP client auth tokens encode the workspace ID
- `workstream_list`, `workstream_submit_task`, etc. filter results to the caller's workspace
- Memory namespacing: memories stored by agents in workspace A are not visible to workspace B
- The controller generates per-job HMAC tokens that include the workspace scope in the payload

**Suggested mechanism:**
```
token payload: { job_id, workspace_id, workstream_id, expires_at }
HMAC key: derived_key = HMAC(master_secret, workspace_id)
```

This allows the ar-manager to verify the token without needing a per-workspace secret —
it rederives the key from the workspace ID embedded in the payload.

**Files that will need modification (Phase 2+):**
- `tools/mcp/manager/server.py` — workspace-scoped filtering on all tools
- `flowtree/src/main/java/io/flowtree/slack/FlowTreeApiEndpoint.java` — embed workspace in job tokens
- ar-memory service — namespace memory keys by workspace ID

---

## Migration Path

### Backward Compatibility

The migration is designed to be fully backward compatible:

1. **No `slackWorkspaces` in YAML:** The controller behaves exactly as today. It falls back
   to `SlackTokens.resolve()` for the single bot/app token pair. All existing deployments
   continue working without any config changes.

2. **Single entry in `slackWorkspaces`:** Equivalent to the old single-workspace setup.
   Workstreams without a `slackWorkspaceId` are assigned to the first (only) workspace.

3. **Multiple entries:** New multi-workspace behavior. Workstreams with no `slackWorkspaceId`
   are assigned to the first workspace in the list (a sensible default).

### Configuration Migration Steps

For an existing deployment migrating to multi-workspace:

1. Add `slackWorkspaces` section to `workstreams.yaml` with a single entry wrapping the
   existing `slack-tokens.json` credentials:
   ```yaml
   slackWorkspaces:
     - workspaceId: "T0123456789"
       name: "primary"
       tokensFile: "/config/slack-tokens.json"
       githubOrgs:
         my-org:
           token: "ghp_..."  # move from top-level githubOrgs
   ```
2. Keep the top-level `githubOrgs` section as a fallback during transition.
3. Remove `slackWorkspaceId` is optional — workstreams without it fall back to the first workspace.
4. Test with one workspace, then add additional workspaces.

### Breaking Changes

None for existing single-workspace deployments. The `handleMessage()` and `handleSlashCommand()`
method signatures on `SlackListener` change (new `workspaceId` parameter), but these are
internal APIs — the only caller is `FlowTreeController.registerEventHandlers()`.

---

## Implementation Order

### Phase 1a: Config Schema (No Behavior Change)

1. Add `SlackWorkspaceEntry` static class to `WorkstreamConfig`
2. Add `slackWorkspaces` list to `WorkstreamConfig`
3. Add `slackWorkspaceId` to `WorkstreamEntry` and `Workstream`
4. Add `SlackTokens.from(SlackWorkspaceEntry)` factory method
5. Confirm `WorkstreamConfig` serialization/deserialization round-trips correctly

**Tests:** Update `WorkstreamConfigTest` to cover the new fields. Confirm existing YAML
without `slackWorkspaces` still parses correctly.

### Phase 1b: Multi-Workspace Controller Startup

6. Add `WorkspaceConnection` inner class to `FlowTreeController`
7. Refactor `FlowTreeController` fields: replace `botToken`/`appToken`/`app`/`socketModeApp`
   with `Map<String, WorkspaceConnection> workspaceConnections`
8. Update `loadConfig()` to populate `workspaceConnections` from `slackWorkspaces`; fall back
   to single-token behavior when list is empty
9. Update `start()` to start one `App` + `SocketModeApp` per workspace connection
10. Update `registerEventHandlers()` to accept `App` and `workspaceId` parameters
11. Pass `workspaceId` through to `listener.handleMessage()` and `listener.handleSlashCommand()`

**Tests:** Add `FlowTreeControllerMultiWorkspaceTest` that creates two simulated workspace
connections and verifies events route to the correct handlers.

### Phase 1c: Listener and Notifier Routing

12. Update `SlackListener.channelToWorkstream` key to `workspaceId:channelId`
13. Update `handleMessage()` and `handleSlashCommand()` to accept and use `workspaceId`
14. Add `Map<String, SlackNotifier> notifiersByWorkspace` to `SlackListener`
15. Slash commands: filter workstream lists by `workspaceId`
16. Channel creation (`/flowtree setup`): use workspace-specific notifier

**Tests:** Extend `SlackIntegrationTest` to cover two-workspace scenarios.

### Phase 1d: GitHub Token Scoping

17. In `FlowTreeController.startApiEndpoint()`, merge githubOrgs from all workspaces
18. Validate globally unique org names at config load; warn on collision
19. Update `GitHubTokenValidator` to validate tokens from per-workspace `githubOrgs` sections

**Tests:** Extend `GitHubTokenValidatorTest` to cover per-workspace token maps.

---

## Risks and Considerations

| Risk | Mitigation |
|------|-----------|
| Slack org IDs not present in incoming events | Verify `getTeamId()` is available on `AppMentionEvent`, `MessageEvent`, and slash command payloads via Bolt SDK docs before implementation |
| Multiple SocketModeApp instances resource usage | Each connection uses one WebSocket; acceptable for tens of workspaces. For hundreds, consider a multiplexing approach. |
| Slash command name `/flowtree` registered once per App | Each Bolt App registers its own `/flowtree` handler — no conflict since each App is for a different workspace |
| `singleTeamBotToken` vs org-level tokens | `AppConfig.builder().singleTeamBotToken()` is the correct builder path for workspace-specific tokens; no changes needed here |
| Per-workspace `channelOwnerUserId` | Currently a global setting. With per-workspace config, it becomes per-workspace — the `SlackNotifier` may need to accept it at construction rather than via setter |
| `reloadConfig()` in `FlowTreeController` | Must rebuild workspace connections on reload; currently just re-registers workstreams |
| `JobStatsStore` | Currently a single SQLite file; stays workspace-unaware in Phase 1 (job IDs are globally unique UUIDs) |

---

## File Summary

| File | Change Type | Phase |
|------|-------------|-------|
| `flowtree/src/main/java/io/flowtree/slack/WorkstreamConfig.java` | Add `SlackWorkspaceEntry`, `slackWorkspaces`, `slackWorkspaceId` on entry | 1a |
| `flowtree/src/main/java/io/flowtree/slack/Workstream.java` | Add `slackWorkspaceId` field | 1a |
| `flowtree/src/main/java/io/flowtree/slack/SlackTokens.java` | Add `from(SlackWorkspaceEntry)` factory | 1a |
| `flowtree/src/main/java/io/flowtree/slack/FlowTreeController.java` | Multi-workspace startup, `WorkspaceConnection` inner class | 1b |
| `flowtree/src/main/java/io/flowtree/slack/SlackListener.java` | Workspace-aware channel key, `workspaceId` parameters | 1c |
| `flowtree/src/main/java/io/flowtree/slack/FlowTreeApiEndpoint.java` | Notifier resolver for per-workspace dispatch | 1c |
| `flowtree/src/main/java/io/flowtree/slack/GitHubProxyHandler.java` | Workspace-scoped token resolution (Phase 2) | 1d |
| `flowtree/src/main/java/io/flowtree/slack/GitHubTokenValidator.java` | Validate per-workspace githubOrgs sections | 1d |
| `tools/mcp/manager/server.py` | Workspace-scoped filtering (future) | 2+ |
