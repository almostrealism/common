# Why the method-placement hook did not move `broadcast`

Written at the request of the review of PR #495. During that branch's work the
`warn-method-placement.py` hook fired on essentially every edit that introduced a
method, and the agent still placed `broadcast(shape, axis, values)`, a pure
collection-producer operation, in `LayerFeatures` (domain/graph) instead of the
algebra mixins where `repeat`, `concat` and `traverse` live. This note records why
the reminder was read and not acted on, so the hook can be made effective rather
than louder.

## What actually happened

The method was written twice. The first version was the body of a layer builder,
`scale(shape, axis, factors)`, which multiplies a layer input by one factor per
position. When a second consumer appeared (the additive key mask in attention), the
repeat-and-reshape core was extracted into `broadcast` and placed directly beneath
the layer builder it came from. The extraction was itself a response to the "would a
second consumer want this" question, so the agent registered the placement problem
as solved: the method had been lifted one level, from inside a lambda to a mixin
method. It was not lifted far enough. The right rung was the one above: the method
reads only a `TraversalPolicy` and a `Producer`, and calls only `c`, `reshape` and
`repeat`, every one of which is defined in `compute/algebra`. Nothing about it is a
layer.

## Why the reminder did not change that

1. **It fires on everything, so it carries no information about this method.** In
   one session it fired on getters, on `@Override` implementations of an interface,
   on private test helpers, on test fixtures, on `destroy()`, and on the method that
   was wrongly placed. The same paragraph appeared about forty times. After the first
   few, it was read as a fixed cost of editing Java rather than as a signal that a
   particular method was suspect, and the agent's attention went to the second tier
   (the `static` warning), which is the only part that varies with the code. A
   reminder that cannot distinguish a getter from a misplaced primitive trains the
   reader to skim it.

2. **It asks a question the agent believes it has already answered.** The ladder in
   the reminder ("does an equivalent exist, does it read another type's state, is it
   domain-agnostic, where would the next consumer look") is a good checklist, but at
   the moment of the edit the agent has usually just made a placement decision with
   some reasoning behind it, and a generic checklist does not challenge that
   reasoning; it invites the answer "yes, I did that". The misplacement here was a
   wrong answer to rung 3, and the reminder gave no way to notice that the answer was
   wrong.

3. **It has no notion of the module layering.** The reminder says "the general
   layer" and "a base features mixin" without saying which module or interface that
   is for the method in hand. The agent's working model of "most general place I have
   open" was `LayerFeatures`, because that is the mixin the layer code implements and
   the only one visible in the file being edited. Deciding that the correct home is
   `compute/algebra` requires knowing that `LayerFeatures` extends the algebra mixins
   and that the algebra module is the lowest module whose types cover the method's
   signature and body. Both facts are mechanical and the hook could compute them.

4. **It fires before the code exists, at the least convenient moment to act.** As a
   `PreToolUse` hook it reads the proposed edit. The agent is mid-thought, has the
   surrounding file loaded, and is one tool call from continuing. Moving the method to
   another module means opening a different file, checking its imports and
   dependencies, and re-planning the edit. The reminder makes that cost visible while
   offering nothing that reduces it, so the cheapest compliant response is to accept
   the reminder and keep going.

5. **Advice cannot compete with a blocking check.** The same session's other hooks
   (checkstyle length caps, the exfiltration guard, the setMem policy) all block, and
   every one of them changed behaviour immediately. A hook that only appends context
   is weighed against whatever the agent was already doing and loses whenever the
   agent is confident.

## What would have worked

- **Fire only when there is evidence.** Skip getters, overrides, constructors, test
  sources and private helpers whose signature mentions the enclosing class. Fire when
  the new method's signature and body reference no type from the module it is being
  added to. That single check would have flagged `broadcast` and stayed silent on
  the rest.
- **Name the destination.** When it fires, say which lower module's types suffice:
  "`broadcast` uses only `TraversalPolicy`, `Producer`, `c`, `reshape` and `repeat`,
  all from `compute/algebra`; `LayerFeatures` extends `CollectionFeatures`, so the
  method belongs there". A concrete destination is an instruction; a ladder is a
  reading assignment.
- **Block the clear cases.** If the destination check is confident (every referenced
  type resolves at least one module below), refuse the edit and require either the
  move or a one-line justification in the edit. Advice-only hooks are the ones that
  fail.
- **Fire once per method, not once per edit.** Repeated edits to the same new
  method should not repeat the reminder; repetition is what turned it into noise.
- **Check placement again at validation time.** The build validator already walks
  the tree for policy violations; a placement pass there, on the final code, would
  catch what a mid-edit reminder cannot, and would have caught this before review.

## Disposition of the case that prompted this

`broadcast` now lives in `CollectionFeatures` next to `concat`, built from `c`,
`reshape` and `repeat` of the same module; `LayerFeatures.scale(shape, axis, factors)`
and the attention key mask consume it from there.
