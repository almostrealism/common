"""SQLite data access layer for ar-tracker.

Provides CRUD operations for projects, releases, and tasks,
plus full-text search via SQLite FTS5.
"""

import sqlite3
import threading
import uuid
from contextlib import contextmanager
from datetime import datetime, timezone
from typing import Optional

from migrate import run_migrations


def _now() -> str:
    """Return the current UTC time as an ISO 8601 string."""
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


# Task columns, in the order every query returns them. The headline projection
# omits only the description, for callers scanning large backlogs.
_TASK_HEADLINE_COLUMNS = (
    "id", "title", "status", "priority", "stage", "source", "project_id",
    "release_id", "workstream_id", "created_at", "updated_at",
)
_TASK_FULL_COLUMNS = (
    _TASK_HEADLINE_COLUMNS[:2] + ("description",) + _TASK_HEADLINE_COLUMNS[2:]
)

# The one definition of a task an agent may claim: open, marked ready, not yet
# linked to a workstream, and not waiting on any task that is still open. Both
# counting and claiming use it, so the question "is there work?" and the act of
# taking it can never disagree.
_CLAIMABLE = (
    "tasks.status = 'open' AND tasks.stage = 'ready' "
    "AND tasks.workstream_id IS NULL "
    "AND NOT EXISTS (SELECT 1 FROM task_blockers b "
    "JOIN tasks blocker ON blocker.id = b.blocker_id "
    "WHERE b.task_id = tasks.id AND blocker.status != 'closed')"
)

# Source prefix of a task an agent derived from a goal document
# ("goals:<document>"); any other task was written by a person.
GOAL_SOURCE_PREFIX = "goals:"

# Claim order: the most important task first, then the one waiting longest.
_CLAIM_ORDER = "tasks.priority DESC, tasks.created_at ASC"


def _task_columns(headlines_only: bool = False) -> str:
    """Return the task column list for a SELECT on the ``tasks`` table."""
    names = _TASK_HEADLINE_COLUMNS if headlines_only else _TASK_FULL_COLUMNS
    return ", ".join("tasks." + n for n in names)


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
        # Every statement on the shared connection, read or write, runs under
        # this lock. A read on the connection sees the rows of a transaction
        # another thread has open, so an unlocked read could return a task or
        # release that a failing write is about to roll back. The lock is
        # reentrant because the locked writes resolve rows through the same
        # read methods.
        self._lock = threading.RLock()
        self._conn = sqlite3.connect(db_path, check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        run_migrations(self._conn)

    @contextmanager
    def _write(self):
        """Run a multi-statement write as one transaction under the store lock.

        The transaction commits when the block completes and rolls back when
        it raises, so a statement the database refuses part-way through (an
        unknown blocker id, a CHECK violation) never leaves the earlier
        statements in the open transaction for an unrelated later write to
        commit.
        """
        with self._lock:
            try:
                yield
            except BaseException:
                self._conn.rollback()
                raise
            self._conn.commit()

    def close(self) -> None:
        """Close the underlying SQLite connection.

        Call this in test tearDown (before os.unlink) and in any context
        where the store will not be used again.
        """
        self._conn.close()

    # ------------------------------------------------------------------
    # Projects
    # ------------------------------------------------------------------

    def create_project(self, name: str) -> dict:
        """Create a new project and return it."""
        project_id = str(uuid.uuid4())
        created_at = _now()
        with self._write():
            self._conn.execute(
                "INSERT INTO projects (id, name, created_at) VALUES (?, ?, ?)",
                (project_id, name, created_at),
            )
        return {"id": project_id, "name": name, "created_at": created_at}

    def get_project(self, project_id: str) -> Optional[dict]:
        """Return a project by ID, or None if not found."""
        with self._lock:
            row = self._conn.execute(
                "SELECT id, name, created_at FROM projects WHERE id = ?", (project_id,)
            ).fetchone()
        return dict(row) if row else None

    def list_projects(self) -> list:
        """Return all projects ordered by name."""
        with self._lock:
            rows = self._conn.execute(
                "SELECT id, name, created_at FROM projects ORDER BY name"
            ).fetchall()
        return [dict(r) for r in rows]

    def update_project(self, project_id: str, name: str) -> Optional[dict]:
        """Update a project's name. Returns the updated project or None."""
        with self._write():
            self._conn.execute(
                "UPDATE projects SET name = ? WHERE id = ?", (name, project_id)
            )
        return self.get_project(project_id)

    def delete_project(self, project_id: str) -> bool:
        """Delete a project. Returns True if a row was deleted."""
        with self._write():
            cursor = self._conn.execute(
                "DELETE FROM projects WHERE id = ?", (project_id,)
            )
        return cursor.rowcount > 0

    # ------------------------------------------------------------------
    # Releases
    # ------------------------------------------------------------------

    def create_release(self, name: str, project_id: Optional[str] = None) -> dict:
        """Create a new release and return it.

        The insert takes the store lock, so it cannot interleave with the
        lookup-then-insert of :meth:`ensure_release`.
        """
        release_id = str(uuid.uuid4())
        created_at = _now()
        with self._write():
            self._conn.execute(
                "INSERT INTO releases (id, name, project_id, created_at) VALUES (?, ?, ?, ?)",
                (release_id, name, project_id or None, created_at),
            )
        return {
            "id": release_id,
            "name": name,
            "project_id": project_id or None,
            "created_at": created_at,
        }

    def get_release(self, release_id: str) -> Optional[dict]:
        """Return a release by ID, or None if not found."""
        with self._lock:
            row = self._conn.execute(
                "SELECT id, name, project_id, created_at FROM releases WHERE id = ?",
                (release_id,),
            ).fetchone()
        return dict(row) if row else None

    def list_releases(self, project_id: Optional[str] = None) -> list:
        """Return all releases, optionally filtered by project_id."""
        with self._lock:
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
        with self._write():
            self._conn.execute(
                f"UPDATE releases SET {', '.join(updates)} WHERE id = ?", params
            )
        return self.get_release(release_id)

    def delete_release(self, release_id: str) -> bool:
        """Delete a release. Returns True if a row was deleted."""
        with self._write():
            cursor = self._conn.execute(
                "DELETE FROM releases WHERE id = ?", (release_id,)
            )
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
        stage: str = "backlog",
        source: str = "person",
        blocked_by: Optional[list] = None,
    ) -> dict:
        """Create a new task and return it.

        ``blocked_by`` lists the ids of tasks that must close before this one
        can be claimed.
        """
        task_id = task_id or str(uuid.uuid4())
        now = created_at or _now()
        with self._write():
            self._conn.execute(
                "INSERT INTO tasks "
                "(id, title, description, status, priority, stage, source, "
                " project_id, release_id, workstream_id, created_at, updated_at) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (task_id, title, description, status, int(priority), stage, source,
                 project_id or None, release_id or None,
                 workstream_id or None, now, now),
            )
            if blocked_by:
                self._set_blockers(task_id, blocked_by)
        return self.get_task(task_id)

    def get_task(self, task_id: str) -> Optional[dict]:
        """Return a task by ID, or None if not found."""
        with self._lock:
            row = self._conn.execute(
                f"SELECT {_task_columns()} FROM tasks WHERE tasks.id = ?",
                (task_id,),
            ).fetchone()
            return self._with_blockers([dict(row)])[0] if row else None

    def _set_blockers(self, task_id: str, blocker_ids: list) -> None:
        """Replace the set of tasks blocking *task_id*. The caller commits or
        rolls back.

        Duplicate ids are collapsed here rather than with ``INSERT OR IGNORE``,
        which would also silently drop a row the CHECK refuses (a task
        blocking itself) instead of raising.
        """
        self._conn.execute("DELETE FROM task_blockers WHERE task_id = ?", (task_id,))
        self._conn.executemany(
            "INSERT INTO task_blockers (task_id, blocker_id) VALUES (?, ?)",
            [(task_id, b) for b in dict.fromkeys(blocker_ids)],
        )

    def _with_blockers(self, tasks: list) -> list:
        """Set ``blocked_by`` on each task dict to the ids blocking it. The
        caller holds the store lock."""
        if not tasks:
            return tasks
        ids = [t["id"] for t in tasks]
        placeholders = ", ".join("?" for _ in ids)
        rows = self._conn.execute(
            "SELECT task_id, blocker_id FROM task_blockers "
            f"WHERE task_id IN ({placeholders}) ORDER BY blocker_id",
            ids,
        ).fetchall()
        blockers: dict = {}
        for r in rows:
            blockers.setdefault(r["task_id"], []).append(r["blocker_id"])
        for t in tasks:
            t["blocked_by"] = blockers.get(t["id"], [])
        return tasks

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
        stage: Optional[str] = None,
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
            stage: Filter by stage.

        Returns:
            dict with 'tasks', 'total', 'limit', and 'offset'.
        """
        limit = min(limit, 200)
        sort_col = sort if sort in ("created_at", "updated_at", "priority") else "created_at"
        order_dir = "DESC" if order.lower() == "desc" else "ASC"

        columns = _task_columns(headlines_only)

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
        if stage is not None:
            conditions.append("stage = ?")
            params.append(stage)

        where = ("WHERE " + " AND ".join(conditions)) if conditions else ""

        with self._lock:
            total = self._conn.execute(
                f"SELECT COUNT(*) FROM tasks {where}", params
            ).fetchone()[0]

            rows = self._conn.execute(
                f"SELECT {columns} FROM tasks {where} "
                f"ORDER BY {sort_col} {order_dir} LIMIT ? OFFSET ?",
                params + [limit, offset],
            ).fetchall()

            return {
                "tasks": self._with_blockers([dict(r) for r in rows]),
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
        stage: object = UNSET,
        source: object = UNSET,
        blocked_by: object = UNSET,
        only_goal_derived: bool = False,
    ) -> Optional[dict]:
        """Update a task's fields.

        Pass UNSET (the default) to leave a field unchanged.
        Pass None to clear an optional FK field. The priority field
        cannot be cleared — it always has an integer value in [-2, 2].
        ``blocked_by`` replaces the whole set of blocking task ids.

        With ``only_goal_derived`` the update applies only while the stored
        task's source starts with :data:`GOAL_SOURCE_PREFIX`. The check is part
        of the UPDATE itself, so a person taking a task over between a caller's
        read and this write can never be overwritten.

        Returns:
            The updated task, or None when no task was changed: the task does
            not exist, or ``only_goal_derived`` was set and it was not
            goal-derived.
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
            ("stage", stage),
            ("source", source),
        ]:
            if not isinstance(val, _Unset):
                updates.append(f"{field} = ?")
                params.append(int(val) if field == "priority" else val)

        where = "id = ?"
        params.append(task_id)
        if only_goal_derived:
            where += " AND substr(source, 1, ?) = ?"
            params.extend([len(GOAL_SOURCE_PREFIX), GOAL_SOURCE_PREFIX])
        with self._write():
            cursor = self._conn.execute(
                f"UPDATE tasks SET {', '.join(updates)} WHERE {where}", params
            )
            if cursor.rowcount == 0:
                return None
            if not isinstance(blocked_by, _Unset):
                self._set_blockers(task_id, blocked_by or [])
        return self.get_task(task_id)

    # ------------------------------------------------------------------
    # Goal-derived task upsert
    # ------------------------------------------------------------------

    def upsert_goal_task(
        self,
        project_name: str,
        release_name: str,
        title: str,
        source: str,
        description: Optional[str] = None,
        priority: int = 0,
        blocked_by: Optional[list] = None,
        task_id: Optional[str] = None,
    ) -> dict:
        """Ensure the named release exists and create or update a goal task in it.

        The release resolution (create-if-missing, in the named project) and the
        task create or conditional update run as one transaction under the store
        lock. Anything that makes the task write fail — a missing project, an
        unknown blocker id, or an update naming a task that is not goal-derived —
        rolls the whole thing back, so a failed upsert never leaves behind a
        release this call would otherwise have created. The project is never
        created.

        When ``task_id`` is given the update applies only while the stored task's
        source starts with :data:`GOAL_SOURCE_PREFIX`, so a person taking the task
        over between any earlier read and this write is never overwritten.

        Returns a dict whose ``status`` is one of:
            ``ok`` — with ``task``, ``release`` and ``created_release`` (bool);
            ``project_not_found`` — no project has ``project_name``;
            ``task_not_found`` — ``task_id`` names no task;
            ``not_goal_derived`` — ``task_id`` names a task a person owns;
            ``db_error`` — the database refused a write (e.g. an unknown
                blocker), with ``error`` describing it.
        """
        with self._lock:
            try:
                result = self._upsert_goal_task_locked(
                    project_name, release_name, title, source,
                    description, priority, blocked_by, task_id)
            except sqlite3.Error as e:
                self._conn.rollback()
                return {"status": "db_error", "error": str(e)}
            except BaseException:
                self._conn.rollback()
                raise
            if result["status"] == "ok":
                self._conn.commit()
            else:
                self._conn.rollback()
        if result["status"] == "ok":
            result["task"] = self.get_task(result.pop("_task_id"))
        return result

    def _upsert_goal_task_locked(
        self,
        project_name: str,
        release_name: str,
        title: str,
        source: str,
        description: Optional[str],
        priority: int,
        blocked_by: Optional[list],
        task_id: Optional[str],
    ) -> dict:
        """Do the work of :meth:`upsert_goal_task`. The caller holds the store
        lock and commits on an ``ok`` status, rolling back otherwise, so this
        never commits or rolls back itself.

        On the ``ok`` path the result carries ``_task_id`` for the caller to
        resolve into a full task after the commit.
        """
        release = self.find_release(project_name, release_name)
        created_release = False
        if release is None:
            project = self._conn.execute(
                "SELECT id, name FROM projects WHERE name = ? "
                "ORDER BY created_at ASC, id ASC LIMIT 1",
                (project_name,),
            ).fetchone()
            if not project:
                return {"status": "project_not_found"}
            release_id = str(uuid.uuid4())
            created_at = _now()
            self._conn.execute(
                "INSERT INTO releases (id, name, project_id, created_at) "
                "VALUES (?, ?, ?, ?)",
                (release_id, release_name, project["id"], created_at),
            )
            release = {
                "id": release_id, "name": release_name,
                "project_id": project["id"], "created_at": created_at,
                "project_name": project["name"],
            }
            created_release = True

        now = _now()
        if not task_id:
            new_id = str(uuid.uuid4())
            self._conn.execute(
                "INSERT INTO tasks "
                "(id, title, description, status, priority, stage, source, "
                " project_id, release_id, workstream_id, created_at, updated_at) "
                "VALUES (?, ?, ?, 'open', ?, 'ready', ?, ?, ?, NULL, ?, ?)",
                (new_id, title, description, int(priority), source,
                 release["project_id"], release["id"], now, now),
            )
            if blocked_by:
                self._set_blockers(new_id, blocked_by)
            written_id = new_id
        else:
            cursor = self._conn.execute(
                "UPDATE tasks SET title = ?, description = ?, priority = ?, "
                "source = ?, project_id = ?, release_id = ?, updated_at = ? "
                "WHERE id = ? AND substr(source, 1, ?) = ?",
                (title, description, int(priority), source,
                 release["project_id"], release["id"], now, task_id,
                 len(GOAL_SOURCE_PREFIX), GOAL_SOURCE_PREFIX),
            )
            if cursor.rowcount == 0:
                exists = self._conn.execute(
                    "SELECT 1 FROM tasks WHERE id = ?", (task_id,)
                ).fetchone()
                return {"status": "not_goal_derived" if exists else "task_not_found"}
            if blocked_by is not None:
                self._set_blockers(task_id, blocked_by or [])
            written_id = task_id

        return {"status": "ok", "_task_id": written_id,
                "release": release, "created_release": created_release}

    # ------------------------------------------------------------------
    # Claiming work
    # ------------------------------------------------------------------

    def find_release(self, project_name: str, release_name: str) -> Optional[dict]:
        """Return the release named *release_name* in the project named
        *project_name*, or None when either does not exist.

        The returned dict carries the release fields plus ``project_name``.

        Names are not constrained to be unique, so more than one row can match.
        Selection is deterministic — the oldest matching release, ties broken by
        id — so a lookup, a claimable count and a claim always resolve the same
        release and can never target different ids for the same names.
        """
        with self._lock:
            row = self._conn.execute(
                "SELECT releases.id, releases.name, releases.project_id, "
                "releases.created_at, projects.name AS project_name "
                "FROM releases JOIN projects ON projects.id = releases.project_id "
                "WHERE projects.name = ? AND releases.name = ? "
                "ORDER BY releases.created_at ASC, releases.id ASC LIMIT 1",
                (project_name, release_name),
            ).fetchone()
        return dict(row) if row else None

    def ensure_release(self, project_name: str, release_name: str) -> tuple:
        """Return the release named *release_name* in the project named
        *project_name*, creating it if it does not exist yet.

        The lookup and the insert run under the store lock, so concurrent
        callers asking for the same names all receive the same release rather
        than each creating its own duplicate. When the release already exists
        the result is the one :meth:`find_release` resolves. A missing project
        is never created; when several projects share the name the release is
        created in the oldest.

        Returns:
            A (release_or_None, created) tuple. The release is None when no
            project has the name; ``created`` is True only when this call
            inserted the release.
        """
        with self._lock:
            existing = self.find_release(project_name, release_name)
            if existing:
                return existing, False
            project = self._conn.execute(
                "SELECT id, name FROM projects WHERE name = ? "
                "ORDER BY created_at ASC, id ASC LIMIT 1",
                (project_name,),
            ).fetchone()
            if not project:
                return None, False
            release_id = str(uuid.uuid4())
            created_at = _now()
            self._conn.execute(
                "INSERT INTO releases (id, name, project_id, created_at) "
                "VALUES (?, ?, ?, ?)",
                (release_id, release_name, project["id"], created_at),
            )
            self._conn.commit()
        return {
            "id": release_id,
            "name": release_name,
            "project_id": project["id"],
            "created_at": created_at,
            "project_name": project["name"],
        }, True

    def count_claimable(self, release_id: str) -> int:
        """Return how many tasks in *release_id* an agent could claim now."""
        with self._lock:
            return self._conn.execute(
                f"SELECT COUNT(*) FROM tasks WHERE tasks.release_id = ? AND {_CLAIMABLE}",
                (release_id,),
            ).fetchone()[0]

    def claim_next(self, release_id: str, workstream_id: str) -> Optional[dict]:
        """Link the next claimable task in *release_id* to *workstream_id*.

        The task taken is the highest-priority claimable one, oldest first
        among equals. The update is conditional on the task still being
        unlinked, so two callers can never take the same task.

        Returns:
            The claimed task, or None when nothing is claimable.
        """
        with self._lock:
            row = self._conn.execute(
                f"SELECT tasks.id FROM tasks WHERE tasks.release_id = ? AND {_CLAIMABLE} "
                f"ORDER BY {_CLAIM_ORDER} LIMIT 1",
                (release_id,),
            ).fetchone()
            if not row:
                return None
            cursor = self._conn.execute(
                "UPDATE tasks SET workstream_id = ?, updated_at = ? "
                "WHERE id = ? AND workstream_id IS NULL",
                (workstream_id, _now(), row["id"]),
            )
            self._conn.commit()
            if cursor.rowcount == 0:
                return None
        return self.get_task(row["id"])

    def delete_task(self, task_id: str) -> bool:
        """Delete a task. Returns True if a row was deleted."""
        with self._write():
            cursor = self._conn.execute("DELETE FROM tasks WHERE id = ?", (task_id,))
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

        select_cols = _task_columns(headlines_only)

        with self._lock:
            try:
                total = self._conn.execute(
                    f"SELECT COUNT(*) FROM tasks {where}", params
                ).fetchone()[0]

                rows = self._conn.execute(
                    f"SELECT {select_cols} FROM tasks {where} LIMIT ? OFFSET ?",
                    params + [limit, offset],
                ).fetchall()
            except sqlite3.OperationalError:
                return {"tasks": [], "total": 0, "query": query,
                        "limit": limit, "offset": offset}

            return {
                "tasks": self._with_blockers([dict(r) for r in rows]),
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
        with self._lock:
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
        Tasks carry ``stage``, ``source`` and ``blocked_by`` like any other
        task; an absent field keeps an existing task's value, or takes the
        column default for a new one.

        The import is all-or-nothing: it runs as one transaction under the
        store lock, and a record the import rejects, or one the database
        refuses (an unknown foreign key, a value outside a CHECK constraint),
        rolls back everything written before it.

        Returns:
            Counts of created and updated records per entity type, or a dict
            with ``error`` when nothing was imported.
        """
        with self._lock:
            try:
                result = self._bulk_import_locked(projects, releases, tasks)
            except sqlite3.Error as e:
                result = {"error": f"import rejected by the database: {e}"}
            except Exception:
                self._conn.rollback()
                raise
            if "error" in result:
                self._conn.rollback()
            else:
                self._conn.commit()
        return result

    def _bulk_import_locked(self, projects: list, releases: list, tasks: list) -> dict:
        """Write the records of :meth:`bulk_import`. The caller holds the
        store lock and commits or rolls back.

        A record is inserted only after its update matched nothing, so the
        row cannot already exist and a plain INSERT is used: ``INSERT OR
        IGNORE`` would also silently skip a row that breaks a NOT NULL or
        CHECK constraint, reporting success for a record never written."""
        created = {"projects": 0, "releases": 0, "tasks": 0}
        updated = {"projects": 0, "releases": 0, "tasks": 0}

        for idx, p in enumerate(projects):
            if not p.get("id") or not p.get("name"):
                return {"error": f"projects[{idx}] must have 'id' and 'name'"}
            cur = self._conn.execute(
                "UPDATE projects SET name = ? WHERE id = ?",
                (p["name"], p["id"]),
            )
            if cur.rowcount > 0:
                updated["projects"] += 1
            else:
                self._conn.execute(
                    "INSERT INTO projects (id, name, created_at) VALUES (?, ?, ?)",
                    (p["id"], p["name"], p.get("created_at") or _now()),
                )
                created["projects"] += 1

        for idx, r in enumerate(releases):
            if not r.get("id") or not r.get("name"):
                return {"error": f"releases[{idx}] must have 'id' and 'name'"}
            cur = self._conn.execute(
                "UPDATE releases SET name = ?, project_id = ? WHERE id = ?",
                (r["name"], r.get("project_id"), r["id"]),
            )
            if cur.rowcount > 0:
                updated["releases"] += 1
            else:
                self._conn.execute(
                    "INSERT INTO releases "
                    "(id, name, project_id, created_at) VALUES (?, ?, ?, ?)",
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
            now = _now()
            existing = self.get_task(t["id"])
            if existing:
                self._conn.execute(
                    "UPDATE tasks SET title = ?, description = ?, status = ?, "
                    "priority = ?, stage = ?, source = ?, project_id = ?, "
                    "release_id = ?, workstream_id = ?, updated_at = ? "
                    "WHERE id = ?",
                    (t.get("title", existing["title"]),
                     t.get("description", existing["description"]),
                     t.get("status", existing["status"]),
                     t.get("priority", existing["priority"]),
                     t.get("stage", existing["stage"]),
                     t.get("source", existing["source"]).strip(),
                     t.get("project_id", existing["project_id"]),
                     t.get("release_id", existing["release_id"]),
                     t.get("workstream_id", existing["workstream_id"]),
                     now, t["id"]),
                )
                updated["tasks"] += 1
            else:
                self._conn.execute(
                    "INSERT INTO tasks "
                    "(id, title, description, status, priority, stage, source, "
                    " project_id, release_id, workstream_id, created_at, updated_at) "
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    (t["id"], t.get("title", ""), t.get("description"),
                     t.get("status", "open"), priority,
                     t.get("stage", "backlog"), t.get("source", "person").strip(),
                     t.get("project_id"), t.get("release_id"),
                     t.get("workstream_id"),
                     t.get("created_at") or now, t.get("updated_at") or now),
                )
                created["tasks"] += 1

        # Blockers are linked once every task exists, so a task may be blocked
        # by one that appears later in the same import. As with update_task, a
        # supplied blocked_by replaces the set; an absent one leaves it alone.
        for t in tasks:
            if "blocked_by" in t:
                self._set_blockers(t["id"], t["blocked_by"] or [])
        return {"created": created, "updated": updated}

    # ------------------------------------------------------------------
    # Health
    # ------------------------------------------------------------------

    def counts(self) -> dict:
        """Return entity counts for the health endpoint."""
        def _count(table: str) -> int:
            return self._conn.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
        with self._lock:
            return {
                "projects": _count("projects"),
                "releases": _count("releases"),
                "tasks": _count("tasks"),
            }
