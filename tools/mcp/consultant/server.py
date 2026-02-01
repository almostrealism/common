#!/usr/bin/env python3
"""
AR Consultant MCP Server

A documentation-aware assistant that sits between coding agents and the
AR documentation + memory systems.  Combines documentation retrieval,
semantic memory, and local LLM inference to provide grounded answers,
contextualized memory recall, and terminology-consistent memory storage.

Environment variables:
    AR_CONSULTANT_BACKEND      - "llamacpp", "ollama", "mlx", "passthrough", or "auto" (default)
    AR_CONSULTANT_LLAMACPP_URL - llama.cpp server URL (default: http://host.docker.internal:8080)
    AR_CONSULTANT_MODEL        - Ollama model name (default: qwen2.5-coder:32b-instruct-q4_K_M)
    AR_CONSULTANT_MLX_MODEL    - MLX model path (default: mlx-community/Qwen2.5-Coder-32B-Instruct-4bit)
    AR_CONSULTANT_OLLAMA_URL   - Ollama base URL (default: http://localhost:11434)
    AR_MEMORY_DATA_DIR         - Shared memory data directory
    AR_MEMORY_BACKEND          - Memory embedding backend
"""

import json
import logging
import os
import sys
import time
import uuid
from typing import Optional

# Allow importing sibling modules when run as a script
sys.path.insert(0, os.path.dirname(__file__))

from mcp.server.fastmcp import FastMCP

from docs_retriever import DocsRetriever
from inference import SYSTEM_PROMPT, create_backend
from memory_client import MemoryClient

logging.basicConfig(level=logging.INFO, format="%(name)s %(levelname)s: %(message)s")
log = logging.getLogger("ar-consultant")

# ---------------------------------------------------------------------------
# Initialize components
# ---------------------------------------------------------------------------

docs = DocsRetriever()
memory = MemoryClient()
llm = create_backend()

log.info("Consultant LLM backend: %s", llm.name)

# Session storage for multi-turn consultations
# session_id -> {"messages": [...], "topic": str, "created": float}
_sessions: dict[str, dict] = {}
_SESSION_TTL = 3600  # 1 hour


def _cleanup_sessions():
    """Remove expired sessions."""
    now = time.time()
    expired = [sid for sid, s in _sessions.items() if now - s["created"] > _SESSION_TTL]
    for sid in expired:
        del _sessions[sid]


# ---------------------------------------------------------------------------
# Prompt construction helpers
# ---------------------------------------------------------------------------

def _build_consult_prompt(question: str, doc_context: str, memory_context: str,
                          extra_context: Optional[str] = None) -> str:
    """Build the prompt for a consultation question."""
    parts = []

    if doc_context:
        parts.append(f"## Relevant Documentation\n\n{doc_context}")

    if memory_context:
        parts.append(f"## Relevant Memories from Prior Sessions\n\n{memory_context}")

    if extra_context:
        parts.append(f"## Additional Context from Agent\n\n{extra_context}")

    parts.append(f"## Question\n\n{question}")

    parts.append(
        "Answer the question using the documentation and memory context above. "
        "Be specific, cite module and class names, and keep the answer concise."
    )

    return "\n\n".join(parts)


def _build_recall_prompt(query: str, memories: list[dict], doc_context: str) -> str:
    """Build the prompt for memory recall summarization."""
    mem_text = ""
    for i, m in enumerate(memories, 1):
        score = m.get("score", "?")
        mem_text += f"### Memory {i} (similarity: {score})\n{m['content']}\n\n"

    parts = []
    if doc_context:
        parts.append(f"## Relevant Documentation\n\n{doc_context}")
    parts.append(f"## Retrieved Memories\n\n{mem_text}")
    parts.append(f"## Task\n\nThe agent searched for: \"{query}\"\n\n"
                 "Summarize the retrieved memories in the context of the "
                 "documentation. Highlight which memories are still accurate "
                 "and note any that may be outdated. Be concise.")

    return "\n\n".join(parts)


def _build_reformulate_prompt(raw_note: str, doc_context: str) -> str:
    """Build the prompt for memory reformulation."""
    parts = []
    if doc_context:
        parts.append(f"## Relevant Documentation\n\n{doc_context}")

    parts.append(f"## Agent Note (Raw)\n\n{raw_note}")
    parts.append(
        "## Task\n\n"
        "Reformulate the agent's note to be consistent with AR project "
        "terminology and documentation. Preserve the original intent but:\n"
        "- Use correct class names, method names, and module names\n"
        "- Add relevant context from the documentation\n"
        "- Fix any inaccuracies based on the documentation\n"
        "- Keep it concise (1-3 sentences)\n\n"
        "Return ONLY the reformulated note, nothing else."
    )

    return "\n\n".join(parts)


def _format_memory_context(memories: list[dict], max_entries: int = 3) -> str:
    """Format memory entries into a context string."""
    if not memories:
        return ""
    parts = []
    for m in memories[:max_entries]:
        parts.append(f"- {m['content']}")
    return "\n".join(parts)


# ---------------------------------------------------------------------------
# MCP Server and Tools
# ---------------------------------------------------------------------------

mcp = FastMCP("ar-consultant")


@mcp.tool()
def consult(question: str, context: Optional[str] = None) -> dict:
    """Ask the Consultant a question about the AR codebase.

    The Consultant searches documentation, retrieves relevant memories,
    and returns a synthesized answer grounded in project documentation.

    Args:
        question: The question to ask.
        context: Optional additional context (e.g., code snippet, error message).

    Returns:
        Dictionary with answer, sources, and related memories.
    """
    # Retrieve documentation context
    doc_context = docs.get_context_for_query(question)

    # Search memories for related prior knowledge
    memories = memory.search(query=question, namespace="default", limit=3)
    mem_context = _format_memory_context(memories)

    # Build prompt and generate
    prompt = _build_consult_prompt(question, doc_context, mem_context, context)
    answer = llm.generate(prompt, system=SYSTEM_PROMPT)

    # Extract source file references from doc results
    doc_results = docs.search(question, max_results=5)
    sources = list({r["file"] for r in doc_results})

    return {
        "answer": answer,
        "sources": sources,
        "related_memories": [
            {"content": m["content"], "score": m.get("score")}
            for m in memories
        ],
        "backend": llm.name,
    }


@mcp.tool()
def recall(query: str, namespace: str = "default", limit: int = 5) -> dict:
    """Search memories and return Consultant-summarized results.

    Unlike raw memory_search, this returns memories contextualized with
    relevant documentation. The Consultant highlights which memories are
    still accurate and flags any that may be outdated.

    Args:
        query: What to search for.
        namespace: Memory namespace to search.
        limit: Maximum number of memories to retrieve.

    Returns:
        Dictionary with summary, raw memories, and doc references.
    """
    memories = memory.search(query=query, namespace=namespace, limit=limit)

    if not memories:
        return {
            "summary": f"No memories found for '{query}' in namespace '{namespace}'.",
            "memories": [],
            "doc_references": [],
        }

    # Get documentation context related to the query
    doc_context = docs.get_context_for_query(query)

    # Summarize with the model
    prompt = _build_recall_prompt(query, memories, doc_context)
    summary = llm.generate(prompt, system=SYSTEM_PROMPT)

    doc_results = docs.search(query, max_results=5)
    doc_refs = list({r["file"] for r in doc_results})

    return {
        "summary": summary,
        "memories": [
            {
                "id": m.get("id"),
                "content": m["content"],
                "score": m.get("score"),
                "tags": m.get("tags"),
                "created_at": m.get("created_at"),
            }
            for m in memories
        ],
        "doc_references": doc_refs,
        "backend": llm.name,
    }


@mcp.tool()
def remember(
    content: str,
    namespace: str = "default",
    tags: Optional[list[str]] = None,
    source: Optional[str] = None,
) -> dict:
    """Store a memory after Consultant reformulation.

    The Consultant rewrites the content to be consistent with project
    terminology and documentation before storing. Both the original text
    and the reformulated version are persisted, preserving the agent's
    intent while adding documentation context.

    Args:
        content: The raw note to store.
        namespace: Memory namespace.
        tags: Optional tags for categorization.
        source: Optional source identifier (e.g., file path).

    Returns:
        Dictionary with both versions of the text and the entry ID.
    """
    # Get documentation context for the note's subject matter
    doc_context = docs.get_context_for_query(content)

    # Reformulate with the model
    prompt = _build_reformulate_prompt(content, doc_context)
    reformulated = llm.generate(prompt, system=SYSTEM_PROMPT, max_tokens=512)

    # Strip any wrapping the model might add
    reformulated = reformulated.strip().strip('"').strip("'")

    # Store both versions
    entry = memory.store_dual(
        original=content,
        reformulated=reformulated,
        namespace=namespace,
        tags=tags,
        source=source,
    )

    return {
        "reformulated": reformulated,
        "original": content,
        "entry_id": entry["id"],
        "namespace": namespace,
        "tags": tags,
        "backend": llm.name,
    }


@mcp.tool()
def search_docs(query: str, module: Optional[str] = None) -> dict:
    """Search project documentation with a Consultant-generated summary.

    Searches the AR documentation and returns both raw results and a
    synthesized summary from the Consultant.

    Args:
        query: Search term or phrase.
        module: Optional module name to restrict search.

    Returns:
        Dictionary with summary and raw search results.
    """
    results = docs.search(query, module=module, max_results=8)

    if not results:
        return {
            "summary": f"No documentation found for '{query}'.",
            "results": [],
        }

    # Build context from results
    context_parts = []
    for r in results:
        context_parts.append(f"[{r['file']}:{r['line']}]\n{r['context']}")
    context = "\n\n---\n\n".join(context_parts)

    prompt = (
        f"## Documentation Search Results\n\n{context}\n\n"
        f"## Task\n\nThe agent searched for: \"{query}\"\n\n"
        "Provide a brief summary of what the documentation says about this "
        "topic. Reference specific files, classes, or methods. Be concise."
    )
    summary = llm.generate(prompt, system=SYSTEM_PROMPT)

    return {
        "summary": summary,
        "results": [
            {"file": r["file"], "line": r["line"], "context": r["context"]}
            for r in results
        ],
        "backend": llm.name,
    }


@mcp.tool()
def start_consultation(topic: str) -> dict:
    """Begin a multi-turn consultation session.

    Opens a session where the agent can have a back-and-forth conversation
    with the Consultant about a topic. The Consultant retrieves relevant
    documentation and maintains conversation context.

    Args:
        topic: The topic or initial question for the consultation.

    Returns:
        Dictionary with session_id and the Consultant's initial response.
    """
    _cleanup_sessions()

    session_id = str(uuid.uuid4())[:8]

    # Get initial documentation context
    doc_context = docs.get_context_for_query(topic)
    memories = memory.search(query=topic, namespace="default", limit=3)
    mem_context = _format_memory_context(memories)

    prompt = _build_consult_prompt(topic, doc_context, mem_context)
    response = llm.generate(prompt, system=SYSTEM_PROMPT)

    _sessions[session_id] = {
        "topic": topic,
        "messages": [
            {"role": "user", "content": topic},
            {"role": "assistant", "content": response},
        ],
        "doc_context": doc_context,
        "created": time.time(),
    }

    return {
        "session_id": session_id,
        "response": response,
        "backend": llm.name,
    }


@mcp.tool()
def continue_consultation(session_id: str, message: str) -> dict:
    """Continue an existing consultation session.

    Sends a follow-up message in an ongoing conversation. The Consultant
    has access to the full conversation history and can retrieve additional
    documentation as needed.

    Args:
        session_id: The session ID from start_consultation.
        message: The follow-up message or question.

    Returns:
        Dictionary with the Consultant's response and session_id.
    """
    if session_id not in _sessions:
        return {
            "error": f"Session '{session_id}' not found or expired.",
            "session_id": session_id,
        }

    session = _sessions[session_id]

    # Optionally fetch more doc context for the new message
    new_doc_context = docs.get_context_for_query(message, max_chunks=3, max_total_chars=4000)

    # Build prompt with conversation history
    history_text = ""
    for msg in session["messages"]:
        role = msg["role"].upper()
        history_text += f"[{role}]: {msg['content']}\n\n"

    prompt_parts = []
    if session.get("doc_context"):
        prompt_parts.append(f"## Initial Documentation Context\n\n{session['doc_context']}")
    if new_doc_context:
        prompt_parts.append(f"## Additional Documentation for Follow-up\n\n{new_doc_context}")
    prompt_parts.append(f"## Conversation History\n\n{history_text}")
    prompt_parts.append(f"## New Message\n\n{message}")
    prompt_parts.append("Continue the conversation. Answer the follow-up question or "
                        "address the new point using the documentation context and "
                        "conversation history.")

    prompt = "\n\n".join(prompt_parts)
    response = llm.generate(prompt, system=SYSTEM_PROMPT)

    session["messages"].append({"role": "user", "content": message})
    session["messages"].append({"role": "assistant", "content": response})

    return {
        "response": response,
        "session_id": session_id,
        "turn": len(session["messages"]) // 2,
        "backend": llm.name,
    }


@mcp.tool()
def end_consultation(
    session_id: str,
    store_summary: bool = True,
    namespace: str = "default",
) -> dict:
    """End a consultation session.

    Optionally stores a summary of the conversation as a memory entry
    so future sessions can benefit from the discussion.

    Args:
        session_id: The session ID to end.
        store_summary: Whether to store a summary as a memory (default: True).
        namespace: Memory namespace for the summary.

    Returns:
        Dictionary with the session summary and optional memory entry ID.
    """
    if session_id not in _sessions:
        return {
            "error": f"Session '{session_id}' not found or expired.",
            "session_id": session_id,
        }

    session = _sessions.pop(session_id)

    # Generate a summary of the conversation
    history_text = ""
    for msg in session["messages"]:
        role = msg["role"].upper()
        history_text += f"[{role}]: {msg['content']}\n\n"

    prompt = (
        f"## Consultation Conversation\n\n{history_text}\n\n"
        "## Task\n\n"
        "Summarize this consultation in 2-4 sentences. Capture the key "
        "question(s) asked, the answers given, and any decisions or "
        "recommendations made. This summary will be stored as a memory "
        "entry for future reference."
    )
    summary = llm.generate(prompt, system=SYSTEM_PROMPT, max_tokens=512)

    result = {
        "summary": summary,
        "session_id": session_id,
        "topic": session["topic"],
        "turns": len(session["messages"]) // 2,
        "backend": llm.name,
    }

    if store_summary:
        entry = memory.store(
            content=summary,
            namespace=namespace,
            tags=["consultation", "summary"],
            source=f"consultation:{session_id}",
        )
        result["memory_entry_id"] = entry["id"]

    return result


@mcp.tool()
def consultant_status() -> dict:
    """Check the Consultant's status and backend configuration.

    Returns:
        Dictionary with backend info, active sessions, and health status.
    """
    return {
        "backend": llm.name,
        "backend_available": llm.available,
        "active_sessions": len(_sessions),
        "session_ttl_seconds": _SESSION_TTL,
        "docs_modules_available": len(docs._all_doc_files()),
    }


if __name__ == "__main__":
    mcp.run()
