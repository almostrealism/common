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
2. After an inline-code flag: keep consuming further inline-code flags (and the dash-led
   options between them, which the loop already handles), so `perl -e 'a' -e 'b' file` scans
   both programs; at the first positional token, return `f"{prog}:inline"` without reading it.
   Once inline code has fixed the program, a `-` is a positional argument too (it is
   `sys.argv`), so the early return must preempt the `tok == "-"` branch; otherwise
   `python3 -c 'print(1)' -` would scan stdin as a second program.
3. A positional token that contains `$` or a backtick, reached while the program is still
   unknown (`python3 "$script"`), blocks with a message that says what it is — an argument the
   guard cannot resolve to a file — instead of a `FileNotFoundError` on the literal text. Once
   the program is known, such a token is just an argument and passes (covered by items 1–2).
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
- **Inline program.** After the inline-code branch sets `code_seen`, the loop continues so
  further code flags and dash-led options are still processed (they may be more `-e` programs,
  preloads, or `-m`, which must still be checked). The early return goes *after* those dash-led
  handlers (so repeated `-e` and a trailing `-m` are still checked — see Open question 2) but
  *before* the `tok == "-"` branch: add, just above `if tok == "-":`, a check that returns
  `f"{prog}:inline"` when `code_seen`. Placing it before `tok == "-"` is what makes a `-` after
  inline code read as a positional argument rather than a second stdin program
  (`python3 -c 'print(1)' -`). The awk branch keeps its own `if not code_seen` scan of the first
  positional; with the early return above it, that branch is only reached when no inline program
  has been seen.
- **Unresolvable token.** The predicate already exists: `is_shell_expanded(token)` in
  `exfil_bash_lex.py` returns `"$" in token or "`" in token`, and is imported and used by the
  dispatcher and VCS checks. `exfil_bash_runner.py` already imports from that module
  (`SHELLS`, `GuardError`, `match_flag`); add `is_shell_expanded` to that import and use it in
  both `_resolve_dir` (replacing its inline `"$" in target or "`" in target`) and the
  interpreter loop. Do not introduce a second predicate in `exfil_bash_runner.py` — that would
  duplicate the shared helper and let the two definitions drift. In the loop, when the program
  is unknown and the positional token satisfies it, raise
  `_GuardError(f"{prog} script {tok!r} is expanded by the shell, so the guard cannot read it to scan it; a program the guard cannot read does not run")`
  before calling `_read_script`. The fail-closed outcome is unchanged; only the message is.
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
  program). Guards the early-return-before-`tok == "-"` placement.
- `perl -e 'print 1' -e 'print 2' file` allows, and with a network call in the *second* `-e`
  blocks (proves both programs are scanned).
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
2. **Options after an inline program.** For Python, everything after `-c CODE` is
   `sys.argv`, so `python3 -c 'x' -m http.server` passes `-m http.server` as arguments, but
   the guard will still refuse it as a network module. That is a fail-closed false positive
   no one has reported; the plan keeps processing dash-led tokens after inline code (needed
   for perl/ruby's repeated `-e`) and does not special-case Python. Confirm that is acceptable.

## Estimated complexity

Small. About 15 lines changed in one function, reusing the existing `is_shell_expanded`
predicate (one added import, no new predicate), one new test module, and three one-line list
additions (two `GUARD_FILES` arrays and the `analysis.yaml` `PROTECTED_FILES` array).
