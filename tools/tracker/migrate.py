"""Schema migration runner for ar-tracker.

Applies numbered SQL migration files in sequence, tracking the current
schema version in a schema_version table.
"""

import sqlite3
from pathlib import Path


_SCHEMA_V1 = """
CREATE TABLE IF NOT EXISTS schema_version (
    version INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS projects (
    id         TEXT PRIMARY KEY,
    name       TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS releases (
    id         TEXT PRIMARY KEY,
    name       TEXT NOT NULL,
    project_id TEXT REFERENCES projects(id) ON DELETE SET NULL,
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS tasks (
    id             TEXT PRIMARY KEY,
    title          TEXT NOT NULL,
    description    TEXT,
    status         TEXT NOT NULL DEFAULT 'open',
    project_id     TEXT REFERENCES projects(id) ON DELETE SET NULL,
    release_id     TEXT REFERENCES releases(id) ON DELETE SET NULL,
    workstream_id  TEXT,
    created_at     TEXT NOT NULL,
    updated_at     TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_tasks_project    ON tasks(project_id);
CREATE INDEX IF NOT EXISTS idx_tasks_release    ON tasks(release_id);
CREATE INDEX IF NOT EXISTS idx_tasks_workstream ON tasks(workstream_id);
CREATE INDEX IF NOT EXISTS idx_tasks_status     ON tasks(status);

CREATE VIRTUAL TABLE IF NOT EXISTS tasks_fts USING fts5(
    title,
    description,
    content=tasks,
    content_rowid=rowid
);

CREATE TRIGGER IF NOT EXISTS tasks_ai AFTER INSERT ON tasks BEGIN
    INSERT INTO tasks_fts(rowid, title, description)
    VALUES (new.rowid, new.title, new.description);
END;

CREATE TRIGGER IF NOT EXISTS tasks_ad AFTER DELETE ON tasks BEGIN
    INSERT INTO tasks_fts(tasks_fts, rowid, title, description)
    VALUES ('delete', old.rowid, old.title, old.description);
END;

CREATE TRIGGER IF NOT EXISTS tasks_au AFTER UPDATE ON tasks BEGIN
    INSERT INTO tasks_fts(tasks_fts, rowid, title, description)
    VALUES ('delete', old.rowid, old.title, old.description);
    INSERT INTO tasks_fts(rowid, title, description)
    VALUES (new.rowid, new.title, new.description);
END;
"""


# v2: add a signed integer priority column to tasks.
# Range -2..2 (Lowest..Highest), default 0 (Medium). The CHECK constraint
# is enforced at the database level so corrupt values cannot be written
# even if API validation is bypassed.
_SCHEMA_V2 = """
ALTER TABLE tasks ADD COLUMN priority INTEGER NOT NULL DEFAULT 0
    CHECK (priority BETWEEN -2 AND 2);

CREATE INDEX IF NOT EXISTS idx_tasks_priority ON tasks(priority);
"""


# v3: readiness, provenance and dependencies, for agents that pick up work.
#
# stage   - 'backlog' (default: not to be picked up), 'ready' (may be claimed
#           by a planning agent), or 'declined' (its plan was rejected; kept
#           out of the queue until a person returns it to 'ready').
# source  - who created the task: 'person' (default, and every task that
#           existed before v3), or 'goals:<document>' for a task derived from
#           goal documents by an agent. Agents may only edit 'goals:' tasks.
#           The CHECK is the data-integrity backstop the API validation mirrors,
#           so a direct TrackerStore caller cannot persist a value outside the
#           provenance model. GLOB, unlike LIKE, is case-sensitive, so
#           'GOALS:x' is refused just as the API refuses it. The API strips the
#           value and requires a non-empty document after the prefix, so a
#           whitespace-only name ('goals: ', 'goals:\t') is rejected there;
#           the pattern below mirrors that by requiring at least one
#           non-whitespace character after 'goals:', which also rejects a bare
#           'goals:'.
# task_blockers - a task is blocked while any task it names here is open. A
#           task may not block itself (it could never become claimable); the
#           CHECK mirrors the API's refusal for direct TrackerStore callers.
_SCHEMA_V3 = """
ALTER TABLE tasks ADD COLUMN stage TEXT NOT NULL DEFAULT 'backlog'
    CHECK (stage IN ('backlog', 'ready', 'declined'));

ALTER TABLE tasks ADD COLUMN source TEXT NOT NULL DEFAULT 'person'
    CHECK (source = 'person' OR source GLOB
        ('goals:*[^ ' || char(9) || char(10) || char(11) || char(12) || char(13) || ']*'));

CREATE INDEX IF NOT EXISTS idx_tasks_stage ON tasks(stage);

CREATE TABLE IF NOT EXISTS task_blockers (
    task_id    TEXT NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    blocker_id TEXT NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    PRIMARY KEY (task_id, blocker_id),
    CHECK (task_id != blocker_id)
);

CREATE INDEX IF NOT EXISTS idx_task_blockers_blocker ON task_blockers(blocker_id);
"""


def run_migrations(conn: sqlite3.Connection) -> None:
    """Apply all pending schema migrations to the database connection.

    Args:
        conn: An open SQLite connection with autocommit disabled.
    """
    conn.execute("PRAGMA foreign_keys = ON")
    conn.execute("PRAGMA journal_mode = WAL")

    version = _get_version(conn)

    if version < 1:
        # schema_version is created empty by the v1 script, so the version is
        # recorded with an INSERT rather than the UPDATE the later steps use.
        _apply(conn, _SCHEMA_V1
               + "DELETE FROM schema_version;\nINSERT INTO schema_version VALUES (1);")
        version = 1

    if version < 2:
        _apply(conn, _SCHEMA_V2 + "UPDATE schema_version SET version = 2;")
        version = 2

    if version < 3:
        _apply(conn, _SCHEMA_V3 + "UPDATE schema_version SET version = 3;")
        version = 3


def _apply(conn: sqlite3.Connection, script: str) -> None:
    """Apply one migration and its version bump as a single transaction.

    SQLite DDL is transactional, but :meth:`sqlite3.Connection.executescript`
    disregards ``isolation_level`` and commits any pending transaction before
    it runs, so it cannot on its own keep a multi-statement migration atomic:
    were a later statement to fail (or the process to die) partway through, an
    earlier ``ALTER TABLE`` would stay committed while ``schema_version`` stayed
    behind, and every subsequent startup would fail re-applying the migration on
    a column that already exists. Transaction control is therefore written into
    the script itself — the DDL and the version bump run inside an explicit
    ``BEGIN``/``COMMIT`` — and any failure rolls the whole migration back so it
    is retried cleanly on the next startup.

    Args:
        conn: An open SQLite connection.
        script: The migration's SQL, including its ``schema_version`` update.
    """
    try:
        conn.executescript("BEGIN;\n" + script + "\nCOMMIT;")
    except BaseException:
        conn.rollback()
        raise


def _get_version(conn: sqlite3.Connection) -> int:
    """Return the current schema version (0 if uninitialized)."""
    try:
        row = conn.execute("SELECT version FROM schema_version").fetchone()
        return row[0] if row else 0
    except sqlite3.OperationalError:
        return 0
