# Stop the exfiltration guard reading an interpreter's data arguments as the program

**Tracker task:** `92172edc-99c0-49e4-b72a-d81c7dfc97cf` — "Exfiltration guard reads an
interpreter's data argument as the program after a stdin or inline program"
**Project:** Common (release tracked in the project tracker)

## Motivation

The Bash exfiltration guard blocks ordinary, read-only commands. The observed case:

```
for f in plans/reg-*.json; do python3 -I - "$f" <<'EOF'
import json, sys; d = json.load(open(sys.argv[1])); ...
EOF
done
```

was refused with `cannot read '$f' to scan it (FileNotFoundError)`. The program is the
heredoc fed to `python3 -`, which the guard had already scanned and found clean; `"$f"` is
`sys.argv[1]`, a data file. The guard treated it as a second program and tried to open the
literal text `$f`. Every such false positive teaches agents to route around the guard, and
makes a common, safe idiom (a heredoc script applied to each file in a loop) unusable.

## Cause

`_check_interpreter` in `.claude/hooks/lib/exfil_bash_runner.py` walks the
interpreter's arguments looking for the program. Two branches fix what the program is and then
keep looping:

- `tok == "-"` scans the stdin/heredoc program, sets `code_seen`, and
  `continue`s.
- The inline-code branch (`-c`, `-e`, `-p`, `-r` for php, perl `-M…`, …)
  scans the code, sets `code_seen`, and `continue`s.

After either, the next token that does not start with `-` falls through to
`_read_script(tok, ctx)` and is read and scanned as a script. Once the program is
known, every later positional token is an argument *to* that program. So
`python3 -c 'print(1)' data.txt` also fails today when `data.txt` does not exist, and passes
only by accident (it is "scanned") when it does.

A token containing `$` or a backtick cannot name a file the guard can resolve; today it reaches
`open()` verbatim and is reported as a confusing `FileNotFoundError` on the literal text.
`_resolve_dir` already applies exactly this test for directory arguments.

## Scope

In scope (all in `.claude/hooks/lib/`, the guard's file lists in
`tools/ci/agent-protection/`, and the enforcement-tampering file list in
`.github/workflows/analysis.yaml`):

1. After `-` (stdin program): stop the argument walk and return `f"{prog}:stdin"`; the
   remaining tokens are script arguments and are not read.
2. After an inline-code flag, handling splits by interpreter family. Python's `-c` turns the
   whole remainder of the command line into `sys.argv`, so once it has been scanned no later
   token is an option: the Python family returns `f"{prog}:inline"` immediately, before the
   `-m`, `-`, positional, or awk handlers run. That is what makes `python3 -c 'print(1)' data.txt`,
   `python3 -c 'print(1)' -`, and `python3 -c 'x' -m http.server` all allow — the program is
   fixed and the rest is data. Every other interpreter — Perl and Ruby, which accept *repeated*
   inline-code flags; Node; and interpreters the guard has no option grammar for — keeps the loop
   going after the first inline flag so a later one is still scanned (`perl -e 'a' -e 'b' file`
   scans both programs), and a `code_seen` early return just above the `tok == "-"` branch then
   ends the walk at the first positional (or `-`) without reading it — except that a
   shell-expanded positional token on that path (`perl -e 'print 1' ${more[@]}`, where `${more[@]}`
   can expand at run time into a further `-e` and its code) is *refused* there, not returned past,
   so a second program smuggled through an expansion is never silently skipped. That path stays
   fail-closed: the only reason to single Python out is that its `-c` grammar is unambiguous
   enough — the remainder is unconditionally `sys.argv`, expansions included — to return early
   safely (Open question 2).
3. A positional token that contains `$` or a backtick, reached while the program is still
   unknown (`python3 "$script"`), blocks with a message that says what it is — an argument the
   guard cannot resolve to a file — instead of a `FileNotFoundError` on the literal text. Once
   the program is a resolved script path or Python's `-c`, such a token is just data and passes
   (covered by items 1–2); the one place it still blocks is the non-Python inline-code path,
   where it could expand into a further program (item 2).
4. Tests for all of the above (see Success criteria).

Optional, lower priority (the task's "Related" item; see Open questions): scanning a heredoc
body written to a script path earlier in the same command, instead of failing the read of a
file that does not exist yet at scan time.

Out of scope: the shell branch (`_check_shell`, `bash "$f"`), awk's `-f progfile`, and any
change to which modules or code count as network-capable.

## Approach

All production changes are in `_check_interpreter` in `exfil_bash_runner.py`.

- **Stdin program.** In the `tok == "-"` branch, after `_check_stdin_program(...)`, return
  `f"{prog}:stdin"` instead of `continue`. This matches the label `_check_shell` already uses
  for a stdin program. The trailing `if not code_seen: _check_stdin_program(...)` is unchanged.
- **Inline program.** Two cases, by interpreter family.
  - **Python** (family `python`: `python`, `python2`, `python3`, `pypy`, `pypy3`). `-c` makes
    everything after it `sys.argv`, so nothing that follows is an option. In the inline-code
    branch, when `family == "python"`, return `f"{prog}:inline"` as soon as `_scan_code(...)`
    has run — before the `-m` handler, the `tok == "-"` branch, and the positional/awk handlers.
    This is what stops `python3 -c 'x' -m http.server` from being refused as a network module and
    `python3 -c 'print(1)' -` from scanning stdin as a second program: both tokens are arguments
    to the program `-c` already fixed. `python3 -m http.server` with no preceding `-c` is
    unaffected — `code_seen` is false there, so the `-m` handler is still reached and still blocks.
  - **Every other interpreter** (Perl, Ruby, Node, and grammar-less interpreters such as php or
    lua). These may carry repeated inline-code
    flags (`perl -e 'a' -e 'b'`), so the loop must keep going after the first one: it sets
    `code_seen` and `continue`s, scanning each further `-e`/`-E` program. A `code_seen` early
    return added just above `if tok == "-":` then ends the walk at the first positional token
    (or `-`) without reading it. That early return is conditional on the token: a plain
    positional returns `f"{prog}:inline"`, but one that `is_shell_expanded` raises the same
    "expanded by the shell" `_GuardError` as the unknown-program case below rather than returning
    past it. This is because, for these families, a shell-expanded token can expand at run time
    into a further `-e` and its code — `perl -e 'print 1' ${more[@]}` with
    `more=(-e 'use Socket; …')` is one positional token to the guard but two programs to Perl —
    and returning early would skip the second program, a fail-open regression from today's
    behaviour (the token currently reaches `_read_script` and fails closed on the unreadable
    name). Placing the early return before `tok == "-"` is
    what makes a `-` after inline code read as a positional argument rather than a second stdin
    program. For these families the `-m` handler still runs after inline code, so
    `perl -e 'x' -m Foo`-style tokens stay fail-closed (see Open question 2); no such case has
    been reported.
  The awk branch keeps its own `if not code_seen` scan of the first positional; with the early
  return above it (and the Python immediate return), it is reached only when no inline program
  has been seen.
- **Unresolvable token.** The predicate already exists: `is_shell_expanded(token)` in
  `exfil_bash_lex.py` returns `"$" in token or "`" in token`, and is imported and used by the
  dispatcher and VCS checks. `exfil_bash_runner.py` already imports from that module
  (`SHELLS`, `GuardError`, `match_flag`); add `is_shell_expanded` to that import and use it in
  both `_resolve_dir` (replacing its inline `"$" in target or "`" in target`) and the
  interpreter loop. Do not introduce a second predicate in `exfil_bash_runner.py` — that would
  duplicate the shared helper and let the two definitions drift. The predicate is consulted at
  two points in the loop, both fail-closed:
  - When the program is still unknown and a positional token satisfies it (`python3 "$script"`),
    raise
    `_GuardError(f"{prog} script {tok!r} is expanded by the shell, so the guard cannot read it to scan it; a program the guard cannot read does not run")`
    before calling `_read_script`. The fail-closed outcome is unchanged from today (the token
    would reach `_read_script` and fail on the unreadable name); only the message is.
  - On the non-Python `code_seen` early-return path, a shell-expanded positional token raises the
    same error instead of returning `f"{prog}:inline"` — see the inline-program bullet above. Here
    the outcome *does* matter: returning early would let a shell-expanded token (`${more[@]}`)
    that expands into a further `-e` program slip past unscanned, so the guard must refuse it.
  Python's immediate return after `-c` needs no such check: its remainder is unconditionally
  `sys.argv`, so an expansion there is data, never a second program.
- **Return labels.** `_check_interpreter`'s return value is only a descriptive label consumed by
  the dispatcher in `exfil_bash_dispatch.py`; no caller branches on it, so returning
  early changes nothing downstream.

### Tests

`.claude/hooks/lib/test_exfiltration_guard_check.py` is 1,575 lines, just under the 1,600-line
limit, so the new cases go in a new module, `test_exfiltration_guard_interpreter.py`, built on
the shared `GuardFixture` imported from `test_exfiltration_guard_check.py`. This follows the
precedent of `test_exfiltration_guard_transport.py`. CI already picks it up: `analysis.yaml`
runs `python3 -m unittest discover -s .claude/hooks/lib -p "test_exfiltration_guard_*.py"`.
The new file is guard infrastructure, so it must be registered everywhere the transport
module already is. The transport module appears in three lists, so the new module is added
beside it in all three:

- the `GUARD_FILES` array in `tools/ci/agent-protection/verify-exfiltration-guard.sh`,
- the `GUARD_FILES` array in `tools/ci/agent-protection/test-verify-exfiltration-guard.sh`, and
- the `PROTECTED_FILES` array of the "Check for enforcement infrastructure tampering" step in
  `.github/workflows/analysis.yaml` (next to `test_exfiltration_guard_check.py`).

Missing the `analysis.yaml` entry would leave the new test module editable on a PR branch
without tripping the enforcement-tampering check, unlike every other guard test file.

Cases (from the task):

- `python3 - data.json <<'PY' … PY` with a clean body allows; with `import socket` blocks.
- `python3 -I - "$f" <<'PY' … PY` with a clean body allows.
- `python3 -c 'print(1)' data.txt` (no such file) allows.
- `python3 -c 'print(1)' -` allows: once inline code has fixed the program, the trailing `-`
  is a positional argument, not a second stdin program, so its heredoc/pipe is not scanned
  (and with `import socket` only in that stdin body, still allows — the body never runs as a
  program). Guards the Python immediate-return: the `-c` branch returns before the
  `tok == "-"` handler is reached.
- `python3 -c 'print(1)' -m http.server` allows: for Python, `-m http.server` after `-c` is
  `sys.argv`, not a module directive, so it is not refused as a network module. Guards that the
  Python return precedes the `-m` handler (Open question 2). `python3 -m http.server` with no
  `-c` still blocks (existing behaviour; add it so the distinction is asserted, not inferred).
- `perl -e 'print 1' -e 'print 2' file` allows, and with a network call in the *second* `-e`
  blocks (proves both programs are scanned).
- `perl -e 'print 1' ${more[@]}` (a shell-expanded positional token after inline code) blocks
  with the "expanded by the shell" message, not allowed. Guards the non-Python early return
  against a token that can expand at run time into a second `-e` program the guard cannot see;
  `python3 -c 'print(1)' "$rest"` still allows, since Python's remainder is unconditionally
  `sys.argv`.
- `python3 /nonexistent/script.py` still blocks (existing behaviour). The closest existing
  assertion is the option-value variant `python3 -W error /nonexistent/script.py` in
  `test_interpreter_options_with_a_value_are_not_the_program`; the new module adds the plain
  `python3 /nonexistent/script.py` case and the `python3 "$script"` variant (which blocks with
  the new "expanded by the shell" message), so neither is left to inference.
- `python3 "$script"` blocks with the new "expanded by the shell" message.

No existing test changes.

## Success criteria

- The observed loop command and every case above produce the stated allow/block result.
- `python3 -m pytest .claude/hooks/lib/test_exfiltration_guard_interpreter.py -q` and the
  existing `test_exfiltration_guard_check.py` and `test_exfiltration_guard_transport.py`
  modules pass unchanged.
- `bash tools/ci/agent-protection/test-verify-exfiltration-guard.sh` passes.
- The guard stays present and registered on HEAD throughout; the `test-integrity-check`,
  CI-file lock and enforcement-tampering checks pass on the implementation branch.

## Dependencies

- **Landing branch.** `.claude/hooks/lib/` and `tools/ci/` are under the CI-file lock and the
  enforcement-tampering check, and the harness protects them from staging on other branches.
  This planning branch is `project/task-…`, so the implementation must run on a `ci/...`
  branch (the task says so explicitly). The person approving the plan needs to start the
  implementation on, or move it to, a `ci/...` branch; otherwise the edits cannot be staged.
- No other tracker task blocks this one.

## Open questions

1. **Heredoc-written script (optional item).** `cat > /abs/fix.py <<'PY' … PY; python3 -I
   /abs/fix.py target.md` is blocked because the file does not exist at scan time. The
   dispatcher has every heredoc body (it calls `_split_heredocs`, defined in
   `exfil_bash_lex.py`, and holds the resulting `bodies`) and the redirects of each simple
   command, so pairing a `>` target with its body and
   scanning that body when the interpreter's script path equals the target is feasible. It
   widens the change into the dispatcher and needs careful path normalisation (relative vs
   absolute, a `cd` between the write and the run). Fail-closed is defensible.
   **Recommendation:** leave it out of this change and file it as a follow-up task, so the bug
   fix lands small. If the reviewer wants it in, it is a second, separable phase.
2. **Options after an inline program (resolved).** For Python, everything after `-c CODE` is
   `sys.argv`, so `python3 -c 'x' -m http.server` must pass `-m http.server` as arguments rather
   than be refused as a network module — the same class of false positive this change targets.
   An earlier draft left this open; it is now resolved in Scope/Approach by having the Python
   family return `f"{prog}:inline"` as soon as inline code is scanned, before the `-m` handler.
   The resolution is deliberately Python-only: Python's `-c` unambiguously consumes the rest of
   argv, whereas returning early for interpreters that can carry repeated inline-code flags
   (Perl, Ruby) — or Node, or any interpreter whose grammar the guard does not model — would risk
   *not* scanning a later inline program — a fail-open bypass. So those families keep the continue-then-early-return
   path, and a residual fail-closed false positive remains for them (e.g. `perl -e 'x' -m Foo`
   if `Foo` ever named a modelled network module). None has been reported; over-blocking there is
   the safe direction. If a reviewer prefers the maximally conservative option, leaving Python in
   the fail-closed path too is a one-line change, at the cost of keeping the reported
   `python3 -c 'x' -m http.server` false positive.

   The same fail-open asymmetry governs a shell-expanded positional token after inline code: for
   Python the remainder is unconditionally `sys.argv`, so `python3 -c 'x' "$rest"` is safe to
   allow, but for the other families `${more[@]}` could expand into a further `-e` program, so the
   non-Python early return refuses a shell-expanded token (Scope/Approach item 2) rather than
   return past it. Keeping the fail-closed direction there mirrors the `-m` decision above.

## Estimated complexity

Small. About 15–20 lines changed in one function — a Python-family immediate return in the
inline-code branch, the `code_seen` early return above `tok == "-"` for the other families
(itself guarding shell-expanded positional tokens), the `-` stdin return, and the
unresolvable-token guard — reusing the existing `is_shell_expanded` predicate at both of its loop
sites (one added import, no new predicate), one new test module, and three one-line list
additions (two `GUARD_FILES` arrays and the `analysis.yaml` `PROTECTED_FILES` array).
