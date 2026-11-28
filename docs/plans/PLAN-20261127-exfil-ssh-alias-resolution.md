---
status: proposed
created: 2026-11-27
closed:
tracker: [7f7b02cb-0001-460e-82d0-35e6cdba4486]
---

# Exfiltration guard: resolve SSH config aliases before checking the host allowlist

**Tracker task:** `7f7b02cb-0001-460e-82d0-35e6cdba4486` — "Exfiltration guard: resolve SSH config aliases before checking the host allowlist"
**Release:** the in-development release named by the root `pom.xml` `<version>` (do not pin a number here — it changes every release).

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

1. **`.claude/hooks/lib/exfil_bash_network.py`** — in `_check_ssh_family` (now
   called with the command's captured `env`), resolve each ssh-family destination
   host through `ssh -G` before the existing `_require_lab` allowlist match,
   considering the resolved `hostname`, `proxyjump` and `proxycommand`, subject to
   the env-override refusal, the pre-`ssh -G` config scan (no resolution on configs
   that can execute commands), the pinned `-o`/`-F` forwarding contract, and the
   bounded ProxyJump depth/cycle policy. Add `KnownHostsCommand` to the refused
   command-executing `-o` regex. On any resolution failure or unsafe config, fall
   back to the current literal match.
2. **A new sibling test module** `.claude/hooks/lib/test_exfiltration_guard_ssh_alias.py`,
   built on the shared `GuardFixture` imported from
   `test_exfiltration_guard_check.py`, covering every case in Success criteria:
   alias-to-allowlisted (allowed), alias-to-non-allowlisted (blocked), ProxyJump to
   a non-allowlisted host (blocked), ProxyCommand alias (blocked), `-o HostName=`
   override in both attached and separate spellings and `-F`/`-Ffile` (blocked),
   `HOME=`/`PATH=` environment overrides (blocked), `Match exec` config rejection
   with a sentinel proving no execution, ProxyJump cycle termination, and literal
   fallback on resolution failure — each for `ssh`, `scp` and `rsync`-over-ssh.
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
def _resolve_ssh_destination(token, options, env, prog, ctx) -> list[str]:
    """Hosts the allowlist must clear for this destination: the resolved
    HostName plus any ProxyJump hop, or [token] unchanged if ssh -G cannot
    be run safely or cannot resolve it."""
```

Behaviour, in order:

1. **Refuse a config/binary-selecting command-scoped environment override
   (thread #2).** `_check_ssh_family` is now called with the command's captured
   leading `VAR=value` assignments (`env`, see "Command-scoped environment"
   below). Before any resolution, if `env` assigns `HOME` or `PATH`, raise a
   block. These are the two variables that change *which* `ssh` binary runs and
   *which* `~/.ssh/config` is read, so running `ssh -G` under the hook's own
   environment would resolve a different configuration than the real command
   (the `HOME=/tmp/evil ssh amd-halo` case), and running `ssh -G` under the
   *overridden* environment would let an attacker-chosen `PATH`/`HOME` pick the
   binary and the config whose `Match exec`/`ProxyCommand` then executes. Fail
   closed: refuse rather than resolve. (This mirrors the existing refusal of
   rsync transport env.)
2. **Locate ssh** with `shutil.which("ssh")`. If absent, return `[token]`
   (literal fallback).
3. **Refuse to run `ssh -G` on a config that can execute a command during
   resolution (thread #3 — the `Match exec` mitigation).** `ssh -G` evaluates
   `Match exec "<command>"` and runs `ProxyCommand`/`LocalCommand`/
   `KnownHostsCommand` references while it builds the effective config, and
   there is no `ssh -G` flag that evaluates `Match` without its `exec`
   predicate. So before invoking `ssh -G`, statically scan the effective config
   text — the per-user `~/.ssh/config` (located via the current `HOME`, which
   step 1 has guaranteed is not overridden), the system `/etc/ssh/ssh_config`,
   any `-F <file>` named on the command line, and every file reachable through
   their `Include` directives (follow includes to a bounded depth, expanding
   `~` and globs) — for any command-executing directive: `Match exec`,
   `ProxyCommand` (not `none`), `LocalCommand`, `PermitLocalCommand yes` and
   `KnownHostsCommand`. If any is present, or if the config chain cannot be
   fully read/followed, do **not** run `ssh -G`; return `[token]` (literal
   fallback, which fails closed — a cosmetic alias not in the allowlist is
   blocked, exactly as today). The scan executes nothing; it only reads text.
   `ssh -G` is therefore only ever invoked on a config proven free of
   command-execution directives, so resolution never runs an
   assistant-controlled command. This is the decisive mitigation the task
   requires: **no `ssh -G` on configs that can execute commands.**
4. **Run `ssh -G <forwarded-options> <alias-without-user@-or-:port>`** with a
   short timeout and captured output, mirroring the `Git.run` error handling:
   any `OSError`/`SubprocessError`/timeout, or a non-zero exit, returns
   `[token]` (literal fallback). The forwarded options are exactly the set
   pinned under "Faithful resolution" below (`-F` and the host/config-selecting
   `-o` values); the routing and command-executing `-o` forms are already
   refused before resolution, so they never reach this call.
5. **Parse** the `key value` lines of the output (ssh prints keys lowercased,
   one per line). Read `hostname`, `proxyjump`, `proxycommand`.
6. **ProxyCommand from the resolved config.** If `proxycommand` is present and
   not `none`, the session is routed through an arbitrary command whose
   destination cannot be verified — raise a block, exactly as the existing
   command-line `-o ProxyCommand` check does. (In practice step 3 has already
   taken the literal-fallback path for such a config, but this is a belt-and-
   braces check on the resolved output.)
7. **ProxyJump hops, with an explicit depth/cycle policy (thread #4).** If
   `proxyjump` is present and not `none`, each hop must itself be allowlisted.
   Resolve hops iteratively, not by open recursion, bounded by two named
   constants and a visited set so a cyclic config (`Host a ProxyJump b` /
   `Host b ProxyJump a`) cannot spin:
   - `_MAX_PROXYJUMP_DEPTH` (default 10) caps how many hop levels are followed.
   - `_MAX_SSH_RESOLUTIONS` (default 16) caps the total number of `ssh -G`
     invocations across one top-level destination (primary alias + all hops).
   - A `visited` set of hop specs (normalised) is carried; a hop already in
     `visited` is not resolved again (cycle break).
   Each hop is resolved by the same steps 1–6 (same env refusal, same config
   scan, same `ssh -G`), and its resolved `hostname` is added to the returned
   list so `_require_lab` checks it. When the depth cap, the invocation cap, or
   a cycle is hit, stop resolving further hops and add the *literal* hop token
   to the returned list instead (still matched by `_require_lab`, so a
   non-allowlisted jump host is blocked) — fail closed, never unbounded work.
8. **Return** `[resolved_hostname] + resolved_hop_hosts`.

Then in `_check_ssh_family`, replace the final

```
for host in hosts:
    _require_lab(host, prog, ctx)
```

with a pass that expands each host through `_resolve_ssh_destination` and calls
`_require_lab` on every resolved host. `_check_ssh_family` now takes the command's
captured `env` (the leading `VAR=value` assignments) and forwards it to the resolver
for the step-1 override refusal; the dispatcher already has `env` in hand from
`unwrap_with_env` and simply passes it through (`_check_ssh_family(prog, argv, env,
ctx)`). The returned audit string should report the hosts actually checked.

### Command-scoped environment (thread #2)

`unwrap_with_env(argv)` strips the leading `VAR=value` assignments off the command
and returns them separately; today `_check_ssh_family` is called without them, so
`HOME=/tmp/evil ssh amd-halo` or `PATH=/tmp ssh amd-halo` reaches the check with
those assignments invisible. For alias resolution this matters: the real `ssh` runs
under the overridden environment, so it reads `/tmp/evil/.ssh/config` (or an
attacker `ssh` on the front of `PATH`), while a `ssh -G` the resolver ran under the
hook's own environment would resolve a different configuration entirely.

Decision: forward `env` into `_check_ssh_family`, and in resolver step 1 **refuse**
(block) when `env` assigns either of the two variables that change which `ssh`
binary runs or which config is read — `HOME` and `PATH`. This is the fail-closed
choice: the guard does not try to reproduce an arbitrary overridden environment, and
it never runs `ssh -G` under an attacker-chosen `PATH`/`HOME` (which would be exactly
the command-execution path thread #3 closes). The refusal is symmetric with the
existing rsync transport-env refusal in `_check_ssh_family`/the dispatcher. Other
assignments (`LANG`, `TERM`, a `SendEnv`/`SetEnv`-style variable that only reaches
the *remote*, …) do not affect local config selection and are left alone.

### Faithful resolution and its options (thread #1 — the exact forwarding contract)

`ssh -G` honours the same config-selecting options the real command would, so the
resolver must pass through the ssh options that select which host config applies,
while never passing through an option that could itself route the session or execute
a command. `_ssh_positionals(argv, _SSH_VALUE_FLAGS)` already collects the option
pairs as `(flag, value)` in both the separate (`-o HostName=x`) and attached
(`-oHostName=x`, which `_ssh_positionals` splits into `("-o", "HostName=x")`)
spellings, and `-F file` / `-Ffile` likewise. The contract is therefore stated over
those normalised pairs:

**Forwarded to `ssh -G` (verbatim, both spellings):**

- `-F <configfile>` — the config file selector. Forwarded as `-F <value>`.
- Every `-o <key>=<value>` whose `<key>` is **not** in the rejected set below.
  This deliberately forwards *all* host/config-selecting keys — `HostName`,
  `Port`, `User`, `HostKeyAlias`, `CanonicalizeHostname`, `CanonicalDomains`, and
  any other — rather than an allow-enumerated subset, because under-forwarding is
  the risk: dropping `-o HostName=` reopens the `ssh -o HostName=evil.example
  amd-halo` hole (the guard would resolve `amd-halo` while the real ssh connects to
  `evil.example`). Forwarding a non-routing `-o` to `ssh -G` can only change the
  *reported* effective config, which is precisely what the guard must see.

**Rejected before resolution (raise a block; never forwarded) — case-insensitive,
both spellings:**

- `-o ProxyCommand=…` and `-o ProxyJump=…` — routing (already refused today).
- `-o LocalCommand=…` and `-o PermitLocalCommand=yes` — local command execution
  (already refused today).
- `-o KnownHostsCommand=…` — command execution during host-key lookup (to be
  **added** to the existing refusal regex; it is a command-executing `-o` form the
  current regex misses).
- `-J <hops>` — handled by the existing per-hop `_require_lab` loop; its hops are
  also expanded through the resolver so a jump-host *alias* is resolved like any
  other destination.

Everything else on the command line (positional host, `-p`/`-l`/`-i`/… transport
flags that do not select the config) is neither forwarded nor rejected — it does not
affect which host `ssh -G` reports. scp/sftp use `-F`/`-o` with the same meaning;
rsync's transport-substituting `-e`/`--rsh` and `RSYNC_RSH` are already refused
upstream, so rsync-over-ssh destinations reach the resolver as plain
`[user@]host:path` specs.

The regression tests (see Success criteria) must cover the attached and separate
spellings of both a forwarded `-o` (`-o HostName=` and `-oHostName=`) and `-F`
(`-F file` and `-Ffile`), and the rejection of each command-executing `-o` form.

### Why this is not a new exfiltration vector

The ssh binary the resolver runs (`shutil.which("ssh")`) is the same ssh that will
execute the real command afterwards, reading the same `~/.ssh/config`. If an
environment could make `ssh -G` report a false hostname, that same environment
already controls where the real `ssh` connects — so resolution introduces no
discrepancy an attacker could exploit, and the guard's existing fail-closed
posture is preserved: resolution only ever *adds* a block (a bad HostName,
ProxyJump or ProxyCommand) or defers to the literal match it replaces.

One caveat qualified this during review and is now **resolved** (thread #3): `ssh
-G` evaluates `Match exec` config blocks and runs `ProxyCommand`/`LocalCommand`/
`KnownHostsCommand` references, so a config that defines one would make resolution
run a local command — and because the guard's threat model is the assistant itself,
an assistant that writes its own `~/.ssh/config` with a `Match exec` directive could
have the guard execute that command via the resolution call even on a destination
the guard ultimately blocks, where the real `ssh` would never run it. The resolution
is the step-3 static config scan: `ssh -G` is invoked **only** on a config proven
free of command-executing directives, and otherwise the resolver takes the literal
fallback. So resolution adds **no** new path that executes an assistant-controlled
command; it only ever *adds* a block (a bad HostName, ProxyJump or ProxyCommand) or
defers to the literal match it replaces.

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
  regression test for the forwarding contract in "Faithful resolution".) The
  **attached** spelling `ssh -oHostName=evil.example amd-halo` is blocked the same
  way, and `-F file` / `-Ffile` both reach `ssh -G` as the config selector.
- Each command-executing `-o` form is **rejected before resolution**:
  `-o ProxyCommand=…`, `-o ProxyJump=…`, `-o LocalCommand=…`,
  `-o PermitLocalCommand=yes`, and the newly-added `-o KnownHostsCommand=…`, in both
  the separate and attached spellings and case-insensitively.
- A command-scoped environment override that selects the config or binary is
  **blocked**: `HOME=/tmp/evil ssh amd-halo` and `PATH=/tmp ssh amd-halo` are both
  refused, while a harmless assignment (`LANG=C ssh amd-halo`) is unaffected.
- A config that can execute a command during resolution causes the resolver to
  **not run `ssh -G`** and fall back to the literal match: a `Match exec` (or
  `ProxyCommand`/`LocalCommand`/`KnownHostsCommand`) directive anywhere in the
  reachable config chain means `ssh amd-halo-ci` is treated literally (blocked,
  since the alias is not itself allowlisted), and — critically — the test confirms
  the `Match exec` command is **never executed** by resolution (e.g. a `Match exec`
  that would `touch` a sentinel file leaves no sentinel).
- A cyclic `ProxyJump` config (`Host a ProxyJump b` / `Host b ProxyJump a`)
  terminates: resolution is bounded by `_MAX_PROXYJUMP_DEPTH`/`_MAX_SSH_RESOLUTIONS`
  and a visited set, makes a bounded number of `ssh -G` calls, and falls back to
  literal matching of the unresolved hop (blocked).
- When `ssh -G` fails (fake ssh exits non-zero, or ssh is absent), the guard falls
  back to a literal match: an allowlisted literal (`ssh amd-halo`) is still
  allowed and a non-allowlisted literal (`ssh evil.example`) is still blocked —
  i.e. identical to today's behaviour.
- The same cases hold for `scp`/`rsync`-over-ssh destinations.
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
  the agent must not route around the hook that protects these files. Separately,
  `verify-exfiltration-guard.sh` CHECK 3 compares the guard's `GUARD_FILES`
  against the base branch off non-`ci/` branches, and `exfil_bash_network.py` is
  one of those files, so this change trips that check and the implementing job
  must run the workflow with `override_integrity_checks` set (see the verified
  dependency note under "Resolved review decisions"). This is distinct from the
  CI-file lock: a guard-library
  change is not under `check-ci-file-lock.sh` (which covers `.github/workflows/`
  and `tools/ci/`), so no `ci/...` branch name is required.

## Resolved review decisions

The review raised five points; all are now settled in the body above (none is left
to the implementer's discretion):

1. **Which ssh options to forward to `ssh -G`** (thread #1). Settled in "Faithful
   resolution and its options": forward `-F <file>` and **every** non-rejected
   `-o <key>=<value>` (so no host/config-selecting key is dropped, closing the
   `-o HostName=` hole), in both the separate and attached spellings; reject the
   routing/command-executing `-o` forms (`ProxyCommand`, `ProxyJump`, `LocalCommand`,
   `PermitLocalCommand`, and the newly-added `KnownHostsCommand`) before resolution;
   forward/forget everything else. The resolver's docstring records this set.
2. **Command-scoped environment overrides** (thread #2). Settled in "Command-scoped
   environment": `_check_ssh_family` now receives the captured `env`, and the
   resolver blocks when `HOME` or `PATH` is assigned — fail closed, never resolve
   under or around an attacker-chosen environment.
3. **`Match exec` / command execution during `ssh -G`** (thread #3). Settled in
   resolver step 3 and "Why this is not a new exfiltration vector": a static,
   execution-free scan of the reachable config chain precedes `ssh -G`, and `ssh -G`
   runs **only** on a config proven free of `Match exec`/`ProxyCommand`/
   `LocalCommand`/`KnownHostsCommand`; otherwise the resolver falls back to the
   literal match. No new command-execution path is introduced.
4. **ProxyJump depth and cycle policy** (thread #4). Settled in resolver step 7:
   iterative hop resolution bounded by `_MAX_PROXYJUMP_DEPTH` (10) and
   `_MAX_SSH_RESOLUTIONS` (16) with a visited set, falling back to literal matching
   of an unresolved hop. A cyclic config terminates with bounded subprocess work.
5. **No pinned release number** (thread #5). The release line now refers to the root
   `pom.xml` `<version>` instead of a concrete number.

Separately, a verified dependency (not an open question): **`verify-exfiltration-guard.sh`
base-branch comparison.** The verifier's `GUARD_FILES` list includes
`exfil_bash_network.py` and `test_exfiltration_guard_check.py`, and its CHECK 3
compares every one of those between the merge base with the base branch and HEAD on a
non-`ci/` branch. A guard-*library* edit therefore **does** trip the integrity check —
it is not limited to workflow-file edits — so the implementing job must run the
workflow with `override_integrity_checks` set, exactly as the design doc's "Adding an
allowlisted host" step 4 prescribes for any enforcement-file change. The new sibling
test module is not in `GUARD_FILES`, so adding it does not itself trip CHECK 3; the
edit to `exfil_bash_network.py` (and any new case added to
`test_exfiltration_guard_check.py`) is what requires the override. This is a
dependency to satisfy at submission time, not a reason to weaken the check.

## Estimated complexity

**Small–moderate.** The resolver plus an execution-free config-chain scanner and a
bounded ProxyJump walk are added to `exfil_bash_network.py`; `_check_ssh_family`
gains an `env` parameter (threaded through from the dispatcher) and its final loop
expands each host through the resolver; the command-executing `-o` refusal regex
gains `KnownHostsCommand`; one new test module; and a two-sentence doc note. No API
changes beyond the internal `_check_ssh_family` signature, no new Maven/Python
module, no production-code changes outside the hook library.
