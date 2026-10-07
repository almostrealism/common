# Forcing object-oriented design on coding agents

## Why

Agents keep landing procedural designs in this codebase and the owner keeps rejecting them in
review. PR #620 is the worked example. `CausalLanguageModel` was neither a `Model` nor named as a
factory. `generator(CompiledModel, Random)` accepted any compiled model rather than the model's
own. It returned `new SlidingWindow(inference, random).generator`: an inner object built only so
one of its fields could be read, wired to `AutoregressiveModel` through lambdas that closed over
it.

The existing defences did not stop this:

- **The PreToolUse hooks.** `warn-method-placement.py`, `warn-new-type-placement.py` and
  `block-interface-bypass.py` are static regex checks. They warn about new methods and types,
  but they cannot judge whether a class is the thing its name says it is.
- **The reviewer agents.** `.claude/agents/*` (Placement Reviewer, Code Reviewer, ...) run only
  when the agent chooses to call them, and nothing calls them.
- **The harness review pass.** `ReviewRule` / `ReviewPromptBuilder` sees the whole diff in one
  session, and it is told to *defer* design issues to a `TODO(review)` and a memory rather than
  fix them.

## What is now enforced (no human action needed)

`flowtree/runtime`'s `ObjectOrientedDesignRule` is an `EnforcementRule`. It is active whenever
review is enabled, which is the default. It runs after the content-protection rules and before
the staging and commit-message checks, so its sessions cannot use up the job's total enforcement
budget (25 attempts) before those rules run.

- It gives every production class the job added or modified, under `src/main/java`, a
  correction session of its own. New classes come first, and at most `MAX_CLASSES` (12) are
  reviewed per job, a limit that also keeps it within the job's enforcement budget.
- Each session considers only that one class. It checks an explicit list of the violations the
  owner has rejected, and it must fix them now, refactoring across files if needed. It may not
  defer.
- Each session appends a verdict line to `.flowtree/oop-review.md`. That file is scratch space
  and is never committed. It is also the rule's progress path, so a class found clean does not
  count toward `EnforcementRunner` retiring the rule as stalled.
- A class that a review session itself adds or modifies is reviewed in turn.

**Cost:** one agent session per changed class. That is deliberate; it is the per-class
subagent the owner asked for, run per job rather than per edit. Running it per edit would repeat
the review on every intermediate state of a class.

`CLAUDE.md` (CODE QUALITY) now states the three rejected patterns explicitly: identity, owning
your collaborators, and not reaching into fields. It also says that the harness reviews every
changed class.

## What needs a human (agents cannot write under `.claude/`)

### 1. Install the interactive reviewer agent

Create `.claude/agents/oop-reviewer.md` with the content below. Interactive sessions can then
spawn it once per changed class, which matches the harness rule.

```markdown
---
name: OOP Reviewer
description: Reviews ONE Java class for object-oriented design violations (identity, owned collaborators, field reach-through, lambda-wired helpers that should be subclasses, misplaced/static behaviour, broken interface or superclass contracts) and fixes them. Spawn once per added or modified production class before staging.
model: sonnet
---

You review exactly one class, named by the caller, for object-oriented design. Assume the
author wrote procedural code; the existing shape of the class is suspect, not a given.

Read the whole class, every supertype and interface, and every caller of its public and
protected members; run `git diff origin/master -- <file>`.

Hunt for: (1) identity - a class is what its name says (`...Model` extends the model type,
`...Config` extends the config base, a builder of another type is that type or `...Factory`);
(2) owned collaborators - no public method accepts an object the receiver should derive from
its own state; (3) no reads of another object's fields and no object built only to pull out
a field; (4) lambdas closing over a helper object passed to a framework constructor mean the
helper should be a subclass overriding template steps; (5) behaviour on the type whose state it
reads, no static helpers whose signature never mentions their class; (6) no utility/helper
classes; (7) honor the interface; (8) honor the superclass contract.

Fix every violation now, including refactors across callers, tests and docs. Never defer and
never weaken a test. Compile, run the class's tests, and report each violation and its fix, or
state explicitly that the class is clean.
```

### 2. Add two cheap static checks to the PreToolUse hooks (Edit/Write, `.java`)

These checks are mechanical, catch the specific shapes from PR #620 at edit time, and cost no
agent session:

- **Field reach-through.** Block `new\s+\w+(<[^>]*>)?\([^;]*\)\.\w+\s*;`, which matches a
  freshly constructed object whose field is read rather than a method called. `warn` on
  `\b(?!this\b)[a-z]\w*\.[a-z]\w*\s*;` where the member is a field of a repository class.
  This needs the same `git ls-files` type lookup that `block-interface-bypass.py` already does.
- **Identity by name.** When a new or edited class declaration is `class \w+Model\b` and has no
  `extends \w*Model`, warn with the identity rule. Do the same for `\w+Config` without
  `extends \w*Config`.

- **Features mixins are implemented, not instantiated.** PR #620 again:
  `CausalLanguageModelConfig` declared
  `private static final RotationFeatures OPS = new RotationFeatures() { };` and called
  `OPS.computeInvFreq(...)`, `OPS.cp(...)`. The owner's answer: you IMPLEMENT
  `RotationFeatures` to get these methods; you do not declare a field holding one. See the
  hook design below.

All three belong in `.claude/hooks/lib/` beside `block_interface_bypass`, with pytest coverage,
and must be registered in `.claude/settings.json`.

### 3. Hook design: `block-features-instance.py` (every Edit/Write of a `.java` file)

**What it looks for.** In the content an `Edit`/`Write` would produce (the `new_string` for
`Edit`, the `content` for `Write`) of a file under `src/main/java`:

1. An anonymous instantiation of a features mixin:
   `new\s+(\w+Features)\s*(<[^>]*>)?\s*\(\s*\)\s*\{\s*\}`. A type counts as a features
   mixin when its name ends in `Features` and `git ls-files` finds it as an `interface` in
   the repository (the same type lookup `block-interface-bypass.py` performs), so a class
   that merely ends in `Features` is not matched.
2. A field, local or constant of such a type that is then used only as a receiver for its
   default methods: `(static\s+)?(final\s+)?(\w+Features)\s+(\w+)\s*=` followed by
   `\b\4\.\w+\(` elsewhere in the class.

**What it does.** Blocks the edit (exit code 2) with a message:

> `RotationFeatures` is a features mixin: an interface whose default methods are the
> operations a class gains by implementing it. Declare `implements RotationFeatures` on the
> class that uses them and call `computeInvFreq(...)` directly, instead of building an
> anonymous instance and calling its methods through a field. If the class cannot implement
> it (a name clash with a superclass method, for example), say so in the class javadoc and
> resolve the clash; do not route around it.

**Allowed shapes, so that the check is not a blunt ban.**

- A features interface's own static singleton accessor (for example
  `CollectionFeatures.getInstance()`), and the interface's own source file: the guard skips a
  file whose declared type is the matched interface.
- Static methods of a mixin (`RotationFeatures.computeRopeFreqs(...)`): they are not
  instance calls through a field, and pattern 2 requires a declared variable.
- Test sources (`src/test/java`) are warned (exit 0 with the message on stderr) rather than
  blocked, since a test may need operations from a mixin its base class does not implement.

**Existing violations.** Some production classes already hold anonymous mixin instances (for
example `AutoregressiveModel.DIST`, used from the static `of(...)` factory). The hook blocks
only lines that the edit adds, comparing `old_string` with `new_string` as
`block-interface-bypass.py` does, so it does not block unrelated edits to those files. Each
such class should then be fixed when it is next touched: a static factory that needs the
operations becomes an instance method or uses `Ops.o()`.

**Tests.** `pytest` cases for: anonymous instantiation in a field (blocked); a local variable
(blocked); `implements RotationFeatures` with direct calls (allowed); a static call
`RotationFeatures.computeRopeFreqs(...)` (allowed); the same pattern under `src/test/java`
(warned); an edit to a file with a pre-existing instance that does not add one (allowed).
