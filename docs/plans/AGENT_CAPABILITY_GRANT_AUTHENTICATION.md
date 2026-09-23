# Authenticating agent capability grants at the factory boundary

Recorded during review of the per-job agent permission-prompt grant. Not an
immediate target — this documents a known weakness in the trust boundary so the
next person to touch it starts from the real picture rather than rediscovering
it.

---

## The current boundary

`CodingAgentJob` carries two capability flags that widen what an agent session
may do:

- `dispatchCapable` — grants the dispatch/orchestration MCP tools
  (`workstream_register`, `workstream_update_config`).
- `bypassAgentPermissionPrompts` — grants `--permission-mode bypassPermissions`,
  which lets the session write paths the agent runtime otherwise reserves for a
  human to approve: its own configuration and hooks, environment files,
  credentials.

Both are decided by the controller from workstream policy, in one place
(`Workstream.applyCapabilities`), and both travel to the agent node through
`CodingAgentJobFactory`'s property map.

## The weakness

The grant is authenticated by nothing. `CodingAgentJobFactory` is public, its
setters are public, `CodingAgentClient` accepts a caller-supplied factory, and
the agent node trusts the properties it receives. A client that can submit a job
at all can therefore set either flag directly and skip
`Workstream.applyCapabilities` entirely.

This is not new to the permission-prompt grant — `dispatchCapable` has had the
same shape since it was introduced, and the node has always trusted the factory
it is handed. What the newer flag changes is the consequence: dispatch tools let
a session orchestrate, while the permission bypass lets it edit the guardrails
it is running under, including the hooks that would otherwise stop it.

So the honest statement of today's model is: **the submission channel is the
trust boundary, and everything downstream of it believes what it is told.**

## What better would look like

The repository already has a worked example of a controller-authenticated grant:
`sensitiveFileBypassSignature`, an HMAC-SHA256 signature the controller produces
with `AR_AGENT_BYPASS_SECRET`, verified in CI by
`tools/ci/agent-protection/verify-sensitive-bypass.sh`. An agent cannot forge it
because the secret is not in its environment.

The same shape would fit here: the controller signs `(taskId, capability)` when
it grants a capability from workstream policy, and the agent node — or the
runner, before it composes the command line — refuses a flag whose signature is
absent or does not verify. That turns "the flag is set" into "the controller set
it", which is the property the flag is supposed to have.

Open questions before doing it:

- Where to verify. Verifying in `ClaudeCodeRunner` catches the most paths but
  puts crypto in the runner; verifying in `CodingAgentJobConfigurer` keeps it on
  the controller side of the job but leaves a directly-constructed job
  unchecked.
- Whether to cover `dispatchCapable` in the same change. Leaving it unsigned
  while signing the newer flag would suggest the older one is verified when it
  is not.
- What a node should do when the secret is absent. Refusing every grant is the
  safe direction and matches how the CI verifier behaves, but it would silently
  disable the capability on any deployment that has not configured the secret.

## Related gap: the grant only restricts Claude

`ClaudeCodeRunner` emits `--permission-mode bypassPermissions` only when the
job carries the grant. `OpencodeRunner` emits `--dangerously-skip-permissions`
unconditionally and does not read the flag, so a job dispatched to opencode is
not restricted by the policy at all.

That is not an oversight in the runner so much as an asymmetry in the two
CLIs. For Claude the flag is purely the sensitive-path bypass — an ungranted
session still runs headless, it just cannot write `.claude/`, environment
files or credentials. opencode's flag is what makes a session unattended in
the first place: without it every tool call waits on a prompt, which is why
`OpencodeRunnerTest` pins its presence as required "for headless runs", and
why `OpencodeConfigBuilder` separately grants `external_directory` for the
paths the flag does not cover. Gating it on the capability would not restrict
opencode sessions, it would stop them running.

Restricting opencode to the same degree therefore means expressing the policy
where opencode expresses policy — the `permission` object
`OpencodeConfigBuilder` already writes — rather than by withholding a command
line flag. Denying writes to the reserved paths there, and granting them only
when the job carries the capability, is the shape that would work. Until that
exists, the documented default-off policy should be read as scoped to the
Claude runner, which is what `AgentRunRequest#isBypassPermissionPrompts()` now
says.

## Related gap: dependent repositories run unlocked

`WorkspaceLock` covers one path — the primary working directory. Dependent
repositories are cloned beside it by `GitRepositorySetup.prepareDependentRepos()`
and every git operation on them runs without a lock of their own. Two jobs can
therefore share a path: one job's dependent repository is another job's primary.

The completion snapshot no longer reads them for this reason (it inspects only
the locked primary tree, and accounts for dependents from `GitCommitHandler`'s
own record instead), so the reporting path is sound. The underlying exposure is
not: concurrent jobs can still stage and commit in the same dependent tree.

Closing it is not just a matter of taking more locks. It needs a consistent
global acquisition order, and ordering alone is insufficient while dependent
paths are resolved *after* the primary lock is taken — job A can hold X and want
Y while job B holds Y and wants X. The shape that works is to resolve every
participating path from job configuration before acquiring anything, then take
all of them in a deterministic order, releasing all on exit. That is a change to
the job startup sequence with deadlock stakes and deserves its own pass.

## Why it is not being done now

The exposure requires a client that can already submit jobs to the controller,
which is the same level of access needed to submit a job that does anything
else. Signing the grant is worth doing, but it is a change to the submission
trust model rather than a fix to the capability, and it should be designed as
one — including the `dispatchCapable` case — rather than bolted onto the flag
that happened to surface it.
