# AR-Docs MCP Usage - Detailed Examples

This document contains detailed examples for using the ar-docs MCP.
See the main [CLAUDE.md](../../CLAUDE.md) for the rules.

## Example: Wrong vs Right Behavior

**Example of WRONG behavior:**
```
User: "The prototype discovery doesn't show file paths"
Claude: "The protobuf schema only stores MD5 hash, not file path..."
```
This is WRONG because Claude did NOT search ar-docs to understand how the actual application handles this.

**Example of CORRECT behavior:**
```
User: "The prototype discovery doesn't show file paths"
Claude: [Calls mcp__ar-docs__search_ar_docs query:"AudioLibrary file path identifier"]
Claude: [Calls mcp__ar-docs__read_ar_module module:"audio"]
Claude: [Now understands how it actually works before responding]
```

## Specific Scenarios

**Debugging CI/test failures:**
```
WRONG: User reports CI failure -> immediately run git log/diff -> read test file -> speculate
RIGHT: User reports CI failure -> search ar-docs for component names -> read module docs -> THEN investigate changes
```

**Why this matters for debugging:** If you don't understand the architecture of the failing component, you will:
- Chase red herrings (changes that look suspicious but are unrelated)
- Miss the actual cause (because you don't know what the component depends on)
- Waste time reading irrelevant code

**Concrete example:**
```
User: "OobleckComponentTests is failing in CI with memory issues"

WRONG:
1. git log --oneline origin/develop..HEAD  <- Looking at changes without understanding Oobleck
2. git diff -- some_file.java             <- Random exploration
3. Read OobleckComponentTests.java        <- Still don't know architecture

RIGHT:
1. mcp__ar-docs__search_ar_docs query:"Oobleck decoder"
2. mcp__ar-docs__read_ar_module module:"ml"
3. Now understand: Oobleck is an audio autoencoder, decoder blocks use WNConv, Snake activation, etc.
4. THEN: git log, git diff, Read files - now you know what to look for
```

**Infrastructure changes (tests, build, framework classes):**
```
WRONG: See TestDepthRule in source, assume how it works, add @Rule manually
RIGHT: Search ar-docs first -> Learn TestDepthRule is INTERNAL to TestSuiteBase
```

**API discovery (finding operations, interfaces, utilities):**
```
WRONG: Grep source for "sin" -> Don't find it -> Conclude "doesn't exist"
RIGHT: mcp__ar-docs__read_quick_reference -> Find sin/cos in GeometryFeatures
```

**Understanding data flow:**
```
WRONG: Read one class -> Make assumptions -> Write incorrect code
RIGHT: Search ar-docs -> Read module docs -> Trace actual data flow -> Understand
```
