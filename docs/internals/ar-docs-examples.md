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
