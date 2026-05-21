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

| Recipe | primary | dedup | placement | enforce-changes | maven-deps | post-completion | commit-message | git-tampering-restart | When to use |
|--------|---------|-------|-----------|-----------------|------------|-----------------|----------------|------------------------|-------------|
| **All-Claude** (default) | claude | claude | claude | claude | claude | claude | claude | claude | Reproduces pre-branch behavior. Use when correctness matters more than cost and you have not yet vetted opencode on the workstream's repos. |
| **Mixed-review** | opencode | claude | claude | opencode | claude | opencode | claude | claude | Run primary on the cheaper local backend; keep Claude on phases that have proven, load-tested prompts (dedup, placement, maven-dep, commit-message). Good first opencode rollout. |
| **All-opencode** | opencode | opencode | opencode | opencode | opencode | opencode | opencode | opencode | Workstreams cost-sensitive enough to accept opencode's best-effort cost reporting and the slight quality drop on dedup/placement audits. The simplest way to get the lowest possible bill. |

### As a `workstream_submit_task` payload

**All-Claude** — omit `runners` and `default_runner`; the controller falls
through to `"claude"` by default.

**Mixed-review:**

```json
{
  "default_runner": "claude",
  "runners": {
    "primary": "opencode",
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

When the same recipe applies to many workstreams in the same Slack
workspace, hoist it onto the `slackWorkspaces[]` entry instead of repeating
it on every workstream:

```yaml
slackWorkspaces:
  - workspaceId: "T0123456789"
    botToken: "xoxb-..."
    appToken: "xapp-..."
    defaultRunner: claude
    runners:
      commit-message: opencode
      organizational-placement: opencode
```

Every workstream whose `slackWorkspaceId` matches this entry inherits the
defaults. Workstream-level config still wins — if a workstream sets its
own `defaultRunner`, the workspace's per-phase entries are ignored for
that workstream (per [PHASES.md](../architecture/PHASES.md)). There is
currently no MCP tool to set workspace-level runner config; edit
`workstreams.yaml` and reload the controller. A future
`workspace_update_config` tool would close this ergonomic gap.

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

## Capability-mismatch validation

The `AgentCapabilities` record declares per-runner facts (does it report
cost, does it support `--max-budget-usd`, etc.). Validation that warns
when a workstream selects a runner without a capability the phase
semantically needs — e.g., a workstream that cares about cost accounting
routing primary to a `reportsCost=false` runner — is intentionally
deferred. Operators should review their phase/runner mix manually against
the recipes above and the capabilities table in [OPENCODE.md](OPENCODE.md)
before deploying.
