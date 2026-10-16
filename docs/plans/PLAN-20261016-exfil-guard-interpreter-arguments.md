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
2. After an inline-code flag, handling splits by interpreter family, and the split follows each
   family's actual grammar rather than the shared `_INTERPRETER_CODE_FLAGS` set. That set
   (`-c`, `-e`, `-E`, `-p`, `-r`, `-C`, `--eval`, `--print`, …) is the union over every
   interpreter, so for any one family most of its members are either not options at all or
   options that take **no** argument. For the four families with an option table, the
   inline-code match therefore uses a new per-family table of the flags that actually take a
   program as their value:

   | Family | Inline-code flags | Shared-set members that are argument-less for it |
   |--------|-------------------|--------------------------------------------------|
   | `python` | `-c` | `-E` (environment isolation) |
   | `perl` | `-e`, `-E` (plus `-M…`, handled separately below) | `-p`, `-c`, `-C` |
   | `ruby` | `-e` | `-p`, `-c` (`-E`, `-C`, `-r` are already taken by its value table, `-C` handler and preload table) |
   | `node` | `-e`, `--eval`, `-p`, `--print`, `-pe` | `-c` (`--check`) (`-r`, `-C` are already taken by its preload and value tables) |

   Node needs one more rule. `-pe` is Node's own alias for `--print --eval`, whose program is the
   *next* token; it is not `-p` with the attached value `e`. Today's attached-form matching
   (enabled for every family with a table) reads it the second way, scans the one-letter "code"
   `e`, and then — harmlessly today, fatally once an early return exists — treats the real
   program as the following operand. So the Node inline-code match recognises `-pe` as a whole
   token and takes `-e`/`-p` values only as the next token or, for the long forms, after `=`
   (`--eval=…`, `--print=…`); it does not split an attached short value.

   `match_flag` cannot express that rule in one call: `allow_attached=False` rejects the
   `--eval=…` form as well as attached short values, and `allow_attached=True` splits `-pe` and
   `-eCODE`. An implementation that simply turns attached matching off for Node would let
   `node --eval='fetch("http://e")'` miss every inline flag, reach the `tok.startswith("-")` skip,
   and run unscanned — a fail-open (today the shared-set match, with attached matching on, does
   catch `--eval=…`). No matcher change is needed; the Node table is matched in two calls:
   `match_flag(tok, {"-e", "-p", "-pe"}, allow_attached=False)` for the exact short tokens, then
   `match_flag(tok, {"--eval", "--print"}, allow_attached=True)`, whose attached branch for a
   `--`-prefixed flag accepts only the `=` form. The implementer should confirm Node's
   short-option grammar against its option table before relying on this. Perl and Ruby genuinely
   accept an attached value (`perl -e'print 1'`), so their `-e`/`-E` keep attached matching; a
   switch cluster such as `-pe` or `-lne` no longer matches any of their inline flags (it does
   not start with `-e`/`-E`), so it is skipped as an ordinary option and the code token after it
   is read as a script by `_read_script`. That read fails closed — and the command blocks —
   *whenever the code token does not name a readable file*, which is the normal case, because the
   token is interpreter source (`'use Socket; …'`), not a path. It is important to be precise
   about what that block is and is not: the guard never scans the cluster's actual inline program,
   so the block is a property of the operand being unreadable, not of the guard recognising the
   cluster. If that operand ever did name a readable, clean file, the guard would scan the *file*,
   return, and let the real inline program run unscanned — a residual fail-open inherent to not
   parsing clusters. This is the same outcome as today, where `-pe` matches `-p` with the attached
   value `e` and the operand is likewise read as a script; Open question 4 records the residual
   and the two ways to close it.

   A shared-set member that is not in the family's table is an ordinary dash option that takes
   no value, so the operand after it is still read and scanned as the script (item 3 and Open
   question 3). Families with no table (`family is None`) keep matching the shared set exactly as
   today.
   - **Python.** Python's only inline-code flag is `-c`, and `-c` turns the whole remainder of the
     command line into `sys.argv`, so once it has been scanned no later token is an option: the
     Python family returns `f"{prog}:inline"` immediately — but *only when the matched flag is
     `-c`* — before the `-m`, `-`, positional, or awk handlers run. That is what makes
     `python3 -c 'print(1)' data.txt`, `python3 -c 'print(1)' -`, and `python3 -c 'x' -m http.server`
     all allow — the program is fixed and the rest is data. The gate on `-c` matters because the
     shared `_INTERPRETER_CODE_FLAGS` set also contains `-E`, which for Python is the
     environment-isolation option (it takes *no* argument), not inline code; so the Python
     inline-code match must be narrowed to `-c` alone (item 3 and Open question 3).
   - **Perl, Ruby and Node.** An *inline-code* flag from the family's table above (`-e`/`-E` for
     Perl, `-e` for Ruby, `-e`/`--eval`/`-p`/`--print`/`-pe` for Node) puts everything after it into the
     program's argument vector (`@ARGV` / `process.argv`), so a trailing operand is data, never a
     script. They also accept *repeated* inline-code flags
     (`perl -e 'a' -e 'b' file` scans both programs), so the loop keeps going after the first
     inline flag and an *inline-program-seen* early return just above the `tok == "-"` branch ends
     the walk at the first positional (or `-`) without reading it. That early return must key off
     an actual inline-code flag, **not** Perl's `-M`: `-M` loads a module and leaves the following
     operand a script that still runs (`perl -MJSON::PP script.pl` executes `script.pl`), so after
     a bare `-M` the first positional is a program and must still be read and scanned, exactly as
     today. For the same reason it must key off the *family* table, not a match against the shared
     set: matched against the shared set, Perl's argument-less `-p` would count as inline code, and
     because attached matching is enabled for these families `perl -pe 'use Socket; …' file` reads
     as `-p` with the value `e` — the early return would then end the walk at `'use Socket; …'`
     and the real program would never be scanned. There is one exception on the inline-program
     path: a token the shell can expand into other words is *refused* there, not returned past and
     not skipped, because an expansion can introduce a further `-e` and its code. This holds whether
     the token reads as a positional, as a dash option, or as the operand a value-flag or
     preload-flag consumes. A positional expansion
     (`perl -e 'print 1' ${more[@]}`, or `perl -e 'print 1' *`): `${more[@]}` can expand at run time
     into a further `-e` and its code, and `*` into script or data operands the guard cannot
     predict. A *dash-prefixed* expansion (`perl -e 'print 1' "-$opt"` with `opt=e`) is the same
     hazard in a different shape: `-$opt` is one token to the guard — matching no inline-code flag,
     since `match_flag` cannot see through the `$` — but expands at run time into a second `-e`
     whose program is the *following* token. A dash-prefixed expanded token must be caught
     explicitly, because it would otherwise reach the generic `tok.startswith("-")` skip that sits
     *below* the early return and be dropped silently, after which the real program (the next token)
     is returned past unscanned. A *value-flag or preload-flag operand* is a third shape, caught at
     a different site again. The value-flag handler (Perl's/Ruby's `-I`, Ruby's `-E`/`-F`, Node's
     value flags), the preload-flag handler (`-r`/`--require`/`--import`/`--loader` for Node, `-r`
     for Ruby) and Ruby's `-C` working-directory handler each consume their operand — the attached
     value, or the *following* token — and `continue`, and all three sit *above* the inline-program
     early return, so an expanded operand there never reaches the early return's per-token test.
     `perl -e 'print 1' -I ${more[@]}`, where `${more[@]}` word-splits into a further `-e` and its
     code, is the case: `-I` swallows `${more[@]}` as its value and the second `-e` is never seen.
     So once an inline program has been seen, those operand-consuming handlers must apply the same
     expansion test to the operand they are about to consume and refuse it, rather than swallow it
     unscanned. The expansion test (`is_shell_expanded(tok)` or
     `is_glob_or_brace(tok)`) is therefore consulted at two kinds of site: for *every* token on the
     inline-program early-return path, before the `tok.startswith("-")`
     skip, not only for positionals — a plain positional (or `-`) returns `f"{prog}:inline"`, an
     expanded token of either shape is refused, and an ordinary dash option that is not expanded
     still falls through to the skip as today — *and*, once `inline_seen` is set, on the operand
     each value-flag, preload-flag and Ruby `-C` handler is about to consume, before that operand
     is swallowed. Gating the operand check on `inline_seen` keeps the common pre-inline idiom
     (`perl -I ${inc[@]} script.pl`, an include-path list) passing as it does today; the residual
     this leaves — an expansion consumed as a value/preload operand *before* any inline program is
     detected — is pre-existing on master and recorded in Open question 6. A second program
     smuggled through an expansion — positional, dash-shaped, or hidden in a consumed operand — is
     never silently skipped once an inline program has been seen.
   - **Interpreters the guard has no option grammar for** (`php`, `lua`, `luajit`, `tclsh`,
     `Rscript`, …). These do *not* get the early return, because for some of them a trailing
     operand after inline code is still a program: `lua -e 'print(1)' script.lua` runs both the
     `-e` chunk and `script.lua`. So these keep today's behaviour — the loop falls through to
     `_read_script` on the first positional and scans it as a script. For a grammar-less
     interpreter whose operand is in fact data (`php -r 'code' data`), that is a residual
     over-block in the fail-closed direction; none has been reported, and reading the operand is
     the only safe default when the guard cannot model the grammar (Open question 2).

   The only reason Python is singled out for the immediate return is that its `-c` grammar is
   unambiguous enough — the remainder is unconditionally `sys.argv`, expansions included — to
   return early safely; every other path stays fail-closed.

   **Stdin is still inspected after an inline program.** Fixing the program to an inline flag
   does not make the command's standard input inert: an inline program can read and execute its
   own stdin (`python3 -c 'exec(sys.stdin.read())'`, `perl -e 'eval <STDIN>'`), so a heredoc or
   pipe attached to the command is potential program text, not data. The command's stdin must
   therefore be inspected whenever an inline program has been seen, exactly as
   `_check_stdin_program` already does for a non-shell interpreter: each heredoc body in
   `bodies` is scanned as code (`_scan_code`), and a pipe (`piped`) is refused. Without it,
   `python3 -c 'exec(sys.stdin.read())' - <<'PY'` / `import socket` / `PY` would run the heredoc
   unscanned — the bypass a reviewer raised on PR #659. For the `-` token specifically this is
   also a regression guard: today the `tok == "-"` branch reaches `_check_stdin_program` and
   scans that heredoc, so an early return that simply dropped it would be a fail-open relative to
   master. Three sites carry this inspection, because the two inline families reach it differently.
   The Python `-c` branch *returns immediately* at the match, so it inspects stdin there and the
   no-`-` form `python3 -c 'exec(sys.stdin.read())' <<'PY'` is covered by that one return. The
   Perl/Ruby/Node branch instead `continue`s (to allow repeated `-e`), so it inspects stdin at
   two points: the `inline_seen` early return, reached when a later token follows the inline
   program, *and* the end-of-loop check, reached when the inline program is the command's last
   token. The latter is the case a reviewer raised on PR #659 (`perl -e 'eval <STDIN>' <<'PL'` / `use Socket;` / `PL`, and the piped `cat x | perl -e 'eval <STDIN>'`): the loop falls through to
   the trailing stdin check, which on master is `if not code_seen:` and is therefore skipped once
   an inline program has set `code_seen`, leaving the heredoc or pipe unscanned. So that trailing
   check must be broadened to fire on `inline_seen` as well — `if inline_seen or not code_seen:` —
   not left unchanged. With that, the inspection closes two pre-existing fail-opens on the no-`-`
   forms, where `code_seen` suppresses the trailing stdin check today:
   `python3 -c 'exec(sys.stdin.read())' <<'PY'` (heredoc, closed by the Python immediate return)
   and `cat x | perl -e 'eval <STDIN>'` (pipe, closed by the broadened end-of-loop check) are
   unscanned on master. Refusing the pipe does over-block a piped inline one-liner that never reads stdin
   (`cat x | python3 -c 'print(1)'`); that cost, and the alternative of scanning only visible
   heredoc bodies while leaving pipes as today (which reintroduces the `-`-token pipe fail-open),
   are recorded in Open question 5 for the approver. This inspection does not read `-` as a
   script file and does not shell-analyze it; only the command's raw stdin is scanned, as code.

   The guarantee is bounded by what the guard can see of the command's stdin, which is a pipe
   (`piped`) and a heredoc body (`bodies`) — and *only* those. `_simple_commands` in
   `exfil_bash_lex.py` records a pipe only for `|`/`|&` and a body only for a `<<WORD` heredoc; an
   ordinary input redirection (`< payload.py`) and a here-string (`<<< 'import socket'`) are in
   `_REDIRECT_TOKENS` but not `_OUTPUT_REDIRECT_TOKENS`, so the walk skips the operator and its
   target (`i += 2`) and neither reaches `_check_interpreter`. So an inline program that reads and
   executes a *redirected file* or a *here-string* —
   `python3 -c 'exec(sys.stdin.read())' < payload.py`, `python3 -c 'exec(sys.stdin.read())' <<< 'import socket'` —
   is **not** inspected by this change, and returns `python3:inline` with the payload unscanned.
   This is a pre-existing gap, not a regression: on master the same commands set `code_seen` and
   reach the end of the walk with the trailing `if not code_seen:` suppressed, so the redirected
   file / here-string is never read there either. Closing it means teaching `_simple_commands` to
   expose input-redirection targets and here-string words and threading them to
   `_check_stdin_program` (reading `< file` as a script to scan, refusing or scanning `<<<`), which
   widens the change into the lexer and the dispatcher. This plan scopes that out and narrows its
   stated guarantee to pipes and heredocs; the input-redirection gap is recorded in Open
   question 5 as a follow-up for the approver.
3. A positional token the shell can expand into other words — one that contains `$` or a
   backtick (`is_shell_expanded`), or an unquoted glob/brace metacharacter (`is_glob_or_brace`:
   `*`, `?`, `[`, `{a,b}`) — reached while the program is still unknown (`python3 "$script"`,
   `python3 *.py`), blocks with a message that says what it is — an argument the guard cannot
   resolve to a file — instead of a `FileNotFoundError` on the literal text. Once the program is
   a resolved script path or Python's `-c`, such a token is just data and passes (covered by
   items 1–2); the one place it still blocks is the Perl/Ruby/Node inline-code early-return path,
   where it could expand into a further `-e` program (item 2). The Python family's only inline-code
   flag is `-c`; `-E` is Python's environment-isolation option and takes no argument, so
   `python3 -E script.py` must leave `script.py` to be read and scanned as the script. Today it
   does not: the shared-set match consumes `script.py` as `-E`'s value, so the script is never
   read — a pre-existing fail-open this change closes (Open question 3).
4. Tests for all of the above (see Success criteria).

Optional, lower priority (the task's "Related" item; see Open questions): scanning a heredoc
body written to a script path earlier in the same command, instead of failing the read of a
file that does not exist yet at scan time.

Out of scope: the shell branch (`_check_shell`, `bash "$f"`), awk's `-f progfile`, and any
change to which modules or code count as network-capable.

## Approach

All production changes are in `exfil_bash_runner.py`: the per-family inline-code table from
Scope item 2 (a module-level dict beside `_INTERPRETER_VALUE_FLAGS` and
`_INTERPRETER_PRELOAD_FLAGS`, keyed the same way), and the loop in `_check_interpreter`, which
matches that table for the four families with one and the shared `_INTERPRETER_CODE_FLAGS` set
only for `family is None`. No existing test exercises Perl/Ruby `-p`/`-c` or Node `-p`/`-pe`
(Ruby's `-C` tests go through its dedicated working-directory handler, which runs first), so the
narrowing changes no current assertion.

- **Stdin program.** In the `tok == "-"` branch, after `_check_stdin_program(...)`, return
  `f"{prog}:stdin"` instead of `continue`. This matches the label `_check_shell` already uses
  for a stdin program. The trailing end-of-loop stdin check is *not* left unchanged: its
  condition is broadened from `if not code_seen:` to `if inline_seen or not code_seen:`, so that
  a Perl/Ruby/Node inline program that is the command's last token (and so never reaches the
  `inline_seen` early return) still has its stdin inspected — see "Stdin after inline code" below.
- **Inline program.** Three cases, by interpreter family.
  - **Python** (family `python`: `python`, `python2`, `python3`, `pypy`, `pypy3`). `-c` makes
    everything after it `sys.argv`, so nothing that follows is an option. In the inline-code
    branch, when `family == "python"` *and the matched flag is `-c`*, inspect the command's stdin
    (`_check_stdin_program(...)` when `piped`/`bodies` is present; see "Stdin after inline code"
    below) and then return `f"{prog}:inline"` as soon as `_scan_code(...)` has run — before the
    `-m` handler, the `tok == "-"` branch, and the positional/awk handlers. This is what stops
    `python3 -c 'x' -m http.server` from being refused as a network module and `python3 -c 'print(1)' -` from reading the trailing `-` as a second *program* (a script file or a stdin-program
    selection): both tokens are arguments to the program `-c` already fixed. The command's stdin
    is still scanned, because the `-c` code may read and execute it. `python3 -m http.server`
    with no preceding `-c` is unaffected — `code_seen` is false there, so the `-m` handler is
    still reached and still blocks. The gate on `-c` is load-bearing: the shared
    `_INTERPRETER_CODE_FLAGS` set contains `-E`, but Python's `-E` is the environment-isolation
    option and takes no argument, so the Python code-flag match must recognise `-c` only. Today's
    loop already matches `-E` against the shared set, so `python3 -E script.py` consumes
    `script.py` as `-E`'s "code", scans only the literal file name, and never reads the script —
    a pre-existing fail-open, like the Perl `-p` case below, that the per-family table closes.
    With an immediate return added and the match left on the shared set, the same command would
    also return `python3:inline` (Open question 3). For the Python family, treat `-E` (and the
    other non-`-c` members of the shared set) as an ordinary dash option that takes no value, so
    the following `script.py` falls through to `_read_script` and is scanned.
  - **Perl, Ruby and Node** (families `perl`, `ruby`, `node`). An inline-code flag from the
    family's table in Scope item 2 (`-e`/`-E` for Perl, `-e` for Ruby, `-e`/`--eval`/`-p`/
    `--print`/`-pe` for Node — matched with the per-family table, never the shared set, and for
    Node without splitting an attached short value but still accepting `--eval=…`/`--print=…`,
    per the two-call match in Scope item 2) puts everything after it into the program's
    argument vector, so
    a trailing operand is data, and these families may carry repeated inline-code flags
    (`perl -e 'a' -e 'b'`), so the loop must keep going after the first one: it `continue`s,
    scanning each further inline program. The shared-set members that are argument-less for the
    family (Perl `-p`/`-c`/`-C`, Ruby `-p`/`-c`, Node `-c`) no longer enter the inline-code branch
    at all; they reach the `tok.startswith("-")` skip, so the operand after them is read by
    `_read_script` as the script. That also closes a pre-existing fail-open: today
    `perl -p script.pl` (and `ruby -p script.rb`, `perl -c script.pl`, whose `BEGIN` blocks run)
    consumes `script.pl` as `-p`'s "code", scans only the literal file name, and never reads the
    script. Alongside the existing `code_seen` (which it still
    sets, to gate the trailing stdin scan), the inline-code branch must record that an actual
    inline *program* was seen in a **new, separate** state — call it `inline_seen` — because
    `code_seen` is also set by Perl's `-M` branch (which shares the inline branch today), and `-M`
    does *not* turn the following operand into argv data. An `inline_seen`
    early return added just above `if tok == "-":` then ends the walk at the first positional token
    (or `-`) without reading it as a program — but first inspects the command's stdin
    (`_check_stdin_program(...)` when `piped`/`bodies` is present; see "Stdin after inline code"
    below), because a Perl/Ruby/Node inline program can likewise read and execute its stdin. That
    early return only fires when a later token follows the inline program; when the inline flag is
    the command's *last* token the loop falls through to the end, so the end-of-loop stdin check
    is broadened to fire on `inline_seen` too (again, see "Stdin after inline code" below), rather
    than leaving an inline program's stdin unscanned the way master's `if not code_seen:` does.
    Keying it off `inline_seen` rather than `code_seen` is
    load-bearing: `perl -MJSON::PP script.pl` sets `code_seen` through the `-M` branch but never
    sets `inline_seen`, so `script.pl` falls through to `_read_script` and is scanned as the
    program it is — returning `perl:inline` there would be a fail-open regression, since today that
    operand is read and scanned. The `-M` path is otherwise unchanged. That early return is also conditional on
    the token, and its condition is tested for *every* token it reaches — dash-prefixed or not,
    ahead of the generic `tok.startswith("-")` skip below it: a plain positional (or `-`) returns
    `f"{prog}:inline"`; a token the shell can expand into
    other words — `is_shell_expanded(tok)` (`$`, backtick) *or* `is_glob_or_brace(tok)` (`*`,
    `?`, `[`, `{a,b}`) — raises the same "expanded by the shell" `_GuardError` as the
    unknown-program case below rather than returning past it; an ordinary, unexpanded dash option
    falls through to the skip, exactly as today. The expansion refusal is because such a token can
    expand at run time into words the guard cannot predict, including a further inline-code flag
    or a script operand: `perl -e 'print 1' ${more[@]}` with `more=(-e 'use Socket; …')` is one
    positional token to the guard but a second `-e` program to Perl, `perl -e 'print 1' *`
    expands to whatever files the directory holds, and `perl -e 'print 1' "-$opt"` with `opt=e`
    is a single *dash-prefixed* token that matches no inline-code flag — `match_flag` cannot see
    through the `$` — yet expands into a second `-e` whose program is the *following* token.
    The dash-prefixed shape is why the expansion test must run before the `tok.startswith("-")`
    skip rather than only on positionals: a `-$opt` left to that skip is dropped silently, after
    which the real program (the next token) hits the positional early return and is returned past
    unscanned — the fail-open a reviewer raised on PR #659. Returning early on an expanded token of
    either shape would skip scanning
    what the token becomes — a fail-open regression from today's behaviour, where the single
    literal token reaches `_read_script` and fails closed on the unreadable name. Placing the early
    return before `tok == "-"` is what makes a `-` after inline code read as a positional
    argument rather than a second stdin-*program* selection — its stdin is still scanned by the
    `_check_stdin_program` call the early return makes first, just not re-interpreted as the
    program. For these families the `-m` handler still runs
    after inline code, so `perl -e 'x' -m Foo`-style tokens stay fail-closed (see Open
    question 2); no such case has been reported.

    The early return's per-token expansion test is *not* the whole story, because the
    operand-consuming handlers run above it and `continue` before it is reached. The value-flag
    handler (`_match_flag(tok, value_flags)`), the preload-flag handler
    (`_match_flag(tok, preload_flags)`) and Ruby's `-C` handler each take their operand from the
    attached value or the following token and loop on, so an *expanded* operand there — `-I
    ${more[@]}`, `-r ${more[@]}`, `-C ${more[@]}` — is swallowed unscanned and never reaches the
    early return. `perl -e 'print 1' -I ${more[@]}` with `more=(safe -e 'use Socket; …')` is the
    reviewer's case: `-I` consumes `${more[@]}`, which the shell word-splits into `safe` (an
    include dir) plus a second `-e 'use Socket; …'` program the guard never sees. So, once
    `inline_seen` is set, each of these three handlers must apply the same
    `is_shell_expanded(operand) or is_glob_or_brace(operand)` test to the operand it is about to
    consume — the attached value or `args[i + 1]` — and raise the same "expanded by the shell"
    `_GuardError` instead of consuming it. The test is gated on `inline_seen` so the common
    pre-inline include-list idiom (`perl -I ${inc[@]} script.pl`) keeps passing exactly as today;
    the residual it leaves — an expansion consumed as a value/preload operand *before* any inline
    program has been seen — is pre-existing on master and recorded in Open question 6, not closed
    here. A clean operand (`perl -e 'print 1' -I lib`) is consumed as data and passes.
  - **Interpreters with no option grammar** (`php`, `lua`, `luajit`, `tclsh`, `Rscript`, `julia`,
    `groovy`, …; `family is None`). These do *not* get the early return. For some of them the
    first operand after an inline-code flag is still a program — `lua -e 'print(1)' script.lua`
    runs the `-e` chunk *and* `script.lua` — so the loop falls through to `_read_script` on the
    first positional and scans it as a script, exactly as today. This keeps Lua/luajit
    fail-closed (the script operand is read and scanned); where a grammar-less interpreter's
    operand is in fact data (`php -r 'code' data`), reading it is a residual over-block in the
    safe direction, since the guard cannot model the grammar to know otherwise (Open question 2).
  - **Stdin after inline code.** Every path that fixes the program to an inline flag must inspect
    the command's stdin before it finishes, because the inline program can read and execute it.
    Reuse `_check_stdin_program(prog, piped, bodies, ctx, depth, analyze_command)` for this: it
    refuses a pipe and scans each heredoc body with `_scan_code` for a non-shell interpreter,
    which is exactly the conservative treatment wanted here. Call it only when `piped` or `bodies`
    is non-empty so the common stdin-less one-liner is untouched. There are three such sites, not
    two, because the Python and Perl/Ruby/Node families reach the end of the walk differently:
    - The **Python `-c` immediate return** inspects stdin and then returns at the match, so it
      covers both `python3 -c 'exec(sys.stdin.read())' - <<…` (trailing `-`) and the no-`-`
      `python3 -c 'exec(sys.stdin.read())' <<…`.
    - The **Perl/Ruby/Node `inline_seen` early return** inspects stdin before returning, covering
      the form where a later token (positional or `-`) follows the inline program —
      `perl -e 'eval <STDIN>' - <<…`.
    - The **end-of-loop check** covers the Perl/Ruby/Node form where the inline flag is the
      command's *last* token (`perl -e 'eval <STDIN>' <<…`, `cat x | perl -e 'eval <STDIN>'`):
      the loop never reaches the `inline_seen` early return, so it falls through to the trailing
      stdin check. On master that check is `if not code_seen:`, which an inline program's
      `code_seen` suppresses; it must be broadened to `if inline_seen or not code_seen:` so the
      inline program's stdin is still inspected. (Python never reaches here — it returned at the
      `-c` match — and `perl -M…` alone without an inline flag keeps master's behaviour, since it
      sets `code_seen` but not `inline_seen`.)
    Together these preserve today's scan of a heredoc on the `tok == "-"` path (which the early
    return would otherwise skip) and close the pre-existing no-`-` gaps where `code_seen`
    suppresses the trailing stdin check. The one new over-block is a piped inline one-liner that
    never reads stdin (Open question 5). This reuses the same helper the `tok == "-"` branch and
    the trailing end-of-loop check already call, so no new stdin-scanning logic is introduced. `_check_stdin_program` sees only what `_simple_commands`
    threads through — a pipe and heredoc bodies — so an inline program fed by an input redirection
    (`< payload.py`) or a here-string (`<<< 'import socket'`) is outside this inspection: those
    targets are dropped by the lexer and never reach the runner. That bound is pre-existing (master
    does not scan them either) and is recorded in Open question 5 as a follow-up rather than closed
    here, which would require exposing input redirections from `_simple_commands`.
  The awk branch keeps its own `if not code_seen` scan of the first positional; with the early
  return above it (and the Python immediate return), it is reached only when no inline program
  has been seen.
- **Unresolvable token.** The predicates already exist in `exfil_bash_lex.py`:
  `is_shell_expanded(token)` returns `"$" in token or "`" in token`, and `is_glob_or_brace(token)`
  matches an unquoted `*`, `?`, `[` class or `{a,b}`/`{m..n}` brace list; both are imported and
  used by the dispatcher and VCS/destination checks. `exfil_bash_runner.py` already imports from
  that module (`SHELLS`, `GuardError`, `match_flag`); add `is_shell_expanded` and
  `is_glob_or_brace` to that import and use them in both `_resolve_dir` (replacing its inline
  `"$" in target or "`" in target`, so a glob destination is treated as unresolvable there too)
  and the interpreter loop. Do not introduce a second predicate in `exfil_bash_runner.py` — that
  would duplicate the shared helpers and let the definitions drift. The "shell can expand this
  into other words" test — `is_shell_expanded(tok) or is_glob_or_brace(tok)` — is consulted at
  three points in the loop, all fail-closed:
  - When the program is still unknown and a positional token satisfies it (`python3 "$script"`,
    `python3 *.py`), raise
    `_GuardError(f"{prog} script {tok!r} is expanded by the shell, so the guard cannot read it to scan it; a program the guard cannot read does not run")`
    before calling `_read_script`. The fail-closed outcome is unchanged from today (the token
    would reach `_read_script` and fail on the unreadable name); only the message is.
  - On the Perl/Ruby/Node `inline_seen` early-return path, such a token raises the same error
    instead of returning `f"{prog}:inline"` — see the inline-program bullet above. Here the
    outcome *does* matter: returning early would let a token that expands into a further `-e`
    program (`${more[@]}`, `*`) slip past unscanned, so the guard must refuse it. The test applies
    to dash-prefixed expanded tokens too (`-$opt` → `-e`), which is why it runs ahead of the
    generic `tok.startswith("-")` skip and not only on positionals: left to that skip, `-$opt` is
    dropped silently and the real program that follows it is returned past unscanned.
  - On the Perl/Ruby/Node value-flag, preload-flag and Ruby `-C` handlers, once `inline_seen` is
    set, the operand each is about to consume (the attached value or `args[i + 1]`) raises the same
    error instead of being swallowed — see the inline-program bullet above. These handlers sit
    *above* the `inline_seen` early return and `continue` before it, so without this check an
    expanded value/preload operand (`perl -e 'print 1' -I ${more[@]}`) that word-splits into a
    further `-e` would be consumed unscanned and never reach the per-token test. The check is gated
    on `inline_seen` so a pre-inline include-list (`perl -I ${inc[@]} script.pl`) is unaffected;
    the pre-inline residual is Open question 6.
  Python's immediate return after `-c` needs no such check on the following tokens: its remainder
  is unconditionally `sys.argv`, so an expansion there is data, never a second program. (A
  shell-expanded `-c` *code value* is a separate, pre-existing limitation — Open question 3.)
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

Cases (from the task). Where a case says a body or script "contains a network call", the call
must be one that `NETWORK_CODE_PATTERNS` in `exfil_bash_network.py` actually matches, such as
`import socket` or `fetch("http://e")` as in the existing tests. A plausible-looking payload
that matches no pattern, for example `require("net").connect(1)`, is allowed whatever the
argument walk does, so a "blocks" case built on it would test nothing.

- `python3 - data.json <<'PY' … PY` with a clean body allows; with `import socket` blocks.
- `python3 -I - "$f" <<'PY' … PY` with a clean body allows.
- `python3 -c 'print(1)' data.txt` (no such file) allows.
- `python3 -c 'print(1)' -` with no heredoc or pipe allows: once inline code has fixed the
  program, the trailing `-` is a positional argument, not a second stdin program, so it is not
  read as a script file. Guards the Python immediate-return: the `-c` branch returns before the
  `tok == "-"` handler is reached, so `-` is never read as a script.
- `python3 -c 'exec(sys.stdin.read())' - <<'PY'` / `import socket` / `PY` blocks, and the same
  command with a clean heredoc body allows. An inline program can read and execute stdin, so the
  command's heredoc body is still scanned as code before the Python immediate return — not
  dropped as if `-` made it inert. Guards that the early return inspects stdin (Scope item 2,
  "Stdin is still inspected after an inline program"); on master the `tok == "-"` branch scans
  this heredoc, so an early return that skipped it would be a fail-open regression.
- `python3 -c 'print(1)' -m http.server` allows: for Python, `-m http.server` after `-c` is
  `sys.argv`, not a module directive, so it is not refused as a network module. Guards that the
  Python return precedes the `-m` handler (Open question 2). `python3 -m http.server` with no
  `-c` still blocks (existing behaviour; add it so the distinction is asserted, not inferred).
- `perl -e 'print 1' -e 'print 2' file` allows, and with a network call in the *second* `-e`
  blocks (proves both programs are scanned).
- `perl -e 'eval <STDIN>' - <<'PL'` / `use Socket;` / `PL` blocks, and the same command with a
  clean heredoc body allows. Guards that the Perl/Ruby/Node `inline_seen` early return inspects
  the command's stdin too: an inline program can read and execute stdin, so the heredoc body is
  scanned as code before the early return rather than dropped behind the trailing `-`.
- `perl -e 'eval <STDIN>' <<'PL'` / `use Socket;` / `PL` (no trailing `-`, so the inline flag is
  the command's last token) blocks, and the same command with a clean heredoc body allows; the
  piped form `cat x | perl -e 'eval <STDIN>'` blocks too. Guards the end-of-loop stdin check:
  with the inline program last the loop never reaches the `inline_seen` early return, so the
  trailing check must fire on `inline_seen` (`if inline_seen or not code_seen:`) rather than be
  suppressed by `code_seen` as on master, where both forms run their stdin unscanned.
- `perl -e 'print 1' ${more[@]}` (a shell-expanded positional token after inline code) blocks
  with the "expanded by the shell" message, not allowed, and `perl -e 'print 1' *` (an unquoted
  glob token after inline code) blocks the same way. Both guard the Perl/Ruby/Node early return
  against a token that can expand at run time into a second `-e` program the guard cannot see;
  `python3 -c 'print(1)' "$rest"` still allows, since Python's remainder is unconditionally
  `sys.argv`.
- `perl -e 'print 1' "-$opt"` (a shell-expanded *dash-prefixed* token after inline code) blocks
  with the "expanded by the shell" message. Guards the dash-prefixed arm of the early return: the
  token matches no inline-code flag, so without the explicit expansion test it would reach the
  generic `tok.startswith("-")` skip, be dropped silently, and the following token (the real
  program when `-$opt` expands to `-e`) would be returned past unscanned. A plain, unexpanded dash
  option after inline code (`perl -e 'print 1' -w`) still allows — it is argv data, not an
  expansion — so the test discriminates on expandability, not merely on the leading dash.
- `perl -e 'print 1' -I ${more[@]}` (a shell-expanded operand consumed by the `-I` value flag
  after inline code) blocks with the "expanded by the shell" message, and `node -e 'x' -r
  ${more[@]}` (an expanded operand consumed by Node's `-r` preload flag after inline code) blocks
  the same way. Guards the value-flag/preload-flag arm of the expansion refusal: these handlers
  run *above* the `inline_seen` early return and consume their operand before it, so without the
  gated check `${more[@]}` — which can word-split into a second `-e`/`require` and its code — would
  be swallowed unscanned. A clean value operand after inline code (`perl -e 'print 1' -I lib`)
  still allows, since `lib` is an ordinary include directory, not an expansion. The gating on
  `inline_seen` is asserted by `perl -I ${inc[@]} script.pl` (no inline program yet): it is *not*
  blocked by this new check — it reaches `_read_script` on `script.pl` exactly as on master (the
  pre-inline residual, Open question 6) — so the change adds no new false positive to the common
  include-list idiom.
- `lua -e 'print(1)' script.lua` scans `script.lua` as a program, so a clean `script.lua` allows
  and one containing a network call blocks. Guards that grammar-less interpreters are *not* given
  the early return: Lua runs both the `-e` chunk and the script operand, so the operand must be
  read and scanned, not returned past as data.
- `perl -MJSON::PP script.pl` scans `script.pl` as a program, so a clean `script.pl` allows and
  one containing a network call blocks. Guards that the Perl/Ruby/Node early return keys off an
  actual inline-code flag (`inline_seen`), not Perl's `-M`: `-M` loads a module and leaves
  `script.pl` the program Perl runs, so it must still be read and scanned rather than returned
  past as argv data. (`perl -e 'print 1' script.pl` is the contrasting allow — there the operand
  *is* `@ARGV` data because `-e` is the program.)
- `perl -pe 'use Socket; …' file` blocks: the `-pe` cluster is not an inline flag for Perl, so
  the cluster's code token (`'use Socket; …'`) is not consumed as inline data (not returned past)
  but reaches `_read_script`, where the read of that non-path fails closed. This case proves the
  cluster is *not* skipped over as data; it does **not** prove the cluster's inline program is
  scanned — the block comes from the operand being unreadable, and if that operand named a
  readable clean file the real program would run unscanned (the residual fail-open in Open
  question 4). A regression that genuinely exercised scanning of a cluster's inline code would
  require parsing the cluster, which this change does not do. `perl -p /nonexistent/script.pl`
  and `ruby -p /nonexistent/script.rb` block because the script is read rather than consumed as
  `-p`'s value. These guard that the Perl/Ruby inline match uses the family table, not the shared
  set.
- `node -p 'process.argv[1]' data.txt`, `node --eval 'x' data.txt` and
  `node -pe 'process.argv[1]' data.txt` (no such file) allow, and `node -pe 'fetch("http://e")'`
  blocks — the real program after `-pe` is scanned, not skipped behind an attached `e`.
- `node --eval='fetch("http://e")'` and `node --print='fetch("http://e")'` block, and
  `node --eval='process.argv[1]' data.txt` (no such file) allows. Guards that the Node match keeps
  the long `=` forms while refusing attached short values (Scope item 2): a Node match made with
  attached matching simply turned off would skip these tokens as ordinary options and never scan
  the code.
- `python3 -E /nonexistent/script.py` blocks because the script is read, not consumed as `-E`'s
  value. This is a regression test for a fix, not a check of existing behaviour: today the
  command allows, because the shared-set match takes `/nonexistent/script.py` as `-E`'s "code".
  It guards that for the Python family `-E` is an argument-less option, not an inline-code flag,
  so the script after it is reached by `_read_script` (Open question 3). A `python3 -E script.py`
  whose readable script contains a network call blocks, and a clean one allows.
- `python3 /nonexistent/script.py` still blocks (existing behaviour). The closest existing
  assertion is the option-value variant `python3 -W error /nonexistent/script.py` in
  `test_interpreter_options_with_a_value_are_not_the_program`; the new module adds the plain
  `python3 /nonexistent/script.py` case and the `python3 "$script"` variant (which blocks with
  the new "expanded by the shell" message), so neither is left to inference.
- `python3 "$script"` and `python3 *.py` (with no matching file) block with the new "expanded by
  the shell" message, covering both the `is_shell_expanded` and `is_glob_or_brace` arms of the
  unknown-program guard.

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
   (Perl, Ruby) — or Node — would risk *not* scanning a later inline program — a fail-open bypass.
   So Perl/Ruby/Node keep the continue-then-early-return path, and a residual fail-closed false
   positive remains for them (e.g. `perl -e 'x' -m Foo` if `Foo` ever named a modelled network
   module). None has been reported; over-blocking there is the safe direction. If a reviewer
   prefers the maximally conservative option, leaving Python in the fail-closed path too is a
   one-line change, at the cost of keeping the reported `python3 -c 'x' -m http.server` false
   positive.

   Interpreters the guard has no option grammar for (`php`, `lua`, `luajit`, `tclsh`, `Rscript`,
   …) are treated more conservatively still: they get *no* early return and keep today's
   fall-through that reads the first operand as a script. This is necessary because for some of
   them the operand after inline code is a program, not data — `lua -e 'x' script.lua` runs
   `script.lua` — so an early return would skip scanning it (a fail-open the first drafts of this
   plan contained by lumping lua in with Perl/Ruby/Node). The cost is a residual over-block for a
   grammar-less interpreter whose operand really is data (`php -r 'code' data` re-reads `data` as
   a script); that is the safe direction and unreported. Modelling php/lua/… grammars precisely
   enough to distinguish their data operands from their script operands is a possible follow-up,
   not part of this change.

   The same fail-open asymmetry governs a positional token after inline code that the shell can
   expand into other words: for Python the remainder is unconditionally `sys.argv`, so
   `python3 -c 'x' "$rest"` is safe to allow, but for Perl/Ruby/Node `${more[@]}` or `*` could
   expand into a further `-e` program, so their early return refuses a token matched by
   `is_shell_expanded` *or* `is_glob_or_brace` (Scope/Approach item 2) rather than return past it.
   Keeping the fail-closed direction there mirrors the `-m` decision above.

3. **Shell-expanded inline *code value* (pre-existing, out of scope).** When the inline code
   itself is a shell expansion — `python3 -c "$CODE"`, `perl -e "$CODE"` — the guard scans the
   literal text `$CODE`, which carries no network pattern, and passes. The real code is produced
   by the shell at run time and the guard can never see it. This is a pre-existing property of
   the guard (today `python3 -c "$CODE"` with no operand already allows) and is independent of
   this change, whose subject is data arguments misread as *programs*, not the visibility of
   inline code. This plan deliberately does **not** reject shell-expanded code values: doing so
   would refuse legitimate dynamic one-liners (`python3 -c "$CODE"` is a common idiom) — a new
   class of false positive, the opposite of this change's goal — and belongs to a separate
   decision about how far the guard should fail closed on unscannable inline code. A reviewer
   raised this (PR #659); it is recorded here rather than actioned. Relatedly, the Python family's
   immediate return is gated on the matched flag being `-c`, because the shared
   `_INTERPRETER_CODE_FLAGS` set contains `-E` — Python's argument-less environment-isolation
   option. Today's shared-set match already treats `-E` as inline code, so `python3 -E script.py`
   consumes the script as `-E`'s value and never reads it — a pre-existing fail-open that the
   per-family table closes (Scope/Approach item 2, and the `python3 -E …` test above).

4. **Perl/Ruby switch clusters (pre-existing over-block, out of scope).** `perl -lne 'code' file`,
   `perl -pe 'code' file` and `ruby -ne 'code' file` are common one-liners. Today none of them is
   recognised as carrying inline code (`-lne` matches no code flag; `-pe` matches `-p` with the
   attached value `e`), so the cluster's code token is read as a script and the command blocks.
   This plan keeps that result — with the per-family tables a cluster still matches no inline flag
   — rather than parse clusters, because a cluster parser must know which Perl/Ruby switches take a
   value (`-l`, `-0`, `-i`, `-F`, `-C`, …) to find where the `e` sits, and a mistake there is a
   fail-open.

   A reviewer (PR #659) correctly observed that calling this result "fail-closed" is an
   overstatement, and the point is recorded here rather than papered over. The block is not
   produced by recognising the cluster; it is produced by `_read_script` failing on the token
   after the cluster. For a real one-liner that token is the Perl/Ruby source (`'use Socket; …'`),
   which is not a path, so the read fails and the command blocks — but the cluster's inline
   program is never actually scanned. If that token ever named a readable, clean file, the guard
   would scan the *file*, return, and let the real inline program run unscanned. That is a
   residual fail-open, narrow (it needs the source text to coincide with a readable clean path)
   but genuine, and it exists on `master` today as much as under this plan. A test that uses only
   a nonexistent code-looking path therefore demonstrates that the cluster token is not consumed
   as data; it does not demonstrate that the inline program is scanned.

   Two ways close it, for the approver to choose between:
   - **Reject unsupported clusters.** When the program is still unknown and a token is a short
     option cluster the family's tables do not recognise (a single-`-`, length > 2 token that is
     not a long `--` flag and matches no value/preload/inline flag), refuse it before reading the
     following token as a script, rather than skipping it. This makes the block unconditional
     (independent of whether the next token is a readable file) and is a small, local change. Its
     cost is that it can only ever block — it still does not run the one-liner — so it does not
     remove the over-block, only makes the fail-closed behaviour honest; and it must be scoped to
     Perl/Ruby/Node (families with a code grammar), since a grammar-less interpreter's unknown
     clusters cannot be classified this way.
   - **Parse clusters.** Decompose `-pe`/`-lne`/`-ne` into their constituent switches, resolve
     which ones take a value, and scan the `-e` program properly. This removes the over-block (the
     one-liner would be scanned and, if clean, allowed) but is the riskier change: a mistake about
     which switches consume a value is itself a fail-open.

   Either is a reasonable follow-up task once this change lands; neither is in scope here, which
   keeps the bug fix small.

5. **Pipe vs heredoc on the inline-program stdin path (in scope; one decision for the approver).**
   Because an inline program can read and execute its own stdin, Scope item 2 requires every
   inline-program path to inspect the command's stdin, reusing `_check_stdin_program` (refuse a
   pipe, scan each heredoc body as code). That is three sites, not two: the Python `-c` immediate
   return, the Perl/Ruby/Node `inline_seen` early return (reached when a later token follows the
   inline program), and the broadened end-of-loop check `if inline_seen or not code_seen:`
   (reached when a Perl/Ruby/Node inline flag is the command's last token). A reviewer raised two
   shapes of this on PR #659 — the `python3 -c 'exec(sys.stdin.read())' - <<…` bypass and, later,
   the no-trailing-token `perl -e 'eval <STDIN>' <<…` / piped form that the early return alone
   misses. Scanning the heredoc bodies is unambiguously right: the body is present, cheap to scan,
   and skipping it both regresses the `-`-token case (master scans it via the `tok == "-"` branch)
   and leaves a pre-existing no-`-` gap open. The only genuine choice is the **pipe**:
   - **Refuse the pipe (recommended, as written above).** Consistent and fully fail-closed:
     `cat x | python3 -c 'exec(sys.stdin.read())'` and `cat x | perl -e 'eval <STDIN>'` block, and
     the `-`-token pipe form that master already blocks stays blocked. Cost: a piped inline
     one-liner that never reads stdin (`cat x | python3 -c 'print(1)'`) is newly over-blocked.
     This is the safe direction and no such idiom has been reported against the guard.
   - **Scan only visible heredoc bodies, leave pipes as today.** Avoids the new over-block, but
     the guard cannot see piped content, so `cat x | python3 -c 'exec(sys.stdin.read())'` would
     pass — and worse, the `python3 -c 'CODE' -` *with a pipe* form that master blocks via the
     `tok == "-"` branch would flip to allow, a fail-open regression. If the approver prefers this
     option, the `-`-token pipe case must be special-cased to stay blocked so no regression is
     introduced.

   The recommendation is to refuse the pipe uniformly; the over-block it costs is small and in
   the safe direction, and it keeps the stdin rule a single, honest statement rather than a set of
   per-form exceptions.

   **Input redirections and here-strings are out of scope (pre-existing gap).** The stdin
   inspection above can only see what `_simple_commands` threads through to the runner — a pipe and
   heredoc bodies. An ordinary input redirection (`python3 -c 'exec(sys.stdin.read())' < payload.py`)
   and a here-string (`python3 -c 'exec(sys.stdin.read())' <<< 'import socket'`) are input forms in
   `_REDIRECT_TOKENS` but not `_OUTPUT_REDIRECT_TOKENS`, so the lexer skips the operator and its
   target and the content never reaches `_check_stdin_program`; the inline program reads and
   executes it unscanned. A reviewer raised this on PR #659. It is a pre-existing gap, not a
   regression — master does not scan a redirected file or a here-string fed to an inline program
   either, because `code_seen` suppresses its trailing stdin check. Closing it is a larger change
   than this one: `_simple_commands` would have to expose input-redirection targets and here-string
   words (a new field on the simple-command tuple, consumed by the dispatcher), and
   `_check_stdin_program` would read a `< file` as a script to scan and scan or refuse a `<<<`
   word. That widens the change into the lexer and the dispatcher and touches every caller of the
   simple-command tuple, so this plan narrows its stated stdin guarantee to pipes and heredocs and
   files the input-redirection/here-string case as a separate follow-up for the approver, keeping
   the bug fix small. Until it lands, the guarantee is honestly stated as "pipes and heredocs,"
   not "all stdin."

6. **Expanded value/preload operand before any inline program (pre-existing, out of scope).** The
   `inline_seen` expansion refusal on the value-flag, preload-flag and Ruby `-C` handlers (Scope
   item 2, Approach Perl/Ruby/Node bullet) is deliberately gated on an inline program having
   *already* been seen, so an expansion consumed as such an operand *before* the first inline flag
   is not refused: `perl -I ${more[@]} -e 'print 1'` and `perl -I ${more[@]} script.pl` with
   `more=(safe -e 'use Socket; …')` word-split `${more[@]}` into a second `-e` program the guard
   never scans. A reviewer (Copilot, PR #659) raised the inline-first shape; this is its
   mirror. It is a pre-existing gap, not a regression — master consumes the `-I` operand without an
   expansion test in every ordering — and the gate is what keeps the common include-list idiom
   (`perl -I ${inc[@]} script.pl`) from becoming a new false positive, since before any inline
   program the guard has no reason to treat an expanded include list as a smuggled program. Closing
   it in the pre-inline direction would mean refusing *every* expanded value/preload operand
   regardless of `inline_seen`, which over-blocks legitimate dynamic include lists and conflicts
   with the plan's goal of removing false positives; a precise fix would have to model each flag's
   word-splitting rather than refuse wholesale. Both are larger than this change, so the pre-inline
   case is recorded here as a follow-up for the approver rather than closed. The inline-first case
   — the one with a demonstrated second-program hazard once the guard knows an inline program is
   present — *is* closed, by the gated check above.

   One further operand-consuming handler sits above the early return and is *not* given the gated
   refusal: the generic `-m` handler, which reads its module from the attached value or the next
   token and then *returns* (ending the walk). It is left as-is because it is barely reachable as a
   smuggling vector for the families that carry the `inline_seen` early return. Python — the only
   family with a real `-m MODULE` that takes a separate operand — returns at `-c` before the `-m`
   handler runs, so it never reaches it after inline code; Node and Ruby have no `-m`; and Perl's
   `-m` is an attached module-import (`-mFoo`), so the only expansion shape that could smuggle a
   second `-e` through it is an attached `-m${…}`, an exotic case with none reported. Giving the
   `-m` handler the same `inline_seen`-gated expansion refusal would be a consistent, strictly
   safer extension if the approver wants it; this plan scopes it out with the pre-inline case to
   keep the change small, and the enumeration of operand-consuming handlers covered by the gated
   check (value-flag, preload-flag, Ruby `-C`) is stated with this one carve-out named rather than
   silently omitted.

## Estimated complexity

Small to moderate. About 30–40 lines changed in `exfil_bash_runner.py` — a per-family
inline-code flag table beside `_INTERPRETER_VALUE_FLAGS` (Python `-c`; Perl `-e`/`-E`; Ruby `-e`;
Node `-e`/`--eval`/`-p`/`--print`/`-pe`, without attached short values but with the long `=`
forms, matched in two `match_flag` calls) used in place of the
shared set for the four families with a table, a Python-family immediate return in the
inline-code branch, an `inline_seen` early return above `tok == "-"` for the
Perl/Ruby/Node families — tracked separately from `code_seen` so Perl's `-M` (which loads a
module and leaves the next operand a script) does not trigger it, and itself refusing any token
the shell can expand (positional *or* dash-prefixed, the latter tested ahead of the generic
`tok.startswith("-")` skip so a `-$opt`→`-e` cannot slip through it) — the
grammar-less families kept on today's fall-through, the `-` stdin return, a conservative
`_check_stdin_program` call on each inline-program path (the Python immediate return, the
Perl/Ruby/Node `inline_seen` early return, and the end-of-loop check broadened from
`if not code_seen:` to `if inline_seen or not code_seen:` so a last-token inline program's stdin
is still inspected — all reusing the existing helper, no new stdin logic),
the `inline_seen`-gated expansion refusal on the value-flag, preload-flag and
Ruby `-C` operand-consuming handlers (so an expanded `-I`/`-r`/`-C` operand after inline code is
refused rather than swallowed), and the
unresolvable-token guard — reusing the existing `is_shell_expanded` and `is_glob_or_brace`
predicates at all three of its loop sites (two added imports, no new predicate), one new test module,
and three one-line list additions (two `GUARD_FILES` arrays and the `analysis.yaml`
`PROTECTED_FILES` array).
