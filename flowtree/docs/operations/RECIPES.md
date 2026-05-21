# Phase-Runner Recipes

Recommended starting configurations for the per-phase runner map. Each
recipe is a complete set of phase → runner assignments that can be passed
to `workstream_submit_task` (as `runners` + optional `default_runner`) or
set as workstream defaults in `WorkstreamConfig`.

For the precedence rules behind per-phase selection, see
[../architecture/PHASES.md](../architecture/PHASES.md). For
opencode-specific operator setup, see [OPENCODE.md](OPENCODE.md).

---

## The recipes

| Recipe | primary | review | dedup | placement | enforce-changes | maven-deps | post-completion | commit-message | git-tampering-restart | When to use |
|--------|---------|--------|-------|-----------|-----------------|------------|-----------------|----------------|------------------------|-------------|
| **All-Claude** (default) | claude | claude | claude | claude | claude | claude | claude | claude | claude | Reproduces pre-branch behavior. Use when correctness matters more than cost and you have not yet vetted opencode on the workstream's repos. |
| **Cheap-second-pass** (recommended) | claude | opencode | claude | claude | claude | claude | claude | claude | claude | Keep the expensive primary work on Claude but route the review pass to opencode/local. Cheap insurance against simple mistakes that doesn't risk the primary diff. |
| **Mixed-review** | opencode | opencode | claude | claude | opencode | claude | opencode | claude | claude | Run primary on the cheaper local backend; keep Claude on phases that have proven, load-tested prompts (dedup, placement, maven-dep, commit-message). Good first opencode rollout. |
| **All-opencode** | opencode | opencode | opencode | opencode | opencode | opencode | opencode | opencode | opencode | Workstreams cost-sensitive enough to accept opencode's best-effort cost reporting and the slight quality drop on dedup/placement audits. The simplest way to get the lowest possible bill. |

### As a `workstream_submit_task` payload

**All-Claude** — omit `runners` and `default_runner`; the controller falls
through to `"claude"` by default.

**Cheap-second-pass** (the recommended starting point — primary stays on
Claude, only the review pass is delegated):

```json
{
  "default_runner": "claude",
  "runners": {
    "review": "opencode"
  }
}
```

**Mixed-review:**

```json
{
  "default_runner": "claude",
  "runners": {
    "primary": "opencode",
    "review": "opencode",
    "enforce-changes": "opencode",
    "post-completion": "opencode"
  }
}
```

**All-opencode:**

```json
{
  "default_runner": "opencode"
}
```

### As workstream defaults

```yaml
workstreams:
  - workstreamId: ws-frugal
    defaultRunner: opencode
    runners:
      deduplication: claude
      commit-message: claude
```

Per-job overrides still apply; this just changes the workstream-level
fallback.

### As workspace defaults

When the same recipe applies to many workstreams in the same workspace,
hoist it onto the `workspaces[]` entry instead of repeating it on every
workstream:

```yaml
workspaces:
  - id: "almostrealism"
    slackTeamId: "T0123456789"     # optional Slack binding
    botToken: "xoxb-..."
    appToken: "xapp-..."
    defaultRunner: claude
    runners:
      commit-message: opencode
      organizational-placement: opencode
```

Every workstream whose `workspaceId` matches this entry inherits the
defaults. Workstream-level config still wins — if a workstream sets its
own `defaultRunner`, the workspace's per-phase entries are ignored for
that workstream (per [PHASES.md](../architecture/PHASES.md)).

The legacy `slackWorkspaces:` top-level key is still accepted; each
legacy entry's `workspaceId` doubles as both its workspace `id` and its
`slackTeamId` on load.

The preferred path to set these defaults is the `workspace_update_config`
MCP tool — discover the workspace ID via the `workspaceId` field on
each `workstream_list` entry (the legacy `slackWorkspaceId` field is
still emitted for backward compatibility):

```python
workspace_update_config(
    workspace_id="almostrealism",
    default_runner="claude",
    runners='{"commit-message":"opencode","organizational-placement":"opencode"}',
)
```

To migrate from the initial Slack-team-ID-as-ID form to a friendlier
operator-chosen name, pass `new_id`:

```python
workspace_update_config(
    workspace_id="T0123456789",
    new_id="almostrealism",
)
```

The tool covers `default_runner`, `runners`, `name`, `default_channel`,
`new_id`, and `slack_team_id`. Credentials (`tokensFile`, `botToken`,
`appToken`) and ACL fields (`githubOrgs`, `channelOwnerUserId`) remain
YAML-only — edit `workstreams.yaml` and reload the controller for
those.

---

## Picking a recipe

Three questions, in order:

1. **Does the workstream care about per-job cost figures being accurate?**
   If yes, route at least the primary phase to a runner with
   `reportsCost = true` (currently: `claude` only). `runnerStats` and the
   top-level `unmeasuredCostRunners` field on the completion event tell
   downstream consumers which phases contributed to the reported cost.
   See [../architecture/PHASES.md#telemetry](../architecture/PHASES.md#telemetry).

2. **How sensitive is the workstream to dedup / organizational-placement
   regressions?** These two phases historically rely on Claude's
   recall-from-context behavior and are the most likely to regress on a
   weaker model. Keep them on `claude` if the workstream's reviewer
   bandwidth is low.

3. **Is the workstream's repo big enough to exercise long-context
   reasoning in the primary phase?** Local 30B-class models start to
   struggle around 20-30k tokens of effective prompt. If your primary
   prompts routinely exceed that, keep primary on `claude` and put
   opencode on the audit phases (Mixed-review).

If none of those constraints bind, **All-opencode** is the right starting
point.

---

## Review-pass follow-up: finding deferred items

The review phase deliberately biases toward filing memories instead of
making edits. Each deferred item is stored with the tag
`review-followup` (plus `workstream:<id>` when the reviewer can resolve
the current workstream). To find what has been flagged:

```python
memory_recall(
    query="reviewer notes",
    namespace="default",
    tags=["review-followup"],
)
```

Or scope to a single workstream via `workstream_context` filtered by
namespace and branch. The corresponding in-code breadcrumb is the
`TODO(review):` comment the reviewer leaves at the relevant location —
those survive in the PR review and are searchable across the repo.

A common pattern is to have the next primary-phase session on the same
workstream call `memory_recall(tags=["review-followup"])` near the start
of its work and incorporate the deferred items into its plan.

### Disabling the review phase

Pass `review_enabled=false` to `workstream_submit_task` (per-job),
set `reviewEnabled: false` in workstream or workspace config, or call
`setReviewEnabled(false)` on the Java side. There is no `"none"` sentinel
in the runners map — disabling is always done via the boolean, matching
the convention established by `enforceOrganizationalPlacement`.

---

## Capability-mismatch validation

The `AgentCapabilities` record declares per-runner facts (does it report
cost, does it support `--max-budget-usd`, etc.). Validation that warns
when a workstream selects a runner without a capability the phase
semantically needs — e.g., a workstream that cares about cost accounting
routing primary to a `reportsCost=false` runner — is intentionally
deferred. Operators should review their phase/runner mix manually against
the recipes above and the capabilities table in [OPENCODE.md](OPENCODE.md)
before deploying.
