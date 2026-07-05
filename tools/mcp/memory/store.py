"""
Memory store backed by SQLite (metadata) and FAISS (vector search).

Each namespace gets its own FAISS index file and ID mapping file.
"""

import json
import logging
import os
import re
import sqlite3
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

import faiss
import numpy as np

from embedder import Embedder

log = logging.getLogger(__name__)

# A namespace becomes a filename component ("<ns>.index", "<ns>.ids.json").
# Restrict to characters that are safe across filesystems and cannot smuggle
# path separators or control characters from a malformed tool argument. A
# single bad value (e.g. an XML literal pasted into the namespace field) used
# to brick startup by making the FAISS index path uncreatable.
_NAMESPACE_RE = re.compile(r"^[A-Za-z0-9._-]{1,64}$")

# The exact leader emitted by PassthroughBackend (tools/mcp/common/inference.py)
# when no inference model is reachable. Content that begins with this is a
# backend-down dump — raw documentation with a notice — not real knowledge.
# A census of the corpus found 36 such entries stored as memories; they lead
# recall for large queries and are pure noise. Guarding here, at the single
# chokepoint every store passes through, stops new ones from any caller
# (interactive remember and FlowTree jobs alike).
_PASSTHROUGH_BANNER = "[Consultant model not available. Returning raw context.]"


def is_passthrough_dump(content: Optional[str]) -> bool:
    """True when content is a backend-down passthrough dump.

    Anchored to the start of the content so a memory that merely *quotes*
    the banner (e.g. a QC audit note) is not mistaken for an actual dump,
    which always leads with it.
    """
    return (content or "").lstrip().startswith(_PASSTHROUGH_BANNER)


def _parse_non_semantic_namespaces() -> frozenset:
    """Namespaces stored for branch/recency retrieval only, not embedded.

    ``messages`` is inter-agent chatter — the majority of the corpus — that
    is retrieved by branch via :meth:`MemoryStore.search_by_branch`
    (``workstream_context``) and never usefully by semantic similarity.
    Embedding it wastes compute and memory and bloats the FAISS index loaded
    at startup for no retrieval benefit. Entries in these namespaces are
    still written to SQLite, so branch/recency retrieval keeps working; they
    are simply excluded from the semantic index.

    Configurable via ``AR_MEMORY_NON_SEMANTIC_NAMESPACES`` (comma-separated);
    defaults to ``messages``. An empty value disables the exclusion.
    """
    raw = os.environ.get("AR_MEMORY_NON_SEMANTIC_NAMESPACES", "messages")
    return frozenset(name.strip() for name in raw.split(",") if name.strip())


NON_SEMANTIC_NAMESPACES = _parse_non_semantic_namespaces()


def _validate_namespace(namespace: str) -> str:
    if not isinstance(namespace, str) or not _NAMESPACE_RE.match(namespace):
        raise ValueError(
            f"invalid namespace: must match {_NAMESPACE_RE.pattern} "
            f"(got {namespace!r})"
        )
    return namespace


def _normalize_repo_url(url: Optional[str]) -> Optional[str]:
    """Normalize a repository URL to the canonical SSH form.

    Converts HTTPS URLs (https://github.com/org/repo) to SSH format
    (git@github.com:org/repo.git) so that exact-match comparisons
    work regardless of which format was used at storage time.
    The SSH form is canonical because all checkouts use SSH keys.
    """
    if not url:
        return url
    normalized = url.strip().rstrip("/")
    # HTTPS → SSH: https://github.com/org/repo → git@github.com:org/repo
    if normalized.startswith("https://") or normalized.startswith("http://"):
        without_scheme = normalized.split("://", 1)[1]
        slash_idx = without_scheme.find("/")
        if slash_idx > 0:
            host = without_scheme[:slash_idx]
            path = without_scheme[slash_idx + 1:]
            normalized = f"git@{host}:{path}"
    # Ensure trailing .git
    if not normalized.endswith(".git"):
        normalized += ".git"
    return normalized


class MemoryStore:
    """Persistent memory store combining SQLite for metadata and FAISS for vector search."""

    def __init__(self, embedder: Embedder, data_dir: str):
        self._embedder = embedder
        self._data_dir = Path(data_dir)
        self._data_dir.mkdir(parents=True, exist_ok=True)

        self._db_path = self._data_dir / "memory.db"
        self._conn = sqlite3.connect(str(self._db_path))
        self._conn.row_factory = sqlite3.Row
        self._init_db()

        # namespace -> (faiss.Index, list[int])  where list[int] is SQLite rowids
        self._indices: dict[str, tuple[faiss.Index, list[int]]] = {}
        self._load_all_indices()
        # Drop any FAISS files for namespaces that were embedded before being
        # designated non-semantic (e.g. the existing ~6.7k `messages` rows).
        self._purge_non_semantic_indices()

    def _is_semantic(self, namespace: str) -> bool:
        """True when a namespace participates in the semantic FAISS index."""
        return namespace not in NON_SEMANTIC_NAMESPACES

    def _purge_non_semantic_indices(self) -> None:
        """Remove stale on-disk FAISS files for non-semantic namespaces.

        A namespace may have been embedded before it was designated
        non-semantic. Its index files are now dead weight that must not be
        loaded or searched; delete them and drop any in-memory handle. The
        SQLite rows are untouched, so ``search_by_branch`` retrieval keeps
        working.
        """
        for namespace in NON_SEMANTIC_NAMESPACES:
            self._indices.pop(namespace, None)
            for path in (self._index_path(namespace), self._ids_path(namespace)):
                try:
                    if path.exists():
                        path.unlink()
                        log.info("purged non-semantic index file: %s", path)
                except OSError as exc:
                    log.warning("could not purge %s: %s", path, exc)

    def _init_db(self):
        self._conn.execute("""
            CREATE TABLE IF NOT EXISTS entries (
                id TEXT PRIMARY KEY,
                namespace TEXT NOT NULL,
                content TEXT NOT NULL,
                tags TEXT,
                source TEXT,
                created_at TEXT NOT NULL,
                repo_url TEXT,
                branch TEXT
            )
        """)
        self._conn.execute("""
            CREATE INDEX IF NOT EXISTS idx_entries_namespace
            ON entries(namespace)
        """)
        self._conn.commit()

        # Migrate existing databases that lack the repo_url/branch columns
        # (must run BEFORE creating indexes that reference those columns)
        self._migrate_add_columns()

        self._conn.execute("""
            CREATE INDEX IF NOT EXISTS idx_entries_repo_branch
            ON entries(repo_url, branch)
        """)
        self._conn.commit()

    def _migrate_add_columns(self):
        """Add repo_url and branch columns if they don't exist yet (schema migration)."""
        cursor = self._conn.execute("PRAGMA table_info(entries)")
        columns = {row[1] for row in cursor.fetchall()}
        if "repo_url" not in columns:
            self._conn.execute("ALTER TABLE entries ADD COLUMN repo_url TEXT")
        if "branch" not in columns:
            self._conn.execute("ALTER TABLE entries ADD COLUMN branch TEXT")
        self._conn.commit()
        self._migrate_normalize_repo_urls()

    def _migrate_normalize_repo_urls(self):
        """Normalize existing repo_url values to canonical SSH form."""
        rows = self._conn.execute(
            "SELECT rowid, repo_url FROM entries WHERE repo_url IS NOT NULL"
        ).fetchall()
        updated = 0
        for row in rows:
            original = row["repo_url"]
            normalized = _normalize_repo_url(original)
            if normalized != original:
                self._conn.execute(
                    "UPDATE entries SET repo_url = ? WHERE rowid = ?",
                    (normalized, row["rowid"]),
                )
                updated += 1
        if updated:
            self._conn.commit()

    def _index_path(self, namespace: str) -> Path:
        return self._data_dir / f"{namespace}.index"

    def _ids_path(self, namespace: str) -> Path:
        return self._data_dir / f"{namespace}.ids.json"

    def _load_all_indices(self):
        """Load FAISS indices for all namespaces that have persisted index files.

        Namespaces that do not satisfy ``_NAMESPACE_RE`` are skipped with a
        warning. They cannot be written to disk as a FAISS index path, and
        previously caused startup to crash-loop. Skipping leaves the SQLite
        rows intact so an operator can repair them.
        """
        namespaces = {
            row[0]
            for row in self._conn.execute(
                "SELECT DISTINCT namespace FROM entries"
            ).fetchall()
        }
        for ns in namespaces:
            try:
                _validate_namespace(ns)
            except ValueError as exc:
                log.warning("skipping unloadable namespace: %s", exc)
                continue
            if not self._is_semantic(ns):
                continue
            self._load_index(ns)

    def _load_index(self, namespace: str):
        """Load a single namespace's FAISS index and ID map from disk."""
        idx_path = self._index_path(namespace)
        ids_path = self._ids_path(namespace)
        if idx_path.exists() and ids_path.exists():
            index = faiss.read_index(str(idx_path))
            with open(ids_path, "r") as f:
                id_map = json.load(f)
            self._indices[namespace] = (index, id_map)
        else:
            # Rebuild from SQLite if files are missing
            self._rebuild_index(namespace)

    def _save_index(self, namespace: str):
        """Persist a namespace's FAISS index and ID map to disk."""
        if namespace not in self._indices:
            return
        index, id_map = self._indices[namespace]
        faiss.write_index(index, str(self._index_path(namespace)))
        with open(self._ids_path(namespace), "w") as f:
            json.dump(id_map, f)

    def _rebuild_index(self, namespace: str):
        """Rebuild a FAISS index for a namespace from all its SQLite entries."""
        rows = self._conn.execute(
            "SELECT rowid, content FROM entries WHERE namespace = ? ORDER BY rowid",
            (namespace,),
        ).fetchall()

        dim = self._embedder.dimension
        index = faiss.IndexFlatL2(dim)
        id_map: list[int] = []

        if rows:
            contents = [row["content"] for row in rows]
            rowids = [row["rowid"] for row in rows]
            vectors = self._embedder.embed_batch(contents)
            matrix = np.stack(vectors).astype(np.float32)
            index.add(matrix)
            id_map = rowids

        self._indices[namespace] = (index, id_map)
        self._save_index(namespace)

    def store(
        self,
        content: str,
        namespace: str = "default",
        tags: Optional[list[str]] = None,
        source: Optional[str] = None,
        repo_url: Optional[str] = None,
        branch: Optional[str] = None,
    ) -> dict:
        """Store a new memory entry.

        Args:
            content: The text content to store and index.
            namespace: Logical grouping for the entry.
            tags: Optional list of tags for filtering.
            source: Optional source identifier.
            repo_url: Optional repository URL to associate with this memory.
            branch: Optional branch name to associate with this memory.

        Returns:
            Dictionary with the created entry's fields.
        """
        _validate_namespace(namespace)
        if is_passthrough_dump(content):
            raise ValueError(
                "refusing to store a passthrough/backend-down dump as a "
                "memory: the inference backend was unavailable when this "
                "content was generated. Retry once a model is reachable."
            )
        entry_id = str(uuid.uuid4())
        created_at = datetime.now(timezone.utc).isoformat()
        tags_json = json.dumps(tags) if tags else None
        repo_url = _normalize_repo_url(repo_url)

        self._conn.execute(
            "INSERT INTO entries (id, namespace, content, tags, source, created_at, repo_url, branch) "
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            (entry_id, namespace, content, tags_json, source, created_at, repo_url, branch),
        )
        self._conn.commit()

        # Non-semantic namespaces (e.g. messages) are stored for branch /
        # recency retrieval only; skip embedding and FAISS indexing entirely.
        if self._is_semantic(namespace):
            # Get the rowid of the just-inserted row
            row = self._conn.execute(
                "SELECT rowid FROM entries WHERE id = ?", (entry_id,)
            ).fetchone()
            rowid = row["rowid"]

            # Embed and add to FAISS index
            vector = self._embedder.embed(content)
            if namespace not in self._indices:
                dim = self._embedder.dimension
                self._indices[namespace] = (faiss.IndexFlatL2(dim), [])

            index, id_map = self._indices[namespace]
            index.add(vector.reshape(1, -1))
            id_map.append(rowid)
            self._save_index(namespace)

        return {
            "id": entry_id,
            "namespace": namespace,
            "content": content,
            "tags": tags,
            "source": source,
            "repo_url": repo_url,
            "branch": branch,
            "created_at": created_at,
        }

    def search(
        self,
        query: str,
        namespace: str = "default",
        limit: int = 5,
        tag: Optional[str] = None,
        repo_url: Optional[str] = None,
        branch: Optional[str] = None,
    ) -> list[dict]:
        """Search for memory entries by semantic similarity.

        Args:
            query: The search query text.
            namespace: Namespace to search within.
            limit: Maximum number of results to return.
            tag: Optional tag to filter results by (post-filter).
            repo_url: Optional repo URL to filter results by (post-filter).
            branch: Optional branch name to filter results by (post-filter).

        Returns:
            List of entry dicts with an added "score" field (L2 distance).
        """
        _validate_namespace(namespace)
        # Non-semantic namespaces have no index; they are retrieved by branch
        # (search_by_branch), never by semantic similarity.
        if not self._is_semantic(namespace):
            return []
        if namespace not in self._indices:
            return []

        index, id_map = self._indices[namespace]
        if index.ntotal == 0:
            return []

        repo_url = _normalize_repo_url(repo_url)
        query_vec = self._embedder.embed(query).reshape(1, -1)

        # Search more than needed if we'll post-filter
        has_filter = bool(tag or repo_url or branch)
        search_k = min(limit * 4 if has_filter else limit, index.ntotal)
        distances, indices = index.search(query_vec, search_k)

        results = []
        for dist, idx in zip(distances[0], indices[0]):
            if idx < 0:
                continue
            rowid = id_map[idx]
            row = self._conn.execute(
                "SELECT id, namespace, content, tags, source, created_at, repo_url, branch "
                "FROM entries WHERE rowid = ?",
                (rowid,),
            ).fetchone()
            if row is None:
                continue

            entry = dict(row)
            entry["tags"] = json.loads(entry["tags"]) if entry["tags"] else None
            entry["score"] = float(dist)

            # Post-filter by tag
            if tag:
                if not (entry["tags"] and tag in entry["tags"]):
                    continue

            # Post-filter by repo_url (normalize stored value for pre-fix entries)
            if repo_url and _normalize_repo_url(entry.get("repo_url")) != repo_url:
                continue

            # Post-filter by branch
            if branch and entry.get("branch") != branch:
                continue

            results.append(entry)

            if len(results) >= limit:
                break

        return results

    def search_by_branch(
        self,
        repo_url: str,
        branch: str,
        namespace: Optional[str] = "default",
        limit: int = 20,
    ) -> list[dict]:
        """List memory entries for a specific repo and branch, newest first.

        This is a non-semantic lookup (no embedding query) that returns all
        memories associated with a given repository and branch combination,
        ordered by creation time.

        Args:
            repo_url: Repository URL to match.
            branch: Branch name to match.
            namespace: Namespace to search within. Pass ``None`` or an
                empty string to search across every namespace — useful when
                the caller wants the full branch context without knowing
                ahead of time which namespaces the memories were stored in.
            limit: Maximum number of entries to return.

        Returns:
            List of entry dicts ordered by creation time (newest first).
        """
        if namespace:
            _validate_namespace(namespace)
        repo_url = _normalize_repo_url(repo_url)
        if namespace:
            rows = self._conn.execute(
                "SELECT id, namespace, content, tags, source, created_at, repo_url, branch "
                "FROM entries WHERE namespace = ? AND repo_url = ? AND branch = ? "
                "ORDER BY created_at DESC LIMIT ?",
                (namespace, repo_url, branch, limit),
            ).fetchall()
        else:
            rows = self._conn.execute(
                "SELECT id, namespace, content, tags, source, created_at, repo_url, branch "
                "FROM entries WHERE repo_url = ? AND branch = ? "
                "ORDER BY created_at DESC LIMIT ?",
                (repo_url, branch, limit),
            ).fetchall()

        results = []
        for row in rows:
            entry = dict(row)
            entry["tags"] = json.loads(entry["tags"]) if entry["tags"] else None
            results.append(entry)
        return results

    def delete(self, entry_id: str, namespace: str = "default") -> dict:
        """Delete a memory entry by ID.

        Args:
            entry_id: The UUID of the entry to delete.
            namespace: The namespace the entry belongs to.

        Returns:
            Dictionary with status information.
        """
        _validate_namespace(namespace)
        row = self._conn.execute(
            "SELECT id FROM entries WHERE id = ? AND namespace = ?",
            (entry_id, namespace),
        ).fetchone()

        if row is None:
            return {"deleted": False, "error": f"Entry {entry_id} not found in namespace {namespace}"}

        self._conn.execute("DELETE FROM entries WHERE id = ?", (entry_id,))
        self._conn.commit()

        # Rebuild the FAISS index (semantic namespaces only; non-semantic
        # namespaces have no index to rebuild).
        if self._is_semantic(namespace):
            self._rebuild_index(namespace)

        return {"deleted": True, "id": entry_id, "namespace": namespace}

    def list_entries(
        self,
        namespace: str = "default",
        tag: Optional[str] = None,
        limit: int = 20,
        offset: int = 0,
    ) -> list[dict]:
        """List memory entries with optional tag filtering.

        Args:
            namespace: Namespace to list from.
            tag: Optional tag to filter by.
            limit: Maximum number of entries to return.
            offset: Number of entries to skip.

        Returns:
            List of entry dicts ordered by creation time (newest first).
        """
        _validate_namespace(namespace)
        rows = self._conn.execute(
            "SELECT id, namespace, content, tags, source, created_at, repo_url, branch "
            "FROM entries WHERE namespace = ? "
            "ORDER BY created_at DESC LIMIT ? OFFSET ?",
            (namespace, limit, offset),
        ).fetchall()

        results = []
        for row in rows:
            entry = dict(row)
            entry["tags"] = json.loads(entry["tags"]) if entry["tags"] else None
            if tag:
                if entry["tags"] and tag in entry["tags"]:
                    results.append(entry)
            else:
                results.append(entry)

        return results

    def namespace_stats(
        self,
        repo_url: Optional[str] = None,
        branch: Optional[str] = None,
    ) -> list[dict]:
        """Summarize every namespace by entry count and most-recent entry.

        Lets a caller discover which namespaces exist and, crucially, when each
        was last written — so locating "where did the latest memory land?" is a
        single call instead of guessing namespaces and issuing one search each.

        Args:
            repo_url: Optional repository URL filter (normalized to canonical
                SSH form before matching). When provided, only entries for that
                repository are counted.
            branch: Optional branch name filter. When provided, only entries on
                that branch are counted.

        Returns:
            A list of dicts, one per namespace, ordered by ``latest_created_at``
            descending (most recently written namespace first). Each dict has:
            ``namespace``, ``count``, ``latest_created_at`` (ISO-8601 string),
            and ``latest_id`` (the id of that newest entry). Returns an empty
            list when no entries match.
        """
        repo_url = _normalize_repo_url(repo_url)
        filters = []
        params: list = []
        if repo_url:
            filters.append("repo_url = ?")
            params.append(repo_url)
        if branch:
            filters.append("branch = ?")
            params.append(branch)
        where = (" WHERE " + " AND ".join(filters)) if filters else ""

        grouped = self._conn.execute(
            "SELECT namespace, COUNT(*) AS count, MAX(created_at) AS latest "
            f"FROM entries{where} GROUP BY namespace ORDER BY latest DESC",
            params,
        ).fetchall()

        stats = []
        for row in grouped:
            # Resolve the id of the newest entry in this namespace. created_at
            # is an ISO-8601 string, so MAX()/ORDER BY DESC are lexicographically
            # correct; a tie just returns one of the simultaneous entries.
            id_row = self._conn.execute(
                "SELECT id FROM entries WHERE namespace = ?"
                + ("".join(f" AND {f}" for f in filters))
                + " ORDER BY created_at DESC LIMIT 1",
                [row["namespace"], *params],
            ).fetchone()
            stats.append({
                "namespace": row["namespace"],
                "count": row["count"],
                "latest_created_at": row["latest"],
                "latest_id": id_row["id"] if id_row else None,
            })
        return stats
