"""
Memory store backed by SQLite (metadata) and FAISS (vector search).

Each namespace gets its own FAISS index file and ID mapping file.
"""

import json
import os
import sqlite3
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

import faiss
import numpy as np

from embedder import Embedder


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

    def _init_db(self):
        self._conn.execute("""
            CREATE TABLE IF NOT EXISTS entries (
                id TEXT PRIMARY KEY,
                namespace TEXT NOT NULL,
                content TEXT NOT NULL,
                tags TEXT,
                source TEXT,
                created_at TEXT NOT NULL
            )
        """)
        self._conn.execute("""
            CREATE INDEX IF NOT EXISTS idx_entries_namespace
            ON entries(namespace)
        """)
        self._conn.commit()

    def _index_path(self, namespace: str) -> Path:
        return self._data_dir / f"{namespace}.index"

    def _ids_path(self, namespace: str) -> Path:
        return self._data_dir / f"{namespace}.ids.json"

    def _load_all_indices(self):
        """Load FAISS indices for all namespaces that have persisted index files."""
        namespaces = {
            row[0]
            for row in self._conn.execute(
                "SELECT DISTINCT namespace FROM entries"
            ).fetchall()
        }
        for ns in namespaces:
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
    ) -> dict:
        """Store a new memory entry.

        Args:
            content: The text content to store and index.
            namespace: Logical grouping for the entry.
            tags: Optional list of tags for filtering.
            source: Optional source identifier.

        Returns:
            Dictionary with the created entry's fields.
        """
        entry_id = str(uuid.uuid4())
        created_at = datetime.now(timezone.utc).isoformat()
        tags_json = json.dumps(tags) if tags else None

        self._conn.execute(
            "INSERT INTO entries (id, namespace, content, tags, source, created_at) "
            "VALUES (?, ?, ?, ?, ?, ?)",
            (entry_id, namespace, content, tags_json, source, created_at),
        )
        self._conn.commit()

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
            "created_at": created_at,
        }

    def search(
        self,
        query: str,
        namespace: str = "default",
        limit: int = 5,
        tag: Optional[str] = None,
    ) -> list[dict]:
        """Search for memory entries by semantic similarity.

        Args:
            query: The search query text.
            namespace: Namespace to search within.
            limit: Maximum number of results to return.
            tag: Optional tag to filter results by (post-filter).

        Returns:
            List of entry dicts with an added "score" field (L2 distance).
        """
        if namespace not in self._indices:
            return []

        index, id_map = self._indices[namespace]
        if index.ntotal == 0:
            return []

        query_vec = self._embedder.embed(query).reshape(1, -1)

        # Search more than needed if we'll post-filter by tag
        search_k = min(limit * 4 if tag else limit, index.ntotal)
        distances, indices = index.search(query_vec, search_k)

        results = []
        for dist, idx in zip(distances[0], indices[0]):
            if idx < 0:
                continue
            rowid = id_map[idx]
            row = self._conn.execute(
                "SELECT id, namespace, content, tags, source, created_at "
                "FROM entries WHERE rowid = ?",
                (rowid,),
            ).fetchone()
            if row is None:
                continue

            entry = dict(row)
            entry["tags"] = json.loads(entry["tags"]) if entry["tags"] else None
            entry["score"] = float(dist)

            if tag:
                if entry["tags"] and tag in entry["tags"]:
                    results.append(entry)
            else:
                results.append(entry)

            if len(results) >= limit:
                break

        return results

    def delete(self, entry_id: str, namespace: str = "default") -> dict:
        """Delete a memory entry by ID.

        Args:
            entry_id: The UUID of the entry to delete.
            namespace: The namespace the entry belongs to.

        Returns:
            Dictionary with status information.
        """
        row = self._conn.execute(
            "SELECT id FROM entries WHERE id = ? AND namespace = ?",
            (entry_id, namespace),
        ).fetchone()

        if row is None:
            return {"deleted": False, "error": f"Entry {entry_id} not found in namespace {namespace}"}

        self._conn.execute("DELETE FROM entries WHERE id = ?", (entry_id,))
        self._conn.commit()

        # Rebuild the FAISS index for this namespace
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
        rows = self._conn.execute(
            "SELECT id, namespace, content, tags, source, created_at "
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
