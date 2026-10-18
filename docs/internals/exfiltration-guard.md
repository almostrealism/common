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
and its split-out bash modules (`exfil_bash_lex.py`, `exfil_bash_network.py`,
`exfil_bash_runner.py`, `exfil_bash_vcs.py`, `exfil_bash_dispatch.py`), all
pinned by `test_exfiltration_guard_check.py`; a decision table written in prose
drifts from them within a release or two. This document holds what the code
cannot say for itself: the threat model, the bypasses that were considered
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

## Design stance

The guard's job is to stop **obvious, deliberate** attempts to move data
somewhere else — a named transport variable, an option that swaps git's helper
programs, a redirection into a synced folder. It is deliberately *not* a
general-purpose sandbox, and in practice it is more protective than the day-to-day
workload strictly needs. Where a case is genuinely ambiguous, or where failing
closed would catch ordinary agent work that has nothing to do with exfiltration,
the guard **allows rather than blocks**. Fixes target clear, deliberate routes;
they do not fail closed on constructs the guard merely cannot resolve.

This refines the threat-model preference for *deny when uncertain*: that
preference still governs constructs that are specifically about transport or
configuration — a computed `-c` config value, an unreadable `TAR_OPTIONS`, a
program-running tar filter — where the only reason to spell it that way is to
defeat inspection. It does **not** extend to generic, non-transport ambiguity that
ordinary commands hit all the time.

The standing accepted-open case is the **relative copy/redirection destination
after a `cd` to an unknown directory** (e.g. `cd "$(mktemp -d)" && cp secret out`).
When the working directory is computed at run time the guard cannot resolve a
relative destination to decide whether it lands in a synced folder or mounted
volume — but this is exactly the shape of ordinary agent work (make a scratch
directory, write into it), so the guard leaves it allowed rather than block it.
Tightening this to fail closed was considered and declined by operator decision;
see the "Reading from such a folder" and computed-destination rows for the cases
that *are* closed (a path spelled to reach a protected folder, or a destination
the shell itself rewrites).

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
| A command inside backticks, `"$(…)"`, `<(…)`, or an unquoted heredoc body | every substitution the local shell performs is analyzed as a command in its own right (`split_substitutions`) |
| A `#` comment line ahead of the real command | comments are removed where their line ends, before lines are joined; shlex never strips them, so a comment cannot swallow what follows |
| Substitution text inside single quotes | inert for the local shell, so it no longer blocks other programs on the line; it still blocks `ssh`, shells and interpreters, which evaluate their arguments |
| A substitution after `cd` | analyzed with the directory unknown, so a `git push` in it fails closed |
| A line continuation mid-word (`safe\⏎#; curl …`) | the continuation is removed outright, as the shell removes it, so the `#` stays inside the word; `/dev/tcp` is checked again on the joined text |
| `<<EOF` inside a comment or quotes, ahead of the real command | only a `<<` the shell reads (outside quotes and comments, quote state followed across lines) starts a heredoc |
| A command glued to a closing subshell (`(true); curl …`) | shlex joins `);` into one token; it is split back into operators, so the command after it is its own command |
| `(cd other && …); next` | a subshell's `cd` ends with the subshell, as it does in the shell, so `next` resolves paths from the outer directory |
| `alias x=curl; x …`, `f() { curl …; }; f` | definition + network word → block |
| `echo <b64> \| base64 -d \| sh` | pipe into shell → block |
| `cat script \| python3`, `python3 - <<EOF` | pipe → block; heredoc body scanned |
| Hex/base64 payload piped into a network tool | the network tool itself is judged; destination still needs allowlisting |
| Writing a script then running it | file is read and scanned; unreadable blocks |
| `xargs curl`, `find -exec curl`, `timeout 30 curl`, `sudo curl`, `env X=1 curl` | wrappers unwrapped |
| `curl -x proxy`, `--resolve`, `-K config` | block |
| `git remote set-url` then `git push origin` | set-url blocked; push checks the pinned `host/owner/repo` |
| Editing `.git/config` with the Write tool, then pushing | push still checks the pin → block |
| `git -c remote.origin.url=… push`, `git --config-env=remote.origin.url=… push`, `git -C /other push`, `--git-dir`/`--work-tree`, `--receive-pack=` | block |
| `GIT_DIR=/other.git git push origin`, `env GIT_WORK_TREE=… git …`, `GIT_INDEX_FILE`/`GIT_OBJECT_DIRECTORY`/`GIT_COMMON_DIR`/`GIT_ALTERNATE_OBJECT_DIRECTORIES`/`GIT_NAMESPACE` | block for every git subcommand — a leading environment assignment (or one after an `env` wrapper) that selects another repository would otherwise make git operate elsewhere while the command read as ordinary; the assignments are preserved through unwrapping and checked. A benign variable (`GIT_PAGER=cat git log`) and a repository-selecting variable on a non-git command stay allowed |
| `git -c "$C" push` / `git --config-env "$C" push` (the value the shell computes) | block — a computed config value cannot be matched against the sensitive-key list, so git could rewrite the remote while the guard checked the on-disk origin |
| `GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=remote.origin.url GIT_CONFIG_VALUE_0=… git push`, `GIT_CONFIG_PARAMETERS="'remote.origin.url'='…'" git push`, `GIT_SSH_COMMAND=… git …`, `GIT_CONFIG`/`GIT_CONFIG_GLOBAL`/`GIT_CONFIG_SYSTEM`, `GIT_SSH`/`GIT_PROXY_COMMAND`/`GIT_ASKPASS`/`GIT_EXTERNAL_DIFF` | block for every git subcommand — these are the environment-borne equivalents of `-c`/`--config-env` and the remote/transport settings: the indexed config pair and `GIT_CONFIG_PARAMETERS` (git's own channel for `-c` overrides, read as `'key'='value'` pairs) both inject config (including `remote.origin.url`) after the guard read the on-disk origin, and the transport variables run an arbitrary program. `GIT_PAGER`/`GIT_EDITOR` stay allowed |
| `GIT_EXEC_PATH=/tmp/evil git push` (inline, exported, or via a wrapper), `git --exec-path=/tmp/evil push`, `git --exec-path /tmp/evil push` | block — `GIT_EXEC_PATH` and the `--exec-path` option both point git at another directory of helper programs, where an attacker's `git-remote-*` replaces the transport; the env form joins the configuration/transport predicate (refused inline, exported and through a wrapper) and the option is refused whenever a value is given. A bare `git --exec-path`, which only prints the path, stays allowed |
| `export GIT_DIR=/other.git; git …`, `export GIT_SSH_COMMAND=…; git …`, `declare -x GIT_CONFIG_GLOBAL=…` | block — only an *exported* variable reaches a later git in a separate simple command, where the leading-assignment capture cannot see it, so the `export`/`declare`/`typeset` of a repository-selecting or configuration/transport git variable is refused at the export itself; `export EDITOR=vim` / `export GIT_PAGER=cat` stay allowed |
| `export GIT_DIR+=/tmp/other.git`, `GIT_DIR+=/tmp/other.git git push`, `export RSYNC_RSH+=…` (bash's append assignment `NAME+=value`) | block — the append form sets the same variable as `NAME=value`, so a repository-selecting or transport variable smuggled through `+=` is caught too: the leading `+` is matched when recognising the assignment (otherwise `GIT_DIR+=…` reads as the program name and git runs unchecked) and then stripped from the captured name (otherwise `partition("=")` yields the harmless name `GIT_DIR+` and slips past the denylist); an ordinary `export PATH+=:/x` append stays allowed |
| `git -C <dir>` inside this project's work tree | followed like a `cd`; a push from there still faces the pin check. More than one `-C` blocks, as does a `-C` the shell expands (`$DIR`, `~/x`, a glob or brace list) and a `-C` that selects a separate repository nested inside the work tree — a nested work tree or a bare repo alike, compared by the git directory git actually selects |
| `cd /other/clone && git push origin` | effective directory tracked; pin check applies there |
| `gh api -f`, `gh gist`, `gh release upload`, `gh pr create --body-file` | block |
| `gh pr create --body "$(cat secret)"` | substitution on a `gh` line → block |
| DNS exfiltration (`dig data.evil`, `$(cat s).evil`) | probe tools need a lab host; substitution blocks |
| `/dev/tcp` bash redirections | regex on the raw text |
| `cp`/`mv`/`rsync`/`tee`/`dd`/`tar`/`zip`/`ln`/`install`/`ditto` writing into iCloud/Dropbox/OneDrive/`/Volumes` | block — the destination operand (`-t`, `of=`, `tar -C` when extracting, `tar -f` when creating, the zip archive) is what is checked; so are `zip -b`/`--temp-path` (the temporary archive's directory) |
| `tar --use-compress-program=…`/`-I …`/`--to-command=…`/`--checkpoint-action=exec=…`, and any unambiguous abbreviation (`--use-com=`, `--to-c=`, `--checkpoint-a=`) | block — tar runs the named program as a filter (or checkpoint action), which can stream the archive to another host while the only operand checked is the local archive; GNU tar resolves any unambiguous prefix of a long option, so prefixes are matched too, while a plain `--checkpoint` frequency (which runs nothing) stays allowed |
| `TAR_OPTIONS='--checkpoint-action=exec=…' tar -cf out.tar src` (inline, via `env`, or `export TAR_OPTIONS=…; tar …`), `TAR_OPTIONS=$OPTS tar …` | block — GNU tar prepends `$TAR_OPTIONS` to its options, so a program-running option there runs the filter while only the archive operand is on the command line; the value is held to the same tar-filter check in the inline, `env`-wrapper and exported forms (an exported value reaches a later tar where the tar command's own leading env cannot see it, so it is checked at the `export`), a value the shell computes (`$OPTS`) cannot be read and fails closed, and an unparsable value fails closed, while a benign `TAR_OPTIONS=--totals` stays allowed |
| `TAR_OPTIONS='--checkpoint-action=exec=…'; export TAR_OPTIONS; tar …` (a bare `export`/`declare -x`/`typeset -x TAR_OPTIONS` with no value) | block — a bare export carries whatever value a prior standalone assignment set to a later tar, but the export statement itself does not spell the value out, so the guard cannot read it and fails closed; only an inline `export TAR_OPTIONS=<value>` with the value written out is verified, and a benign `export TAR_OPTIONS=--totals` stays allowed |
| `rsync --rsync-path='curl -T - …' secret amd-halo:dst` (either spelling) | block — `--rsync-path` runs an arbitrary command on the remote host, which can forward the transferred data onward even when the rsync destination host is allowlisted; an ordinary `rsync -av dir/ agent1@amd-halo:/tmp/` stays allowed |
| `rsync -e 'curl -T - …' secret amd-halo:dst`, `rsync --rsh='…' …` (separate, attached, `=`-joined, or a short cluster containing lowercase `e` such as `-ave`) | block — `-e`/`--rsh` substitutes the transport with an arbitrary command, which can forward the transferred data onward even to an allowlisted host; `e` is rsync's only lowercase short option that does this (capital `-E` is `--executability`), so any single-dash cluster containing a lowercase `e` is refused along with the long spellings |
| `env -S 'curl -T - …'` / `env --split-string=…`, `env -C /tmp git …` / `env --chdir=…` (and unambiguous abbreviations) | block — `-S`/`--split-string` re-parses its single argument into a whole command the unwrapper would hand back as one opaque token, and `-C`/`--chdir` relocates the command; the guard cannot follow either, so it fails closed on them before analysis, while a plain `env FOO=bar git …` stays allowed |
| `env -u X GIT_DIR=/other.git git push`, `env --unset X …`, `env --unset=X …` (also `-a`/`--argv0`) | block — `env`'s value-taking options (`-u`/`--unset` to unset a variable, `-a`/`--argv0`) consume the following token, so the unwrapper must skip it to reach the real program; left unskipped it handed back the option's value (`X`) as the program and the repository-selecting assignment went unchecked. The separate spelling is skipped (the attached `-uX` and `=`-joined `--unset=X` carry their own value), so the git env check runs; `env -u GIT_PAGER git status` (no repository-selecting variable) stays allowed |
| `GIT_DIR=/other.git sh -c 'git push origin HEAD'`, `GIT_SSH_COMMAND=… bash -c '…'`, `TAR_OPTIONS='--checkpoint-action=exec=…' sh -c 'tar …'`, `GIT_DIR=… python -c '…'` | block — a shell or interpreter wrapper re-analyses its nested command without the leading `VAR=value` assignments (those are consumed only in the git/tar branches), so a repository-selecting or configuration/transport git variable, or a program-running `TAR_OPTIONS`, handed to the wrapper would be inherited by the child and reach a later `git`/`tar` unchecked; the captured environment is held at the wrapper — the git variables refused on the name alone, `TAR_OPTIONS` run through the tar-filter check — while a benign `GIT_PAGER=cat sh -c 'git log'` or `FOO=bar sh -c 'echo hi'` stays allowed |
| `git -c branch.<b>.remote=…`, `-c include.path=…`, `-c includeIf.….path=…` (also via `--config-env`) | block — these `-c`/`--config-env` keys route indirectly to another remote or config file: `branch.<b>.remote` rewrites where a branch pushes, and `include.path`/`includeIf` pull in an attacker-controlled config that can set `remote.origin.url`, so the `branch.`/`include.`/`includeIf.` prefixes are refused alongside the remote/url/transport keys; a benign `-c core.pager=cat` stays allowed |
| `https_proxy=… git push` (any of `http_proxy`/`https_proxy`/`all_proxy`/`ftp_proxy`, case-insensitive), `HOME=… git …`, `XDG_CONFIG_HOME=… git …` | block for every git subcommand — a proxy variable routes git's connection through another host, and `HOME`/`XDG_CONFIG_HOME` relocate where git reads its user/global config from (loading one that can rewrite `remote.origin.url`); both join the same repository-selecting/transport predicate the `-c` environment equivalents use, so they are refused inline, exported and through a wrapper. The same variables on a non-git command, and `GIT_PAGER`, stay allowed |
| `export "$n"`, `export GIT_*`, `export {GIT_DIR,X}=…` (an export whose operand *name* the shell computes or expands) | block — a computed or glob/brace operand name could set any variable at all, including a repository-selecting one, so the guard cannot clear it and fails closed on the name; the value may still legitimately contain `$` (`export PATH=$PATH:/x` stays allowed) |
| `set -a` / `set -o allexport` (also inside a cluster, `set -ea`) | block — allexport exports every later plain assignment, so a subsequent `GIT_DIR=…` or `RSYNC_RSH=…` would reach a child command the leading-assignment capture never sees; the guard cannot follow that state across the script, so it fails closed when allexport is switched on, while turning it off (`set +a`, `set +o allexport`) stays allowed |
| `find . -exec env GIT_DIR=… git push \;` | block — `find -exec` runs its command with its own leading `VAR=value` assignments, which were previously dropped along with the `-exec` wrapper; the exec command is now unwrapped with its environment and merged, so a repository-selecting or transport variable reaches the git check. A plain `find . -exec git status \;` stays allowed |
| `RSYNC_RSH='curl -T - …' rsync …` (inline, `export RSYNC_RSH=…; rsync …`, or handed to a shell/interpreter wrapper) | block — `RSYNC_RSH` is the environment form of rsync's `-e`: it names the remote shell rsync runs, which can be an arbitrary command that forwards the data onward, so it is refused inline, exported for a later rsync, and through a wrapper, while an ordinary `rsync -av dir/ agent1@amd-halo:/tmp/` stays allowed |
| `RSYNC_CONNECT_PROG='…' rsync …`, `RSYNC_PROXY=host:port rsync …` (inline, exported, or via a wrapper) | block — like `RSYNC_RSH`, these choose or redirect rsync's transport: `RSYNC_CONNECT_PROG` names the program rsync runs to open a daemon connection and `RSYNC_PROXY` routes that connection through an HTTP proxy host, so either sends the copied data through an attacker-chosen hop even to an allowlisted destination. Both join the rsync-transport predicate and are refused inline, exported and through a wrapper, while an ordinary `rsync -av dir/ agent1@amd-halo:/tmp/` stays allowed |
| A shell redirection into a protected path (`cat secret > /Volumes/USB/out`, `>> ~/Dropbox/x`, `tar -cf - src > /Volumes/USB/out.tar`), including a redirection-only command with no program (`> /Volumes/USB/out`) | block — the redirection target is a destination just as a copy tool's operand is, so a mounted volume or synced folder is an upload and a computed (`> "$DEST"`) or glob/brace (`> /V*`) target fails closed; a command with no program but a redirect target is still kept and its target checked; a literal path, an fd duplication (`2>&1`), and reading *from* a volume (`cat /Volumes/USB/f > local.txt`) stay allowed |
| A copy destination the shell computes — a parameter/command substitution (`cp f "$DEST"`, `mv f "$(…)"`) or a glob/brace expansion (`cp secret /V*`, `mv a /Volumes/{x,y}`) | block — a path the shell rewrites at run time cannot be checked; a computed source is allowed |
| Reading from such a folder (`cp /Volumes/USB/f .`, `cat`, `dd if=`, `tar -cf out.tar /Volumes/…`) | allowed; only a write into one is an upload |
| A copy tool option the guard does not recognize, or `unzip`/`gzip`/`bzip2`/`xz` | destination cannot be identified → every argument is a possible destination, so any synced/volume path, computed path, or glob/brace among the arguments blocks |
| A protected folder named with no trailing slash (`cp f ~/Dropbox`, `cp f /home/u/Dropbox`, `cp f /Volumes`, `tee ~/OneDrive`, `cat secret > ~/Dropbox`) | block — synced-folder names and `/Volumes` are matched at a path boundary, so a terminal component or a bare mount root is caught as well as the trailing-slash forms; a longer name that merely contains one of the words (`Dropbox-backup`, `/Volumes-of-x`, `BoxOffice`) is not a protected folder and stays allowed |
| A copy or redirection destination that is, or passes through, a symlink resolving into `/Volumes` or a synced folder (`ln -s /Volumes/USB link; cp secret link/out`, `cat secret > link/out`) | block — the existing ancestors of the target are resolved with `realpath` (shared by the redirect and copy checks via `_points_into_protected`) so a symlink into a protected path is caught like the literal path; a computed or glob/brace target is left to the fail-closed checks, and a symlink into an ordinary directory stays allowed |
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
- **The inline-program scan mostly matches uses, not mentions.** The
  module/API entries most prone to plain-English mentions in
  `NETWORK_CODE_PATTERNS` (`socket`, `urllib`, `subprocess`, `websocket(s)`,
  `axios`, and the rest of that family) require an import, a
  module-qualified call, or a constructor with no space before the
  parenthesis — never a bare module or API name, and never a
  parenthetical remark such as "the engine socket (an open TCP port)" —
  for the same reason the URL bullet above gives: a guard that blocks
  prose teaches its way around itself. `Net::` is unaffected by this: it
  already required the `::` module-path form before this scan existed,
  so it was never a bare-word match. `LWP::` was narrowed by this same
  effort — it used to match the bare word `LWP` and was tightened to
  require the `::` module-path form alongside the other entries above.
  `new WebSocket(...)` is the one entry that tolerates a space before the
  parenthesis, because real code was found using that spacing and the
  no-space rule let it through; a spaced call is matched only when the
  argument opens with a single quote, double quote, or backtick — the three
  ways JavaScript opens a string literal, including a template literal — and
  a parenthetical remark such as "the new WebSocket (RFC 6455) protocol"
  opens with none of them. A spaced call whose destination is a variable
  (`new WebSocket (url)`) still passes, on the same accepted-cost basis as
  the rest of this list — there is no regex-only way to tell that apart
  from prose once both the space and the literal are gone.
  A handful of other
  entries have no natural "qualified" form at all (`XMLHttpRequest`,
  `HttpClient`, `URLConnection`, `child_process`, `node-fetch`,
  `fsockopen`, `stream_socket_client`, `Invoke-WebRequest`,
  `Invoke-RestMethod`, and similar language built-ins) and still match
  the bare identifier, on the same accepted-cost basis as everywhere else
  in this list.
- **Interpreter scripts are scanned by pattern**, not executed symbolically. A
  program that assembles `"soc" + "ket"` at run time and imports it dynamically is
  not caught. Pipe-fed programs are refused precisely because of this.
- **A preloaded package name is not scanned.** `node -r ./setup.js` and `ruby -r ./x`
  are read like scripts (a path that cannot be read blocks), and a value spelled as a
  URI (`--import`/`--loader` accept ES module specifiers, not just paths) is handled
  the same way: `data:` is decoded and scanned as inline code, `file:` is read like a
  path, `node:` passes as one of Node's own builtin modules, and any other scheme is
  denied outright because the guard cannot fetch and inspect it. A bare package name
  such as `node -r ts-node/register` is still resolved from a module path the guard
  does not model and passes — the same gap a `require` inside a scanned script has.
  Options that only configure the run (`python -W error`, `-X dev`, `ruby -I lib`)
  are skipped with their value rather than mistaken for the program; an option that
  changes the working directory before the run (Ruby's `-C dir`) updates where later
  relative paths resolve instead of being treated as an ordinary value flag.
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
| `.claude/hooks/lib/exfiltration_guard_check.py` | the decision core, `decide(payload, hook_cwd, log_path)`; harness-neutral; re-exports `Allowlist`, `_shell_invocation`, `normalize_host`, `owner_repo`, `SHELLS`, `GuardError` for backward compatibility |
| `.claude/hooks/lib/exfil_bash_lex.py` | shell command tokenization, comment removal and command-substitution extraction (`split_substitutions`), wrapper unwrapping, and the shared `GuardError`, `SHELLS`, `normalize_host`, `owner_repo` |
| `.claude/hooks/lib/exfil_bash_network.py` | URL handling, curl/wget upload checks, ssh family, raw-socket and probe tools, the network-code patterns an inline program is scanned for |
| `.claude/hooks/lib/exfil_bash_runner.py` | shell option grammar (`_shell_invocation`) and interpreter / script-file checks |
| `.claude/hooks/lib/exfil_bash_vcs.py` | `git push` and `gh` checks, including the origin/host allowlist pin |
| `.claude/hooks/lib/exfil_bash_dispatch.py` | `analyze_command` / `bash_targets` and `BashContext`; routes each simple command to the check that knows the program |
| `.claude/hooks/block-exfiltration.sh` | thin Claude Code adapter (`--stdin`); exit 2 blocks |
| `.claude/hooks/exfil-allowlist.txt` | lab hosts and git remotes, read from `HEAD` |
| `.claude/settings.json` | the `PreToolUse` registration, covering `Artifact`, `SendUserFile` and `Bash` |
| `.claude/hooks/lib/test_exfiltration_guard_check.py` | the behavioral authority — every rule above has cases here |
| `tools/ci/agent-protection/verify-exfiltration-guard.sh` | CI check: presence on `HEAD`, registration coverage, and (off `ci/` branches) no change against the base; its header documents the exit codes |
| `tools/ci/agent-protection/exfil_guard_registration.py` | shared registration parsing, used by the verifier and its tests |
