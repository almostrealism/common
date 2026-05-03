"""SQLite data access layer for ar-tracker.

Provides CRUD operations for projects, releases, and tasks,
plus full-text search via SQLite FTS5.
"""

import sqlite3
import uuid
from datetime import datetime, timezone
from typing import Optional

from migrate import run_migrations


def _now() -> str:
    """Return the current UTC time as an ISO 8601 string."""
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


class _Unset:
    """Sentinel meaning 'caller did not supply this field — leave it unchanged'."""


UNSET = _Unset()


class TrackerStore:
    """SQLite-backed store for tracker entities.

    Args:
        db_path: Path to the SQLite database file.
    """

    def __init__(self, db_path: str) -> None:
        self._db_path = db_path
        self._conn = sqlite3.connect(db_path, check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        run_migrations(self._conn)

    # ------------------------------------------------------------------
    # Projects
    # ------------------------------------------------------------------

    def create_project(self, name: str) -> dict:
        """Create a new project and return it."""
        project_id = str(uuid.uuid4())
        created_at = _now()
        self._conn.execute(
            "INSERT INTO projects (id, name, created_at) VALUES (?, ?, ?)",
            (project_id, name, created_at),
        )
        self._conn.commit()
        return {"id": project_id, "name": name, "created_at": created_at}

    def get_project(self, project_id: str) -> Optional[dict]:
        """Return a project by ID, or None if not found."""
        row = self._conn.execute(
            "SELECT id, name, created_at FROM projects WHERE id = ?", (project_id,)
        ).fetchone()
        return dict(row) if row else None

    def list_projects(self) -> list:
        """Return all projects ordered by name."""
        rows = self._conn.execute(
            "SELECT id, name, created_at FROM projects ORDER BY name"
        ).fetchall()
        return [dict(r) for r in rows]

    def update_project(self, project_id: str, name: str) -> Optional[dict]:
        """Update a project's name. Returns the updated project or None."""
        self._conn.execute(
            "UPDATE projects SET name = ? WHERE id = ?", (name, project_id)
        )
        self._conn.commit()
        return self.get_project(project_id)

    def delete_project(self, project_id: str) -> bool:
        """Delete a project. Returns True if a row was deleted."""
        cursor = self._conn.execute(
            "DELETE FROM projects WHERE id = ?", (project_id,)
        )
        self._conn.commit()
        return cursor.rowcount > 0

    # ------------------------------------------------------------------
    # Releases
    # ------------------------------------------------------------------

    def create_release(self, name: str, project_id: Optional[str] = None) -> dict:
        """Create a new release and return it."""
        release_id = str(uuid.uuid4())
        created_at = _now()
        self._conn.execute(
            "INSERT INTO releases (id, name, project_id, created_at) VALUES (?, ?, ?, ?)",
            (release_id, name, project_id or None, created_at),
        )
        self._conn.commit()
        return {
            "id": release_id,
            "name": name,
            "project_id": project_id or None,
            "created_at": created_at,
        }

    def get_release(self, release_id: str) -> Optional[dict]:
        """Return a release by ID, or None if not found."""
        row = self._conn.execute(
            "SELECT id, name, project_id, created_at FROM releases WHERE id = ?",
            (release_id,),
        ).fetchone()
        return dict(row) if row else None

    def list_releases(self, project_id: Optional[str] = None) -> list:
        """Return all releases, optionally filtered by project_id."""
        if project_id:
            rows = self._conn.execute(
                "SELECT id, name, project_id, created_at FROM releases "
                "WHERE project_id = ? ORDER BY name",
                (project_id,),
            ).fetchall()
        else:
            rows = self._conn.execute(
                "SELECT id, name, project_id, created_at FROM releases ORDER BY name"
            ).fetchall()
        return [dict(r) for r in rows]

    def update_release(
        self,
        release_id: str,
        name: object = UNSET,
        project_id: object = UNSET,
    ) -> Optional[dict]:
        """Update a release's fields. Only supplied fields are changed.

        Pass UNSET (the default) to leave a field unchanged.
        Pass None to clear project_id.
        """
        updates = []
        params: list = []
        if not isinstance(name, _Unset):
            updates.append("name = ?")
            params.append(name)
        if not isinstance(project_id, _Unset):
            updates.append("project_id = ?")
            params.append(project_id)
        if not updates:
            return self.get_release(release_id)
        params.append(release_id)
        self._conn.execute(
            f"UPDATE releases SET {', '.join(updates)} WHERE id = ?", params
        )
        self._conn.commit()
        return self.get_release(release_id)

    def delete_release(self, release_id: str) -> bool:
        """Delete a release. Returns True if a row was deleted."""
        cursor = self._conn.execute(
            "DELETE FROM releases WHERE id = ?", (release_id,)
        )
        self._conn.commit()
        return cursor.rowcount > 0

    # ------------------------------------------------------------------
    # Tasks
    # ------------------------------------------------------------------

    def create_task(
        self,
        title: str,
        description: Optional[str] = None,
        status: str = "open",
        priority: int = 0,
        project_id: Optional[str] = None,
        release_id: Optional[str] = None,
        workstream_id: Optional[str] = None,
        task_id: Optional[str] = None,
        created_at: Optional[str] = None,
    ) -> dict:
        """Create a new task and return it."""
        task_id = task_id or str(uuid.uuid4())
        now = created_at or _now()
        self._conn.execute(
            "INSERT INTO tasks "
            "(id, title, description, status, priority, project_id, "
            " release_id, workstream_id, created_at, updated_at) "
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (task_id, title, description, status, int(priority),
             project_id or None, release_id or None,
             workstream_id or None, now, now),
        )
        self._conn.commit()
        return self.get_task(task_id)

    def get_task(self, task_id: str) -> Optional[dict]:
        """Return a task by ID, or None if not found."""
        row = self._conn.execute(
            "SELECT id, title, description, status, priority, project_id, "
            "release_id, workstream_id, created_at, updated_at "
            "FROM tasks WHERE id = ?",
            (task_id,),
        ).fetchone()
        return dict(row) if row else None

    def list_tasks(
        self,
        project_id: Optional[str] = None,
        release_id: Optional[str] = None,
        workstream_id: Optional[str] = None,
        status: Optional[str] = None,
        limit: int = 50,
        offset: int = 0,
        sort: str = "created_at",
        order: str = "desc",
        headlines_only: bool = False,
    ) -> dict:
        """List tasks with optional filtering and pagination.

        Args:
            project_id: Filter to tasks in this project.
            release_id: Filter to tasks in this release.
            workstream_id: Filter to tasks linked to this workstream.
            status: Filter by status.
            limit: Maximum rows to return (capped at 200).
            offset: Pagination offset.
            sort: Sort column (created_at, updated_at, priority).
            order: Sort direction (asc, desc).
            headlines_only: When True, omit the description field from
                each returned task. Use when scanning large backlogs.

        Returns:
            dict with 'tasks', 'total', 'limit', and 'offset'.
        """
        limit = min(limit, 200)
        sort_col = sort if sort in ("created_at", "updated_at", "priority") else "created_at"
        order_dir = "DESC" if order.lower() == "desc" else "ASC"

        columns = (
            "id, title, status, priority, project_id, release_id, "
            "workstream_id, created_at, updated_at"
            if headlines_only
            else
            "id, title, description, status, priority, project_id, "
            "release_id, workstream_id, created_at, updated_at"
        )

        conditions = []
        params: list = []
        if project_id is not None:
            conditions.append("project_id = ?")
            params.append(project_id)
        if release_id is not None:
            conditions.append("release_id = ?")
            params.append(release_id)
        if workstream_id is not None:
            conditions.append("workstream_id = ?")
            params.append(workstream_id)
        if status is not None:
            conditions.append("status = ?")
            params.append(status)

        where = ("WHERE " + " AND ".join(conditions)) if conditions else ""

        total = self._conn.execute(
            f"SELECT COUNT(*) FROM tasks {where}", params
        ).fetchone()[0]

        rows = self._conn.execute(
            f"SELECT {columns} FROM tasks {where} "
            f"ORDER BY {sort_col} {order_dir} LIMIT ? OFFSET ?",
            params + [limit, offset],
        ).fetchall()

        return {
            "tasks": [dict(r) for r in rows],
            "total": total,
            "limit": limit,
            "offset": offset,
        }

    def update_task(
        self,
        task_id: str,
        title: object = UNSET,
        description: object = UNSET,
        status: object = UNSET,
        priority: object = UNSET,
        project_id: object = UNSET,
        release_id: object = UNSET,
        workstream_id: object = UNSET,
    ) -> Optional[dict]:
        """Update a task's fields.

        Pass UNSET (the default) to leave a field unchanged.
        Pass None to clear an optional FK field. The priority field
        cannot be cleared — it always has an integer value in [-2, 2].
        """
        updates = ["updated_at = ?"]
        params: list = [_now()]

        for field, val in [
            ("title", title),
            ("description", description),
            ("status", status),
            ("priority", priority),
            ("project_id", project_id),
            ("release_id", release_id),
            ("workstream_id", workstream_id),
        ]:
            if not isinstance(val, _Unset):
                updates.append(f"{field} = ?")
                params.append(int(val) if field == "priority" else val)

        params.append(task_id)
        self._conn.execute(
            f"UPDATE tasks SET {', '.join(updates)} WHERE id = ?", params
        )
        self._conn.commit()
        return self.get_task(task_id)

    def delete_task(self, task_id: str) -> bool:
        """Delete a task. Returns True if a row was deleted."""
        cursor = self._conn.execute("DELETE FROM tasks WHERE id = ?", (task_id,))
        self._conn.commit()
        return cursor.rowcount > 0

    # ------------------------------------------------------------------
    # Full-text search
    # ------------------------------------------------------------------

    def search_tasks(
        self,
        query: str,
        project_id: Optional[str] = None,
        status: Optional[str] = None,
        limit: int = 20,
        offset: int = 0,
        headlines_only: bool = False,
    ) -> dict:
        """Full-text search over task titles and descriptions.

        Args:
            query: FTS5 query string.
            project_id: Restrict search to this project.
            status: Restrict search to this status.
            limit: Maximum rows to return (capped at 100).
            offset: Pagination offset.
            headlines_only: When True, omit the description field from
                each returned task. Use when scanning large backlogs.

        Returns:
            dict with 'tasks', 'total', 'query', 'limit', and 'offset'.
        """
        limit = min(limit, 100)
        conditions = ["tasks.rowid IN (SELECT rowid FROM tasks_fts WHERE tasks_fts MATCH ?)"]
        params: list = [query]

        if project_id:
            conditions.append("tasks.project_id = ?")
            params.append(project_id)
        if status:
            conditions.append("tasks.status = ?")
            params.append(status)

        where = "WHERE " + " AND ".join(conditions)

        if headlines_only:
            select_cols = (
                "tasks.id, tasks.title, tasks.status, tasks.priority, "
                "tasks.project_id, tasks.release_id, "
                "tasks.workstream_id, tasks.created_at, tasks.updated_at"
            )
        else:
            select_cols = (
                "tasks.id, tasks.title, tasks.description, tasks.status, "
                "tasks.priority, tasks.project_id, tasks.release_id, "
                "tasks.workstream_id, tasks.created_at, tasks.updated_at"
            )

        try:
            total = self._conn.execute(
                f"SELECT COUNT(*) FROM tasks {where}", params
            ).fetchone()[0]

            rows = self._conn.execute(
                f"SELECT {select_cols} FROM tasks {where} LIMIT ? OFFSET ?",
                params + [limit, offset],
            ).fetchall()
        except sqlite3.OperationalError:
            return {"tasks": [], "total": 0, "query": query, "limit": limit, "offset": offset}

        return {
            "tasks": [dict(r) for r in rows],
            "total": total,
            "query": query,
            "limit": limit,
            "offset": offset,
        }

    def project_summary(self, project_id: str) -> Optional[dict]:
        """Return aggregate task counts for a project.

        Args:
            project_id: UUID of the project to summarise.

        Returns:
            dict with total_tasks, by_status, by_priority, by_release, and
            by_workstream, or None if the project does not exist.
        """
        if not self.get_project(project_id):
            return None

        total = self._conn.execute(
            "SELECT COUNT(*) FROM tasks WHERE project_id = ?", (project_id,)
        ).fetchone()[0]

        status_rows = self._conn.execute(
            "SELECT status, COUNT(*) FROM tasks WHERE project_id = ? GROUP BY status",
            (project_id,),
        ).fetchall()
        by_status = {row[0]: row[1] for row in status_rows}

        priority_rows = self._conn.execute(
            "SELECT priority, COUNT(*) FROM tasks WHERE project_id = ? GROUP BY priority",
            (project_id,),
        ).fetchall()
        # Priority keys are integers in storage but JSON object keys must be strings.
        # Use string representation so the shape survives JSON round-trips.
        by_priority = {str(row[0]): row[1] for row in priority_rows}

        release_rows = self._conn.execute(
            "SELECT r.id, r.name, "
            "COUNT(t.id) AS task_count, "
            "COALESCE(SUM(CASE WHEN t.status = 'open' THEN 1 ELSE 0 END), 0) AS open_count "
            "FROM releases r "
            "LEFT JOIN tasks t ON t.release_id = r.id AND t.project_id = ? "
            "WHERE r.project_id = ? "
            "GROUP BY r.id, r.name",
            (project_id, project_id),
        ).fetchall()
        by_release = [
            {
                "release_id": row[0],
                "release_name": row[1],
                "task_count": row[2],
                "open_count": row[3],
            }
            for row in release_rows
        ]
        no_release_row = self._conn.execute(
            "SELECT COUNT(*) AS task_count, "
            "COALESCE(SUM(CASE WHEN status = 'open' THEN 1 ELSE 0 END), 0) AS open_count "
            "FROM tasks WHERE project_id = ? AND release_id IS NULL",
            (project_id,),
        ).fetchone()
        by_release.append({
            "release_id": None,
            "release_name": None,
            "task_count": no_release_row[0],
            "open_count": no_release_row[1],
        })

        ws_rows = self._conn.execute(
            "SELECT workstream_id, COUNT(*) AS task_count, "
            "COALESCE(SUM(CASE WHEN status = 'open' THEN 1 ELSE 0 END), 0) AS open_count "
            "FROM tasks WHERE project_id = ? AND workstream_id IS NOT NULL "
            "GROUP BY workstream_id",
            (project_id,),
        ).fetchall()
        by_workstream = [
            {
                "workstream_id": row[0],
                "task_count": row[1],
                "open_count": row[2],
            }
            for row in ws_rows
        ]
        no_ws_row = self._conn.execute(
            "SELECT COUNT(*) AS task_count, "
            "COALESCE(SUM(CASE WHEN status = 'open' THEN 1 ELSE 0 END), 0) AS open_count "
            "FROM tasks WHERE project_id = ? AND workstream_id IS NULL",
            (project_id,),
        ).fetchone()
        by_workstream.append({
            "workstream_id": None,
            "task_count": no_ws_row[0],
            "open_count": no_ws_row[1],
        })

        return {
            "project_id": project_id,
            "total_tasks": total,
            "by_status": by_status,
            "by_priority": by_priority,
            "by_release": by_release,
            "by_workstream": by_workstream,
        }

    # ------------------------------------------------------------------
    # Bulk import
    # ------------------------------------------------------------------

    def bulk_import(self, projects: list, releases: list, tasks: list) -> dict:
        """Upsert projects, releases, and tasks in bulk.

        Existing records (matched by ID) are updated; new records are inserted.
        Returns counts of created and updated records per entity type.
        """
        created = {"projects": 0, "releases": 0, "tasks": 0}
        updated = {"projects": 0, "releases": 0, "tasks": 0}

        for idx, p in enumerate(projects):
            if not p.get("id") or not p.get("name"):
                return {"error": f"projects[{idx}] must have 'id' and 'name'"}
            existing = self.get_project(p["id"])
            if existing:
                self._conn.execute(
                    "UPDATE projects SET name = ? WHERE id = ?",
                    (p["name"], p["id"]),
                )
                updated["projects"] += 1
            else:
                self._conn.execute(
                    "INSERT OR IGNORE INTO projects (id, name, created_at) VALUES (?, ?, ?)",
                    (p["id"], p["name"], p.get("created_at") or _now()),
                )
                created["projects"] += 1

        for idx, r in enumerate(releases):
            if not r.get("id") or not r.get("name"):
                return {"error": f"releases[{idx}] must have 'id' and 'name'"}
            existing = self.get_release(r["id"])
            if existing:
                self._conn.execute(
                    "UPDATE releases SET name = ?, project_id = ? WHERE id = ?",
                    (r["name"], r.get("project_id"), r["id"]),
                )
                updated["releases"] += 1
            else:
                self._conn.execute(
                    "INSERT OR IGNORE INTO releases (id, name, project_id, created_at) "
                    "VALUES (?, ?, ?, ?)",
                    (r["id"], r["name"], r.get("project_id"),
                     r.get("created_at") or _now()),
                )
                created["releases"] += 1

        for idx, t in enumerate(tasks):
            if not t.get("id"):
                return {"error": f"tasks[{idx}] must have 'id'"}
            priority = t.get("priority", 0)
            if not isinstance(priority, int) or priority < -2 or priority > 2:
                return {
                    "error": f"tasks[{idx}].priority must be an integer in [-2, 2]"
                }
            existing = self.get_task(t["id"])
            now = _now()
            if existing:
                self._conn.execute(
                    "UPDATE tasks SET title = ?, description = ?, status = ?, "
                    "priority = ?, project_id = ?, release_id = ?, "
                    "workstream_id = ?, updated_at = ? WHERE id = ?",
                    (t.get("title", existing["title"]),
                     t.get("description", existing["description"]),
                     t.get("status", existing["status"]),
                     t.get("priority", existing["priority"]),
                     t.get("project_id", existing["project_id"]),
                     t.get("release_id", existing["release_id"]),
                     t.get("workstream_id", existing["workstream_id"]),
                     now, t["id"]),
                )
                updated["tasks"] += 1
            else:
                self._conn.execute(
                    "INSERT OR IGNORE INTO tasks "
                    "(id, title, description, status, priority, project_id, "
                    " release_id, workstream_id, created_at, updated_at) "
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    (t["id"], t.get("title", ""), t.get("description"),
                     t.get("status", "open"), priority,
                     t.get("project_id"), t.get("release_id"),
                     t.get("workstream_id"),
                     t.get("created_at") or now, t.get("updated_at") or now),
                )
                created["tasks"] += 1

        self._conn.commit()
        return {"created": created, "updated": updated}

    # ------------------------------------------------------------------
    # Health
    # ------------------------------------------------------------------

    def counts(self) -> dict:
        """Return entity counts for the health endpoint."""
        def _count(table: str) -> int:
            return self._conn.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
        return {
            "projects": _count("projects"),
            "releases": _count("releases"),
            "tasks": _count("tasks"),
        }
