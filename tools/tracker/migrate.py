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
#           provenance model. 'goals:_%' requires at least one character after
#           the prefix, rejecting a bare 'goals:'.
# task_blockers - a task is blocked while any task it names here is open.
_SCHEMA_V3 = """
ALTER TABLE tasks ADD COLUMN stage TEXT NOT NULL DEFAULT 'backlog'
    CHECK (stage IN ('backlog', 'ready', 'declined'));

ALTER TABLE tasks ADD COLUMN source TEXT NOT NULL DEFAULT 'person'
    CHECK (source = 'person' OR source LIKE 'goals:_%');

CREATE INDEX IF NOT EXISTS idx_tasks_stage ON tasks(stage);

CREATE TABLE IF NOT EXISTS task_blockers (
    task_id    TEXT NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    blocker_id TEXT NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    PRIMARY KEY (task_id, blocker_id)
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
        conn.executescript(_SCHEMA_V1)
        if _get_version(conn) == 0:
            conn.execute("DELETE FROM schema_version")
            conn.execute("INSERT INTO schema_version VALUES (1)")
        conn.commit()
        version = 1

    if version < 2:
        conn.executescript(_SCHEMA_V2)
        conn.execute("UPDATE schema_version SET version = 2")
        conn.commit()

    if version < 3:
        conn.executescript(_SCHEMA_V3)
        conn.execute("UPDATE schema_version SET version = 3")
        conn.commit()


def _get_version(conn: sqlite3.Connection) -> int:
    """Return the current schema version (0 if uninitialized)."""
    try:
        row = conn.execute("SELECT version FROM schema_version").fetchone()
        return row[0] if row else 0
    except sqlite3.OperationalError:
        return 0
