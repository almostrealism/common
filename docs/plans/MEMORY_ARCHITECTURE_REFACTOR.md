# Memory Architecture Refactoring — Remaining Work

**Original plan created**: 2026-03-03
**Core refactoring completed**: 2026-03-07

Phases 1–5 and 7 are complete. The memory subsystem is now centralized as an
HTTP service with shared clients in ar-consultant and ar-manager. See
`tools/mcp/memory/README.md` for the current architecture.

---

## Outstanding: Documentation Updates (Phase 6)

README files across `tools/mcp/` subdirectories have been partially updated
but may still reference outdated patterns (e.g., MCP-only ar-memory, direct
store imports). A sweep of each README is needed to ensure consistency with
the HTTP architecture.

---

## Future: Request History Centralization

ar-consultant maintains a local request history log (`data/history.db`) that
records every tool invocation with input parameters, doc chunks retrieved,
LLM prompts/responses, and latency metrics. This is used for quality
evaluation, retrieval accuracy assessment, and fine-tuning dataset
construction.

**Recommended approach**: Add dedicated history endpoints to ar-memory
(`POST /api/history/record`, `POST /api/history/search`,
`GET /api/history/export`) rather than overloading the memory namespace,
because history records have different query patterns and need structured
access to prompts/responses for fine-tuning export.

**Tasks**:
1. Add history table and endpoints to ar-memory HTTP server
2. Update ar-consultant to use centralized history instead of local history.db
3. Add history recording to ar-manager
4. Create history migration tool (local history.db → centralized server)
5. Update `export_request_history` to pull from centralized server
