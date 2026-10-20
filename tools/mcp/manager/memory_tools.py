"""
Memory and documentation tools for the AR Manager MCP server.

``consult`` lives here rather than in a module of its own because it
answers from the same two sources ``memory_recall`` does — the
documentation corpus and the stored notes — and shares their degradation
contract.

Split out of ``server.py`` for length. The tools are unchanged; only their
address is. See ``tracker_tools`` for the conventions this module follows —
the ``_tools`` suffix that makes it visible to tool discovery, and reaching
anything defined in ``server`` through the module rather than by import, so
the suite's patches still apply. Helpers and constants stay in ``server.py``.
"""

import logging
from typing import Optional

import consult_engine
import repo_config
import server
from consult_conversations import ConversationError, ConversationStore
from memory_text import prefers_reformulated, present, projected
from server import mcp

# Live consult conversations. In-process: a restart ends them, which the
# conversation contract states (an unknown id is reported as expired).
_conversations = ConversationStore()


@mcp.tool()
def memory_recall(
    query: str,
    namespace: str = "default",
    limit: int = 5,
    repo_url: str = "",
    branch: str = "",
    workstream_id: str = "",
    include_messages: bool = False,
    scope: str = "repo",
    reformulated: bool = False,
) -> dict:
    """Search agent memories with optional LLM synthesis.

    Retrieves semantically similar memories from the ar-memory server.
    If an LLM backend is available, provides a synthesized summary.
    Can resolve repo_url/branch from workstream_id if provided.

    Memory text is returned as its author wrote it. Memories stored through
    the Consultant's ``remember`` tool also carry a rewritten version
    ("reformulation"), a beta feature whose quality is still under
    development; ask for it with ``reformulated`` when evaluating the
    rewrite itself.

    By default, results are scoped to the current repository to avoid
    returning unrelated memories from other projects.

    Args:
        query: Natural language search query.
        namespace: Memory namespace to search.
        limit: Maximum number of memories to retrieve.
        repo_url: Optional repository URL filter.
        branch: Optional branch name filter.
        workstream_id: Optional workstream to resolve repo/branch from.
        include_messages: If true, also search the "messages" namespace
            and merge results. Defaults to false.
        scope: Search scope — ``repo`` (default) searches the current
            repository across all branches; ``branch`` narrows to the
            current branch within the repo; ``all`` searches all repos.
        reformulated: When true, return the Consultant's rewrite of each
            memory instead of the original text, with the original included
            alongside it for comparison. Beta — off by default.

    Returns:
        Dictionary with memories and optional summary. Each memory carries
        ``text_source`` recording which version of the text is shown. When a
        documentation corpus is available the summary is grounded in it too,
        and ``doc_references`` lists the documents consulted.
    """
    server._require_scope("memory-read")
    err = server._check_short_strings(
        query=query, namespace=namespace, repo_url=repo_url,
        branch=branch, workstream_id=workstream_id,
    )
    if err:
        return err
    server._audit("memory_recall", query=query, namespace=namespace, scope=scope)

    client = server._get_memory_client()
    if client is None:
        return {
            "ok": False,
            "error": "ar-memory server unavailable",
            "next_steps": [
                "Start ar-memory: python tools/mcp/memory/server.py --http-only",
                "Or set AR_MEMORY_URL to point to a running instance",
            ],
        }

    effective_repo, effective_branch, err = server._resolve_scope_context(
        scope=scope, repo_url=repo_url, branch=branch,
        workstream_id=workstream_id,
    )
    if err:
        return err

    try:
        memories = client.search(
            query=query,
            namespace=namespace,
            limit=limit,
            repo_url=effective_repo or None,
            branch=effective_branch or None,
        )
    except ConnectionError as e:
        return {"ok": False, "error": f"Memory search failed: {e}"}

    # Messages are a non-semantic namespace: retrieved by branch/recency, not
    # embedded in the FAISS index (see MemoryStore NON_SEMANTIC_NAMESPACES).
    # When both repo and branch are known, merge the most recent messages in
    # by recency. They are appended after the semantic results and capped, so
    # they never displace a primary (semantically ranked) hit. Messages are
    # most completely retrieved via workstream_context.
    if (include_messages and namespace != "messages"
            and effective_repo and effective_branch):
        try:
            msg_memories = client.search_by_branch(
                repo_url=effective_repo,
                branch=effective_branch,
                namespace="messages",
                limit=limit,
            )
            if msg_memories:
                memories = (memories + msg_memories)[:limit]
        except ConnectionError:
            pass  # Non-critical: proceed without messages

    if not memories:
        return {
            "ok": True,
            "summary": f"No memories found for '{query}' in namespace '{namespace}'.",
            "memories": [],
        }

    memories, notice = present(
        memories,
        reformulated=reformulated or repo_config.repo_setting(
            effective_repo, "preferReformulatedOnRead", prefers_reformulated(),
        ),
    )

    # Ground the summary in documentation as well as memories, so a memory
    # that has gone stale against the current docs can be spotted. Both the
    # corpus and the model are optional: either being absent costs part of
    # the summary, never the memories.
    from consult_evidence import EvidenceList, TIER_UNTIERED

    evidence = EvidenceList(consult_engine.corpus_identity(
        consult_engine.snapshot_index()))
    evidence.show_empty_documentation = False
    doc_refs = []
    docs = server._get_docs()
    if docs is not None:
        try:
            doc_retrieval = docs.get_context_for_query(query)
            for r in doc_retrieval.get("markdown_results", []):
                evidence.add_doc(r)
            doc_refs = sorted({
                r["file"] for r in doc_retrieval.get("markdown_results", [])
            })
            doc_refs.extend(doc_retrieval.get("html_refs", []))
        except OSError as e:
            logging.getLogger("ar-manager").warning(
                "Documentation retrieval failed for %r: %s", query, e)
    for m in memories:
        evidence.add_note(m, TIER_UNTIERED)

    # Attempt LLM synthesis. The memories are the substance of the response
    # and are returned either way; synthesis is a convenience over them.
    summary = None
    degraded_reason = None
    llm = server._get_llm()
    if llm is None:
        degraded_reason = "no inference backend could be constructed"
    else:
        try:
            from inference import SYSTEM_PROMPT, build_consult_prompt

            # The same evidence layout consult uses, with a summary task in
            # place of the answering instructions. Note scores are L2
            # distances (lower is closer), and the layout labels them so.
            prompt = build_consult_prompt(
                f"Summarize what is known about: \"{query}\"", evidence,
                instructions=(
                    "## Task\n\nSummarize the retrieved notes. Highlight key "
                    "findings and any decisions or progress notes, citing "
                    "items by ID. Where the documentation above contradicts "
                    "a note, say so — a note can be stale. Be concise (2-4 "
                    "sentences)."))
            # synthesize() reports an unreachable model as a value rather
            # than raising, and re-probes health so a recovered backend is
            # picked up without restarting this server.
            synthesis = llm.synthesize(prompt, system=SYSTEM_PROMPT)
            if synthesis.degraded:
                degraded_reason = synthesis.reason
            else:
                body, _, _, _ = consult_engine.parse_trailer(synthesis.text)
                summary = evidence.expand(body) or None
        except Exception as e:
            degraded_reason = f"LLM synthesis failed: {e}"

    result = {
        "ok": True,
        "memories": [
            projected(m, (
                "id", "content", "score", "tags", "created_at",
                "repo_url", "branch",
            ))
            for m in memories
        ],
        "count": len(memories),
        "next_steps": [
            "Use workstream_context for a full branch history",
            "Use memory_store to add new memories",
        ],
    }

    if doc_refs:
        result["doc_references"] = doc_refs

    if summary:
        result["summary"] = summary
    elif degraded_reason:
        result["degraded"] = True
        result["note"] = (
            f"No summary was synthesized ({degraded_reason}). The memories "
            "field is complete and unaffected — memory retrieval does not "
            "depend on the inference backend."
        )
    if notice:
        result["notice"] = notice

    return result

@mcp.tool()
def memory_store(
    content: str,
    workstream_id: str = "",
    repo_url: str = "",
    branch: str = "",
    namespace: str = "default",
    tags: Optional[list[str]] = None,
    source: Optional[str] = None,
    reformulate: Optional[bool] = None,
) -> dict:
    """Store a memory from an external client.

    Either ``workstream_id`` or (``repo_url`` + ``branch``) is required to
    identify the branch context for the memory.  When neither is supplied,
    the workstream bound to the in-flight request's HMAC temp token is
    used — so a job-scoped agent call with only ``content`` succeeds and
    stores the memory against the job's workstream branch automatically.

    When reformulation is enabled the note is rewritten to match project
    terminology before storage, and **both** versions are kept: the rewrite
    is what gets embedded and ranked, the text you wrote is preserved
    alongside it and is what retrieval returns by default.

    Reformulation never costs you the memory. If no inference backend is
    reachable, your text is stored unreformulated and the response says so.

    Args:
        content: The text content to store.
        workstream_id: Resolves to repo_url/branch via workstream config.
        repo_url: Repository URL.
        branch: Branch name.
        namespace: Logical grouping.
        tags: Optional tags for categorization.
        source: Optional source identifier.
        reformulate: Whether to rewrite the note before storing. Defaults to
            the repository's ``reformulateOnStore`` setting.

    Returns:
        Dictionary with the created entry. ``reformulated_stored`` reports
        whether a rewrite was actually stored.
    """
    server._require_scope("memory-write")
    err = server._check_length(content, "content", server.MAX_PROMPT_LEN)
    if err:
        return err
    err = server._check_short_strings(
        workstream_id=workstream_id, repo_url=repo_url,
        branch=branch, namespace=namespace,
    )
    if err:
        return err
    server._audit("memory_store", namespace=namespace, content_len=len(content))

    effective_repo, effective_branch, err = server._resolve_branch_context(
        workstream_id=workstream_id, repo_url=repo_url, branch=branch,
    )
    if err:
        return err

    # Archived workstreams are read-only: their memories stay readable but the
    # record is frozen. A memory keyed to an archived round's branch becomes
    # part of what workstream_context and memory_recall return for it, so
    # appending one mutates the frozen record — the same category of write the
    # send_message guard refuses on the conversation history. Resolving an
    # archived workstream's ownership now succeeds, so this explicit refusal is
    # what keeps new memories off an archived round.
    #
    # The memory lands on (effective_repo, effective_branch) as resolved by
    # _resolve_branch_context above — never on the raw arguments. Its precedence
    # is explicit repo_url + branch first, then workstream_id (whose repoUrl
    # supplies the repo while an explicit branch argument is kept as an
    # override), then the in-flight request's HMAC temp token. So this guard
    # resolves the archived target from those effective values, which is the
    # round that is actually written in every addressing form: resolving from
    # the raw workstream_id instead would miss a caller passing a live
    # workstream_id alongside an archived branch override (no repo_url) — the
    # branch override wins in _resolve_branch_context and gets the memory while
    # the live id would pass a guard keyed on the id. memory_store requires a
    # branch (require_branch defaults to True), so both effective values are
    # non-empty once the error check above has passed.
    #
    # A temp token can briefly outlive the job that minted it, so the
    # token-fallback round (reached through effective_* here) is guarded too
    # rather than trusted to never name an archived round. Unarchive it first
    # to record against it.
    archived_err = server._archived_branch_error(
        "store a memory", effective_repo, effective_branch)
    if archived_err:
        return archived_err

    client = server._get_memory_client()
    if client is None:
        return {
            "ok": False,
            "error": "ar-memory server unavailable",
            "next_steps": [
                "Start ar-memory: python tools/mcp/memory/server.py --http-only",
            ],
        }

    want_reformulation = (
        reformulate if reformulate is not None
        else repo_config.repo_setting(effective_repo, "reformulateOnStore")
    )

    rewrite = None
    degraded_reason = None
    if want_reformulation:
        llm = server._get_llm()
        if llm is None:
            degraded_reason = "no inference backend could be constructed"
        else:
            synthesis = llm.reformulate(content)
            if synthesis.degraded:
                degraded_reason = synthesis.reason
            else:
                rewrite = synthesis.text

    try:
        if rewrite:
            entry = client.store_dual(
                original=content,
                reformulated=rewrite,
                repo_url=effective_repo,
                branch=effective_branch,
                namespace=namespace,
                tags=tags,
                source=source,
            )
        else:
            # Storing the author's own words is always safe. The refusal the
            # Consultant used to apply here was guarding against writing a
            # backend-down passthrough dump into the corpus — model output,
            # not author text — and MemoryStore.is_passthrough_dump rejects
            # that shape at the store regardless.
            entry = client.store(
                content=content,
                repo_url=effective_repo,
                branch=effective_branch,
                namespace=namespace,
                tags=tags,
                source=source,
            )
    except ConnectionError as e:
        return {"ok": False, "error": f"Memory store failed: {e}"}

    entry["ok"] = True
    entry["reformulated_stored"] = rewrite is not None
    if want_reformulation and degraded_reason:
        entry["degraded"] = True
        entry["note"] = (
            f"Stored your original text unreformulated ({degraded_reason}). "
            "The memory is saved and searchable; only the rewrite is missing."
        )
    entry["next_steps"] = [
        "Use memory_recall to search for this and other memories",
        "Use workstream_context to see all memories for this branch",
    ]
    return entry

@mcp.tool()
def memory_namespaces(
    repo_url: str = "",
    branch: str = "",
    workstream_id: str = "",
    scope: str = "repo",
) -> dict:
    """List every memory namespace with its entry count and latest-write time.

    Use this to discover where memories live and when each namespace was last
    written — for example to find which namespace a recent hand-off note landed
    in, without guessing namespace names and issuing a separate ``memory_recall``
    for each. Namespaces are ordered most-recently-written first, so the
    freshest activity is at the top.

    Args:
        repo_url: Optional repository URL filter.
        branch: Optional branch name filter.
        workstream_id: Optional workstream to resolve repo/branch from.
        scope: Which memories to count — ``repo`` (default) covers the
            current repository across all branches; ``branch`` narrows to
            one branch of it; ``all`` counts every repository in the store.

    Returns:
        Dictionary with ``namespaces`` (a list of
        ``{namespace, count, latest_created_at, latest_id}`` dicts, newest
        first) and ``count`` (the number of namespaces).
    """
    server._require_scope("memory-read")
    err = server._check_short_strings(
        repo_url=repo_url, branch=branch, workstream_id=workstream_id,
    )
    if err:
        return err
    server._audit("memory_namespaces", scope=scope, branch=branch)

    client = server._get_memory_client()
    if client is None:
        return {
            "ok": False,
            "error": "ar-memory server unavailable",
            "next_steps": [
                "Start ar-memory: python tools/mcp/memory/server.py --http-only",
                "Or set AR_MEMORY_URL to point to a running instance",
            ],
        }

    effective_repo, effective_branch, err = server._resolve_scope_context(
        scope=scope, repo_url=repo_url, branch=branch,
        workstream_id=workstream_id,
    )
    if err:
        return err

    try:
        stats = client.namespace_stats(
            repo_url=effective_repo or None,
            branch=effective_branch or None,
        )
    except ConnectionError as e:
        return {"ok": False, "error": f"Namespace lookup failed: {e}"}

    return {
        "ok": True,
        "namespaces": stats,
        "count": len(stats),
        "next_steps": [
            "Use memory_recall with a namespace from this list to read it",
            "Use workstream_context for the full narrative of a branch",
        ],
    }

@mcp.tool()
def consult(
    question: str,
    context: str = "",
    keywords: Optional[list[str]] = None,
    repo_url: str = "",
    branch: str = "",
    workstream_id: str = "",
    conversation_id: str = "",
) -> dict:
    """Ask a question about the codebase, answered from cited evidence.

    Searches the documentation corpus, the snapshot index of the source tree
    and notes from prior sessions, and returns an answer that cites each
    claim by evidence ID. The corpus ships inside this server, so this works
    from any repository rather than only from a checkout.

    Every evidence item carries a tier: ``A`` (documentation or index fact,
    present fact for the snapshot), ``B`` (current note — may answer what the
    documentation lacks, and the answer then says so), ``B'`` (true only on
    its own unmerged branch), ``C``/``C✗`` (historical or stale note — history
    only). Citations such as ``[D1: path:start-end]`` name the range the model
    actually saw. A sentence the citation verifier cannot match to the
    evidence it cites — or an uncited specific found in no evidence at all —
    is marked ``[unsupported]`` and listed in ``citation_warnings``; treat it
    as unverified.

    A missing inference backend costs the synthesized answer and nothing else:
    ``sources``, ``evidence``, ``html_refs`` and ``related_memories`` are the
    search results themselves and are returned either way. Read them directly
    when ``degraded`` is set.

    Args:
        question: The question to ask.
        context: Optional extra context — a code snippet, an error message.
        keywords: Optional search terms, used instead of extracting them from
            the question. Multi-word phrases work far better than individual
            common words: ["Features mixin", "CollectionFeatures"] rather than
            ["Features", "mixin", "default", "interface"], which match too
            many documents to narrow anything.
        repo_url: Repository whose notes to draw on. Defaults to the caller's
            workstream context.
        branch: Optional branch filter for those notes.
        workstream_id: Optional workstream to resolve repo/branch from.
        conversation_id: Continue an earlier consult as a follow-up. Empty
            starts a new conversation. Follow-ups keep the evidence IDs of
            earlier turns. Only job tokens can hold conversations; a static
            token is always stateless and is refused a ``conversation_id``.

    Returns:
        Dictionary with ``answer`` (or ``note`` when nothing could be
        synthesized), ``answer_basis`` (documentation, documentation+notes,
        notes or none), ``evidence``, ``citation_warnings``,
        ``uncited_claims``, ``conflicts``, ``corpus`` (snapshot commit and
        build time), ``sources``, ``html_refs`` and ``related_memories``;
        ``history`` when a historical note supplied rationale; ``abstained``,
        ``abstained_with_evidence``, ``nonexistent`` and ``searched`` on an
        abstention; ``conversation_id`` and ``turn`` for job-token callers.
    """
    server._require_scope("memory-read")
    err = server._check_length(question, "question", server.MAX_PROMPT_LEN)
    if err:
        return err
    # context is concatenated into the prompt alongside the question, so it
    # carries the same bound; an unbounded snippet would push the retrieved
    # documentation out of the model's window rather than fail outright.
    err = server._check_length(context, "context", server.MAX_PROMPT_LEN)
    if err:
        return err
    err = server._check_short_strings(
        repo_url=repo_url, branch=branch, workstream_id=workstream_id,
        conversation_id=conversation_id,
    )
    if err:
        return err
    server._audit("consult", question=question)

    docs = server._get_docs()
    if docs is None:
        return {
            "ok": False,
            "error": "No documentation corpus is available to this server.",
            "next_steps": [
                "Confirm AR_DOCS_DIR points at the corpus baked into the image",
                "Use memory_recall if you only need prior notes",
            ],
        }

    # Prior notes are scoped to the caller's repository. A scope that cannot
    # be resolved stops note retrieval altogether (reported as degraded)
    # rather than searching with whatever filters remain, which would reach
    # notes outside the requested repository.
    effective_repo, effective_branch, scope_err = server._resolve_scope_context(
        scope="repo", repo_url=repo_url, branch=branch,
        workstream_id=workstream_id,
    )
    caller = consult_engine.resolve_caller(repo_url, branch, workstream_id)

    ws_id = server._get_token_workstream_id() or ""
    job_id = server._get_token_job_id() or ""
    if not (ws_id and job_id):
        if conversation_id:
            return {"ok": False,
                    "error": "conversations require a job token",
                    "next_steps": ["call consult without conversation_id"]}
        return consult_engine.ConsultTurn(
            question, context, keywords, effective_repo, effective_branch,
            scope_err is not None, caller,
        ).run(docs, server._get_memory_client(), server._get_llm())

    try:
        with _conversations.turn(conversation_id, f"{ws_id}/{job_id}",
                                 effective_repo, effective_branch) as conv:
            return consult_engine.ConsultTurn(
                question, context, keywords, effective_repo, effective_branch,
                scope_err is not None, caller, conversation=conv,
            ).run(docs, server._get_memory_client(), server._get_llm())
    except ConversationError as e:
        return e.response
