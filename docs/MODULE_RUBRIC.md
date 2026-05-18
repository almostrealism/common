# Module Review Rubric

A framework for evaluating each module's **conceptual design** — not code quality, but how well the module organizes ideas, guides developer thinking, and earns its place in the architecture.

---

## Criteria

### 1. Conceptual Coherence

*Does this module capture a single, well-bounded idea?*

A strong module has a clear central concept that a developer can state in one sentence. Everything in the module relates to that concept. Nothing in the module belongs somewhere else.

| Rating | Description |
|--------|-------------|
| **Strong** | The module has an obvious unifying idea. A developer encountering it for the first time would immediately understand what it's *about*. No classes feel like they wandered in from another module. |
| **Adequate** | The central idea is identifiable but the boundaries are soft. A few classes serve adjacent concerns that could arguably live elsewhere. |
| **Weak** | The module is a grab-bag, or it conflates two or more distinct concepts that would be better separated. A developer would struggle to explain what ties it all together. |

**Questions to ask:**
- Can you state the module's purpose in one sentence without using "and"?
- If you removed the module name from every class, could you still tell which module each class belongs to?
- Are there classes that would be more at home in a dependency or dependent module?

---

### 2. Conceptual Clarity

*Does the space of concepts map cleanly to the space of code?*

A clear module establishes a small vocabulary of types whose relationships are obvious and whose roles don't overlap. Confusion arises when multiple classes represent nearly the same idea, when a single class serves multiple unrelated roles, or when naming obscures rather than reveals intent.

| Rating | Description |
|--------|-------------|
| **Strong** | Each concept has one representation. Interfaces and classes have distinct, non-overlapping roles. A developer reading the type names alone could sketch the conceptual model on a whiteboard. |
| **Adequate** | The model is mostly clean but has some redundancy or overloading. A few types are suspiciously similar, or a class pulls double duty in ways that require explanation. |
| **Weak** | The conceptual model is muddy. Types with different names do the same thing, or types with the same shape mean different things in different contexts. The developer must read implementation to understand what's what. |

**Questions to ask:**
- Are there pairs of classes/interfaces where you'd struggle to explain the difference to a new developer?
- Does any single class serve two conceptually distinct purposes?
- Do naming conventions reveal or obscure the relationships between types?
- Could any two types be merged without losing expressiveness?
- Could any type be split to better separate concerns?

---

### 3. Dependency Justification

*Does the module build on its dependencies in a way that makes logical sense?*

Each dependency should feel inevitable — the module *could not* express its concept without it. Dependencies should be used in ways consistent with their intended purpose, not hijacked for convenience. The module should also feel like a natural *extension* of its dependencies, not an unrelated concept that happens to call into them.

| Rating | Description |
|--------|-------------|
| **Strong** | Every dependency is essential and used idiomatically. The module reads as a natural next step from its dependencies. The layering tells a story. |
| **Adequate** | Dependencies are mostly justified, but some feel incidental — used for a utility here or there rather than being central to the module's concept. |
| **Weak** | The module depends on things it shouldn't need, or uses dependencies in ways that fight their design. The dependency graph feels accidental rather than intentional. |

**Questions to ask:**
- For each dependency: if you removed it, would the module's central concept still make sense, or would it collapse?
- Is the module using a dependency for its intended purpose, or borrowing a convenient class that happens to live there?
- Does the module sit at the right layer? Could it move up or down without distorting the architecture?

---

### 4. Necessity

*Is everything in this module pulling its weight?*

Every public type and capability should earn its place — either because the project uses it today, or because it provides a foundation that future capabilities clearly need. But "future capabilities" means plausible extensions along the module's stated concept, not speculative generality.

| Rating | Description |
|--------|-------------|
| **Strong** | Everything in the module is either actively used or provides clear scaffolding for the module's natural evolution. Removing anything would leave a visible gap. |
| **Adequate** | Most of the module earns its place, but there are corners that feel vestigial or speculative — classes that were perhaps needed once, or abstractions built for a future that hasn't arrived. |
| **Weak** | Significant portions of the module are dead weight. The project would have the same effective capabilities if the module were substantially trimmed. |

**Questions to ask:**
- If you deleted this class/interface, what would break? If nothing, why does it exist?
- Is this capability exercised by any test or downstream module?
- Does this abstraction serve the module's natural evolution, or does it anticipate a use case that has no clear path to realization?
- Could the project achieve the same outcomes with a simpler module?

---

## A Note on Context

A module cannot be fully evaluated in isolation. Its coherence, clarity, and necessity are partly defined by how it connects to the rest of the project — what depends on it, what it depends on, and how downstream modules actually use its types. A module might look unfocused on its own but reveal a clear purpose when you see what it enables. Conversely, a module that seems well-structured internally might expose problems when you notice that dependents use it in ways that conflict with its stated concept, or ignore large parts of its surface area.

This means evaluation will often require looking outward: examining how other modules import, extend, and compose with the module under review. The implicit intentions carried by these connections — what downstream code *assumes* about the module — are as important as the module's own documentation. A module proves its coherence in isolation, but it proves its *value* in context.

---

## How to Use This Rubric

For each module under review:

1. **State the module's intended concept** in one sentence
2. **List its key types** and briefly describe what each represents
3. **Rate each criterion** (Strong / Adequate / Weak) with specific evidence
4. **Identify actionable concerns** — things that could be renamed, moved, merged, split, or removed
5. **Summarize the module's ideological stance** — what does it encourage the developer to think about, and what does it hide?

The goal is not to produce a score but to surface structural issues that erode clarity over time. A module rated "Adequate" everywhere may be fine. A module rated "Strong" on coherence but "Weak" on clarity is sending a clear signal about where to invest effort.

---

## Review Log

| Module | Coherence | Clarity | Dependencies | Necessity | Reviewer Notes |
|--------|-----------|---------|--------------|-----------|----------------|
| | | | | | |
