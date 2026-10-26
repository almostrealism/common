# Text Representation of PatternElement (Parse and Serialize)

- **Tracker task:** `a80d6ce3-69ad-49b1-8a5b-44addfcfe648` — *Text representation of PatternElement (parse and serialize)*
- **Release:** Common 0.76
- **Builds on:** `96700c1f-e18b-4013-80f2-ba61b6ebb857` — *Protobuf message and serialization for PatternElement* (closed; delivered `PatternElementPersistence` and the `PatternData` / `PatternElementData` messages in `studio/compose/src/main/proto/audio.proto`)

## Motivation

The pattern model, centered on `PatternElement`
(`studio/music/src/main/java/org/almostrealism/music/pattern/PatternElement.java`), can now be
stored as protobuf, but no person or agent can write a binary message by hand. A text
representation lets a client application or a connected agent write out an exact pattern,
hand it to the framework to render, and read back any pattern the framework holds. The task
requires the text format and the protobuf format to describe **the same pattern model**:
anything one can say, the other can say, and a pattern converted through either comes back
the same.

## Scope

In scope:

1. A text grammar for a pattern (an ordered list of `PatternElement`s) that can express every
   piece of state the protobuf format holds:
   - element: per-voicing notes, position, `NoteDurationStrategy` and note-duration selection,
     `ScaleTraversalStrategy` and scale positions, `PatternDirection`, repeat count, repeat
     duration, and automation parameters (shape and FP64 values);
   - note composition: `PatternNote` in layer mode or delegate+filter mode, its aggregation
     choice, `PatternNoteAudioChoice` selections, `PatternNoteLayer`s, nested `PatternNote`s;
   - filters: `ParameterizedVolumeEnvelope`, `ParameterizedFilterEnvelope` (mode + ADSR
     `ParameterFunction`s) and `ParameterizedLayerEnvelope` (the `ParameterizedEnvelopeLayers`
     functions + layer index), each with the `ParameterSet` and voicing the filter was
     created with;
   - the null and empty states protobuf can carry: null strategy/direction/voicing, a voicing
     entry with no note, absent selection functions, absent parameters, absent automation.
2. A serializer (pattern model → text) and a parser (text → pattern model) in
   `studio/music`, package `org.almostrealism.music.pattern`.
3. Unit tests: text round-trip in `studio/music`, and text ↔ protobuf equivalence in
   `studio/compose` (the only module that sees both formats).
4. A short section on the format in `studio/music/README.md` (grammar summary, the
   keyword→field mapping table, and an example) so agents and client authors can write it.

Out of scope: any change to `audio.proto` or to `PatternElementPersistence`'s behavior; an MCP
tool or application endpoint that accepts the text (that belongs to the consuming
application); representing note audio or filter implementations the protobuf format also
rejects.

## Approach

### Where the code goes

`PatternElementPersistence` sits in `studio/compose` only because the generated protobuf
classes are there (`ar-compose` depends on `ar-music`, not the reverse). Every model type it
maps — `PatternNote`, `PatternNoteLayer`, `PatternNoteAudioChoice`, the three
`Parameterized*Envelope`s, `ParameterizedEnvelopeLayers`, `ParameterizedEnvelopeFilter`,
`ParameterSet`, `ParameterFunction`, `MultipleParameterFunction` — is in `ar-music`. The text
format therefore needs no protobuf dependency and goes where the task says: next to the model
in `org.almostrealism.music.pattern`. Nothing in `pom.xml` changes.

The format is a concept in its own right (a grammar with a reader and a writer), so it is one
new class, **`PatternText`** (`studio/music/src/main/java/org/almostrealism/music/pattern/PatternText.java`),
with:

- `static String format(List<PatternElement> pattern)` / `static String format(PatternElement element)`
- `static List<PatternElement> parse(String text)` / `static PatternElement parseElement(String text)`
- a nested `ParseException extends IllegalArgumentException` that reports line and column.

`PatternElement` gets thin entry points that delegate to it — `String toText()` and
`static PatternElement fromText(String)` — so the capability is found where the model is. The
reader is a small hand-written tokenizer plus a recursive-descent parser (no parser-generator
dependency — none is on `ar-music`'s classpath, and the grammar is small).

### Grammar (to be finalized in implementation)

Brace-delimited, whitespace-insensitive, `#` line comments. Each keyword corresponds
one-to-one to a field (or, where noted below, a fixed pair of fields) of `PatternElementData`
and its nested messages, and enum values are the Java constant names — which the protobuf
enums already share (`NONE`, `FIXED`, `NO_OVERLAP`; `CHORD`, `SEQUENCE`; `FORWARD`,
`BACKWARD`; `MAIN`, `WET`) — so the mapping between the two formats stays mechanical. The
keywords are chosen to be concise rather than the literal snake_case field names (`duration`
rather than `duration_strategy`, `scale` rather than `scale_traversal_strategy`), and three
of them bundle the two proto fields that always travel together onto one line:

- `duration <strategy> <selection>` → `duration_strategy` + `note_duration_selection`
- `scale <strategy> [positions]` → `scale_traversal_strategy` + `scale_positions`
- `repeat <count> every <duration>` → `repeat_count` + `repeat_duration`

The remaining keywords map to a single field each: `position` → `position`, `direction` →
`direction`, `automation` → `automation_parameters`, `note` → an entry of `notes`. The README
section (scope item 4) carries the full keyword→field table so the correspondence is explicit
and reviewable. Illustrative:

```
pattern {
  element {
    position 2.25
    duration FIXED 0.375
    scale SEQUENCE [0.0, 0.25, 0.625]
    direction BACKWARD
    repeat 4 every 0.5
    automation [2, 3] [0.1, 0.2, 0.3, 0.4, 0.5, 0.6]
    note MAIN {
      delegate note {
        delegate note { layers [ layer { delegate choice 0.12  filter layer_envelope { ... } } ... ] }
        filter filter_envelope STANDARD_NOTE {
          attack (0.1, 0.2, 0.3, 0.4) decay (...) sustain (...) release (...)
          params (0.31, -0.72, 0.18) voicing MAIN
        }
      }
      filter volume_envelope NOTE_LAYER { ... }
      aggregation 0.0
    }
    note WET { ... }
  }
}
```

Shorthands for the common, simple cases — `note MAIN choices [0.2, 0.65]` for a layer-mode
note of plain selections — keep hand-written patterns short; the serializer always writes the
canonical long form only where the shorthand cannot express the state.

Rules that make the two renderings one model:

- **Numbers** are written with `Double.toString` and read with `Double.parseDouble`, which
  round-trip every finite double exactly (protobuf stores FP64, so the text must too).
  Automation values are written the same way, never at reduced precision.
- **Nulls** are written as the keyword `none` (e.g. `direction none`, `note none MAIN`), the
  counterpart of a protobuf zero enum value or an unset optional field. `none` is distinct
  from `NoteDurationStrategy.NONE`, which is written `NONE`.
- **Unsupported types** (any other `PatternNoteAudio`/`NoteAudioFilter`/`ParameterizedEnvelope`
  implementation, or a `PatternElement` subclass) make `format` throw
  `IllegalArgumentException`, matching `PatternElementPersistence`, rather than dropping state.
- **Filters** are rebuilt the same way the protobuf decoder rebuilds them: construct the
  envelope from its definition and call `envelope.createFilter(params, voicing)` (via
  `ParameterizedEnvelopeLayers.getEnvelope(layer)` for layer envelopes).
- **Omitted element fields** take `PatternElement`'s constructor defaults (`NONE`, `CHORD`,
  `[0.0]`, `FORWARD`, repeat 1 every 1) so a hand-written element is playable without
  boilerplate. The serializer writes every element field explicitly, so round-trips never
  depend on defaults. (See open question 1.)
- **Unknown keywords, duplicate fields and trailing input are errors**, reported with line
  and column; the parser never guesses.

### Avoiding divergence from the protobuf codec

Both codecs walk the same closed set of types with an `instanceof` chain. The plan keeps the
two walks separate (one per format, in the module each format lives in) and relies on the
cross-format test below to fail the build if either codec gains a type or field the other
lacks. Extracting a shared format-neutral visitor into `ar-music` would remove the duplicated
dispatch but rewrites the merged protobuf codec; it is listed as open question 3, not done
here.

### Tests

`studio/music/src/test/java/org/almostrealism/music/pattern/PatternTextTest.java`
(extends `TestSuiteBase`, every `@Test` has a timeout):

- a full multi-element pattern built like `PatternElementPersistenceTest.fullPattern()`
  (melodic element from `PatternNoteFactory` with filter/volume/layer envelopes and 2×3
  automation; percussive layered note with `NO_OVERLAP` and aggregation choice; unblended
  element with repeats) formats and parses back to elements that are `contentEquals` the
  originals, in order;
- re-formatting the parsed pattern yields identical text (covers state `contentEquals` does
  not compare — `PatternNote.equals` ignores the aggregation choice, and envelope filters are
  compared through their envelopes);
- default element, null strategies/direction/voicing, voicing with no note, and absent
  automation round-trip as such;
- a hand-written minimal element (only `position` and a `choices` note) parses with the
  documented defaults;
- malformed input (unknown keyword, bad number, unbalanced brace, duplicate field) throws
  `PatternText.ParseException` naming the line and column;
- an unsupported filter (a lambda `NoteAudioFilter`) is rejected on format.

`studio/compose/src/test/java/org/almostrealism/studio/persistence/test/PatternTextProtobufEquivalenceTest.java`
(new file, so no existing test changes):

- for the same full source pattern, `PatternText.parse(PatternText.format(p))` and
  `PatternElementPersistence.fromPatternData(toPatternData(p))` are element-wise
  `contentEquals`, **and** encoding both results with `PatternElementPersistence.toPatternData`
  gives equal `PatternData` messages (the strictest available equality — it covers every
  field of both formats);
- protobuf → model → text → model → protobuf reproduces the original `PatternData`, and
  text → model → protobuf → model → text reproduces the original text.

The full-pattern builder currently private to `PatternElementPersistenceTest` is needed by
both new tests; the implementation will not edit that existing test, and will build an
equivalent fixture in the new compose test (reusing the music test's fixture would require a
test-jar dependency, i.e. a `pom.xml` change).

## Success criteria

- `PatternText` and the `PatternElement.toText()` / `fromText(String)` entry points exist in
  `org.almostrealism.music.pattern`, with javadoc, and `studio/music/README.md` documents the
  grammar with an example.
- `PatternTextTest` and `PatternTextProtobufEquivalenceTest` pass under the MCP test runner
  (`module: studio/music`, `test_classes: ["PatternTextTest"]`; `module: studio/compose`,
  `test_classes: ["PatternTextProtobufEquivalenceTest", "PatternElementPersistenceTest"]`).
- `mvn clean install -DskipTests` succeeds, and the build validator (checkstyle, code_policy,
  test_timeouts, duplicate_code) reports no violations.
- No changes to `audio.proto`, `PatternElementPersistence`, any `pom.xml`, or any existing test.

## Dependencies

- The protobuf task (`96700c1f`) is closed and merged; this plan uses its
  `PatternElement.contentEquals`, `PackedCollection.contentEquals`, the envelope getters it
  added (`ParameterizedEnvelopeAdapter.getMode()`, `ParameterizedLayerEnvelope.getLayer()` /
  `getAttackSelection()` / `getVolume0..3()`), and `PatternElementPersistence` (tests only).
- No special hardware: the tests build small `PackedCollection`s and run on any backend, so
  no `requiredLabels` settings file accompanies this plan.

## Open questions

1. **Defaults for omitted fields.** The plan uses `PatternElement`'s constructor defaults
   (agent-friendly: an element written as just a position and a note plays). The alternative
   is protobuf's zero values (repeat count 0, null strategies), which matches what a protobuf
   reader sees for an unset field but makes minimal hand-written elements silent. Which should
   the format specify?
2. **Shorthand forms.** Is the `choices [...]` shorthand (and any others) wanted, or should
   the format have exactly one way to write each state? Shorthands help agents; a single form
   simplifies the grammar and its documentation.
3. **Shared dispatch.** Should a later change extract the type dispatch both codecs share into
   `ar-music` so a new note-audio or filter type is added in one place? Out of scope here;
   the equivalence test is the guard in the meantime.
4. **Format version header.** Should the text begin with a version line (e.g.
   `pattern_text 1`) so the grammar can evolve? Protobuf evolves by field numbers; text has no
   equivalent without one. The plan omits it unless requested.

## Estimated complexity

**Medium.** One new production class (tokenizer, recursive-descent parser, writer — roughly
the size of `PatternElementPersistence`), two thin methods on `PatternElement`, two new test
classes, and a README section. No module, dependency, schema or existing-test changes; one
change set is appropriate.
