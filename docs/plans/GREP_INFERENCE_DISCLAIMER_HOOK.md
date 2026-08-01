# Grep-Inference Disclaimer Hook

A `PreToolUse` hook that fires whenever the agent runs `grep` (or `rg`/`egrep`,
or the `Grep` tool) and injects an inline reminder that **grep output is weak
evidence and is never a basis for a negative or universal claim.**

Modeled on the existing `ar-hooks/memory-reminder` hook (see
[MEMORY_REMINDER_HOOK.md](MEMORY_REMINDER_HOOK.md)): a `PreToolUse` hook that
appends `additionalContext` to the tool call rather than blocking it.

## Why this is needed

The agent makes one specific inference error **almost universally**: it treats a
grep result as proof of a claim, especially a *negative* claim, without doing the
work to verify. Two concrete, expensive instances:

- **Saturday (~2 hours lost):** a kernel-debugging session that amounted to "I
  thought this kernel was the one we were debugging, but there were multiple uses
  of `norm()` and I wasn't even looking at the right one." A grep matched *a*
  `norm()`; the agent assumed it was *the* `norm()`.
- **Argument-aggregation CI failure:** the agent ran
  `grep ... analysis.yaml | head -40`, saw `-DAR_HARDWARE_DRIVER=native` repeated,
  and concluded "CI doesn't use Metal." It had only looked at the Linux job lines;
  the macOS/Metal jobs were further down the file. An entire fix was reasoned on a
  false premise.

The common shape: **"grep showed N instances of Y, so all X are Y"** — without
counting X, without reading the matches in context, without checking the other
direction. This is the exact anti-pattern the repo's own `CLAUDE.md` already
warns about (Rules 5, 11: "NEVER DRAW STRUCTURAL CONCLUSIONS FROM A SINGLE
SEARCH"), but a static rule in a long document does not fire at the moment the
mistake is being made. An inline reminder does.

## What the hook does

- **Trigger:** `PreToolUse` matching a `Bash` command whose tokens include
  `grep`, `egrep`, `fgrep`, `rg`, or `git grep`; and the `Grep` tool directly.
- **Action:** emit `additionalContext` (a `<system-reminder>`), non-blocking.
- **Debounce:** like the memory-reminder hook, do not fire on every call — that
  trains the agent to ignore it. Fire at most once per N grep calls, or once per
  conversation turn, or only when the command pipes to `head`/`tail`/`wc` or uses
  `-c`/`-l` (the count/limit shapes most associated with the bad inference).

## The reminder text (draft)

> grep/rg results are **weak evidence**. A match tells you a string occurs
> somewhere; it does **not** tell you it occurs in the place you care about, that
> it is the only occurrence, or that occurrences you *didn't* match don't exist.
>
> - **Never make a negative or universal claim from grep.** "There are no X",
>   "all X are Y", "nothing depends on Z", "this is the only N" — grep cannot
>   establish any of these. A non-match means *your pattern* didn't match *what
>   you searched*, nothing more.
> - **A count is not a conclusion.** "10 hits for Y" says nothing about how many X
>   exist. If your claim is "all X are Y", you must enumerate X.
> - **`head`/`-m`/early-exit hide the rest.** If you truncated output, you have
>   seen a prefix, not the set. Re-run without the limit before concluding.
> - **A match is a pointer, not the target.** When multiple call sites share a
>   name (`norm()`, `sum()`, a config key), the match you found is probably not
>   the one you mean. Open it and confirm identity before acting on it.
> - **Verify both directions.** "Does A reference B" and "does B reference A" are
>   different searches with different patterns.
>
> Before stating a conclusion that rests on this grep: would it survive reading
> every match in context and counting the population you're generalizing over? If
> not, say "I don't know yet" and do that work.

## Open questions

- Debounce policy: per-turn vs. per-N-calls vs. shape-triggered. Shape-triggered
  (only on `grep | head`, `grep -c`, `grep -l`, negative-result usage) targets the
  highest-risk cases without nagging on routine line-location greps.
- Whether to also cover `find ... | head` and `ls | grep`, which share the
  truncated-enumeration failure mode.
- Whether the `Grep` tool's `head_limit` / `count` modes should carry a stronger
  variant of the reminder than a plain content search.
