# Leak corpus — adversarial test data for the private-link guard

This is the red-team corpus for the "Never Publish a Private Link" rule (root
`CLAUDE.md`) and its in-session detector
`.claude/hooks/lib/private_url_check.py`. It is the deliverable of the first
pass of the red-team task: enumerate every form in which a future agent might
try to smuggle a private Claude session link or session id onto a public
surface, and hand that set to the guard's own tests and to a later pass that
runs it against each egress route.

The companion route inventory — every path by which agent or automation text
reaches a public surface, and which of the guard task's routes covers it — is
in [`docs/internals/leak-route-inventory.md`](../../docs/internals/leak-route-inventory.md).

## What is here

- `leak_corpus.py` — the generator. `all_cases()` returns a list of `Case`
  tuples, each with the adversarial `payload`, the plain `canonical` token it
  reduces to, the obfuscation `kind`, the public `surface` it is meant to sit
  on, and the `normalizer_required` steps a guard must perform to see through
  it.
- `test_leak_corpus.py` — proves the corpus is well-formed and literal-free,
  guards against the detector regressing on plain tokens, and records (one
  `expectedFailure` method per family) exactly which obfuscation families the
  detector cannot yet see through.

## No committed file contains a real link or id

Every case is assembled from disjoint fragments at call time. The two canonical
secrets — the private host and a session id — are never written as literals
anywhere in the module; `leak_corpus.assert_no_literal_secret_in_source` scans
the source to enforce this and is exercised by the tests. Consumers must build
their own expectations the same way (from `case.payload` / `case.canonical`),
never by pasting a literal.

## How the guard's tests consume it

The corpus is language-neutral in intent but shipped as a Python generator,
because the in-session detector is Python. A consumer imports it and feeds each
`payload` to the thing under test:

```python
import leak_corpus
for case in leak_corpus.all_cases():
    assert guard_blocks(case.payload), case   # a correct guard blocks every one
```

`test_leak_corpus.py` shows the pattern against today's detector, loaded by
path so the corpus does not depend on being run from inside `.claude/`.

### The expected-failure ratchet

Against today's detector, the obfuscation families fail to be blocked. Each is
asserted through `unittest.expectedFailure`, so the suite is green now. When the
guard learns to normalise a family, its assertion starts passing, unittest
reports an **unexpected success**, and whoever strengthened the guard removes
the decorator for that family. Gaps therefore ratchet shut instead of being
silently forgotten. Do not add new `expectedFailure` methods to hide a
*regression* — that decorator is only for a not-yet-closed gap.

## Running

```bash
cd tools/leak-corpus
python3 -m unittest test_leak_corpus
```

## Scope

Pass 1 (this corpus + the route inventory + filed gap tasks) builds the data
and exercises it against the detector's matcher. Pass 2 — after the guard merges
— runs the same corpus against each egress route against stubbed endpoints and
reports anything that still gets through, with a reproducer. This corpus never
performs a live GitHub or network write.
