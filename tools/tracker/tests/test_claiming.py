"""Tests for task readiness, provenance, blockers and claiming.

A planning agent may only take a task a person (or an approved goal document)
has marked ready, that nobody has taken, and that is not waiting on unfinished
work. The count the controller's gate reports and the claim an agent makes use
the same definition, so these tests check both against the same fixtures.
"""

import os
import sqlite3
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from starlette.testclient import TestClient

import migrate
from store import TrackerStore
from api import create_http_app


class _StoreTestBase(unittest.TestCase):
    """A fresh store with one project and release."""

    def setUp(self):
        tmp = tempfile.NamedTemporaryFile(suffix=".db", delete=False)
        tmp.close()
        self.path = tmp.name
        self.store = TrackerStore(self.path)
        self.project = self.store.create_project("Framework")
        self.release = self.store.create_release("Framework 1.2", self.project["id"])
        self.client = TestClient(create_http_app(self.store))

    def tearDown(self):
        self.store.close()
        os.unlink(self.path)

    def _task(self, title, stage="ready", priority=0, **kwargs):
        return self.store.create_task(
            title=title, stage=stage, priority=priority,
            project_id=self.project["id"], release_id=self.release["id"], **kwargs)

    def _count(self):
        return self.store.count_claimable(self.release["id"])


class ClaimableTests(_StoreTestBase):
    """What counts as claimable."""

    def test_a_new_task_is_backlog_by_a_person(self):
        task = self.store.create_task(title="t", release_id=self.release["id"])
        self.assertEqual("backlog", task["stage"])
        self.assertEqual("person", task["source"])
        self.assertEqual([], task["blocked_by"])

    def test_only_ready_tasks_are_claimable(self):
        self._task("backlog", stage="backlog")
        self._task("declined", stage="declined")
        self.assertEqual(0, self._count())
        self._task("ready")
        self.assertEqual(1, self._count())

    def test_closed_and_claimed_tasks_are_not_claimable(self):
        self._task("closed", status="closed")
        self._task("claimed", workstream_id="ws-1")
        self.assertEqual(0, self._count())

    def test_an_open_blocker_holds_a_task_back_until_it_closes(self):
        blocker = self._task("blocker", stage="backlog")
        self._task("blocked", blocked_by=[blocker["id"]])
        self.assertEqual(0, self._count())
        self.store.update_task(blocker["id"], status="closed")
        self.assertEqual(1, self._count())

    def test_other_releases_do_not_count(self):
        other = self.store.create_release("Framework 1.3", self.project["id"])
        self.store.create_task(title="later", stage="ready", release_id=other["id"])
        self.assertEqual(0, self._count())


class ClaimTests(_StoreTestBase):
    """Taking the next task."""

    def test_the_highest_priority_task_is_claimed_first(self):
        self._task("low", priority=-1)
        high = self._task("high", priority=2)
        claimed = self.store.claim_next(self.release["id"], "ws-1")
        self.assertEqual(high["id"], claimed["id"])
        self.assertEqual("ws-1", claimed["workstream_id"])

    def test_the_oldest_task_wins_among_equal_priorities(self):
        older = self.store.create_task(
            title="older", stage="ready", release_id=self.release["id"],
            created_at="2020-01-01T00:00:00Z")
        self.store.create_task(
            title="newer", stage="ready", release_id=self.release["id"],
            created_at="2021-01-01T00:00:00Z")
        self.assertEqual(older["id"], self.store.claim_next(self.release["id"], "ws")["id"])

    def test_two_claims_never_take_the_same_task(self):
        first = self._task("a")
        second = self._task("b")
        claimed = {self.store.claim_next(self.release["id"], "ws-1")["id"],
                   self.store.claim_next(self.release["id"], "ws-2")["id"]}
        self.assertEqual({first["id"], second["id"]}, claimed)
        self.assertIsNone(self.store.claim_next(self.release["id"], "ws-3"))

    def test_nothing_claimable_returns_none(self):
        self._task("backlog", stage="backlog")
        self.assertIsNone(self.store.claim_next(self.release["id"], "ws-1"))


class BlockerTests(_StoreTestBase):
    """blocked_by is stored as a set and replaced on update."""

    def test_update_replaces_the_blockers(self):
        a = self._task("a")
        b = self._task("b")
        task = self._task("t", blocked_by=[a["id"]])
        self.assertEqual([a["id"]], task["blocked_by"])
        task = self.store.update_task(task["id"], blocked_by=[b["id"]])
        self.assertEqual([b["id"]], task["blocked_by"])
        task = self.store.update_task(task["id"], blocked_by=[])
        self.assertEqual([], task["blocked_by"])

    def test_listed_tasks_carry_their_blockers(self):
        a = self._task("a")
        self._task("t", blocked_by=[a["id"]])
        tasks = {t["title"]: t for t in self.store.list_tasks()["tasks"]}
        self.assertEqual([a["id"]], tasks["t"]["blocked_by"])

    def test_an_unknown_blocker_rolls_back_the_created_task(self):
        with self.assertRaises(sqlite3.IntegrityError):
            self._task("orphan", task_id="orphan-id", blocked_by=["no-such-task"])
        self.store.create_project("unrelated write")
        self.assertIsNone(self.store.get_task("orphan-id"))
        self.assertEqual(0, self.store.list_tasks()["total"])

    def test_an_unknown_blocker_rolls_back_the_whole_update(self):
        a = self._task("a")
        task = self._task("t", blocked_by=[a["id"]])
        with self.assertRaises(sqlite3.IntegrityError):
            self.store.update_task(task["id"], title="renamed",
                                   blocked_by=["no-such-task"])
        self.store.create_project("unrelated write")
        stored = self.store.get_task(task["id"])
        self.assertEqual("t", stored["title"])
        self.assertEqual([a["id"]], stored["blocked_by"])

    def test_a_rolled_back_write_leaves_the_store_usable(self):
        with self.assertRaises(sqlite3.IntegrityError):
            self._task("orphan", blocked_by=["no-such-task"])
        task = self._task("next")
        self.assertEqual("next", self.store.get_task(task["id"])["title"])

    def test_a_repeated_blocker_is_stored_once(self):
        a = self._task("a")
        task = self._task("t", blocked_by=[a["id"], a["id"]])
        self.assertEqual([a["id"]], task["blocked_by"])
        task = self.store.update_task(task["id"], blocked_by=[a["id"], a["id"]])
        self.assertEqual([a["id"]], task["blocked_by"])

    def test_a_task_blocking_itself_is_refused_by_the_database_on_create(self):
        with self.assertRaises(sqlite3.IntegrityError):
            self._task("loop", task_id="loop-id", blocked_by=["loop-id"])
        self.store.create_project("unrelated write")
        self.assertIsNone(self.store.get_task("loop-id"))

    def test_a_task_blocking_itself_is_refused_by_the_database_on_update(self):
        a = self._task("a")
        task = self._task("t", blocked_by=[a["id"]])
        with self.assertRaises(sqlite3.IntegrityError):
            self.store.update_task(task["id"], blocked_by=[a["id"], task["id"]])
        self.store.create_project("unrelated write")
        self.assertEqual([a["id"]], self.store.get_task(task["id"])["blocked_by"])


class MigrationTests(unittest.TestCase):
    """Tasks that existed before readiness was tracked stay out of the queue."""

    def test_existing_tasks_become_backlog_tasks_by_a_person(self):
        tmp = tempfile.NamedTemporaryFile(suffix=".db", delete=False)
        tmp.close()
        try:
            conn = sqlite3.connect(tmp.name)
            conn.executescript(migrate._SCHEMA_V1)
            conn.execute("INSERT INTO schema_version VALUES (1)")
            conn.executescript(migrate._SCHEMA_V2)
            conn.execute("UPDATE schema_version SET version = 2")
            conn.execute(
                "INSERT INTO tasks (id, title, status, created_at, updated_at) "
                "VALUES ('old', 'old task', 'open', 'x', 'x')")
            conn.commit()
            conn.close()

            store = TrackerStore(tmp.name)
            task = store.get_task("old")
            store.close()
            self.assertEqual("backlog", task["stage"])
            self.assertEqual("person", task["source"])
        finally:
            os.unlink(tmp.name)

    def test_a_failed_migration_rolls_back_and_keeps_the_old_version(self):
        # A migration that fails partway must not leave the schema half-applied
        # with the version behind: the next startup would then re-run it and
        # fail forever on a column that already exists.
        tmp = tempfile.NamedTemporaryFile(suffix=".db", delete=False)
        tmp.close()
        try:
            conn = sqlite3.connect(tmp.name)
            conn.executescript(migrate._SCHEMA_V1)
            conn.execute("INSERT INTO schema_version VALUES (1)")
            conn.executescript(migrate._SCHEMA_V2)
            conn.execute("UPDATE schema_version SET version = 2")
            # Force v3 to fail on its second statement: 'source' already exists,
            # so 'ADD COLUMN source' raises after 'ADD COLUMN stage' has run in
            # the same transaction.
            conn.execute("ALTER TABLE tasks ADD COLUMN source TEXT")
            conn.commit()

            with self.assertRaises(sqlite3.OperationalError):
                migrate.run_migrations(conn)

            columns = {row[1] for row in conn.execute("PRAGMA table_info(tasks)")}
            self.assertNotIn("stage", columns)
            self.assertEqual(2, migrate._get_version(conn))
            conn.close()
        finally:
            os.unlink(tmp.name)


class SourceConstraintTests(_StoreTestBase):
    """The database, not only the API, confines provenance to the model."""

    def test_a_free_form_source_is_refused_by_the_database(self):
        with self.assertRaises(sqlite3.IntegrityError):
            self.store.create_task(
                title="t", source="automation", release_id=self.release["id"])

    def test_a_bare_goals_prefix_is_refused_by_the_database(self):
        with self.assertRaises(sqlite3.IntegrityError):
            self.store.create_task(
                title="t", source="goals:", release_id=self.release["id"])

    def test_the_goals_prefix_is_case_sensitive_in_the_database(self):
        # The API recognises only lowercase 'goals:'; a case-insensitive
        # predicate (SQLite LIKE) would let a direct caller store 'GOALS:x'.
        for source in ("GOALS:docs/GOALS.md", "Goals:x", "PERSON"):
            with self.assertRaises(sqlite3.IntegrityError, msg=source):
                self.store.create_task(
                    title="t", source=source, release_id=self.release["id"])
        with self.assertRaises(sqlite3.IntegrityError):
            task = self.store.create_task(title="t", release_id=self.release["id"])
            self.store.update_task(task["id"], source="GOALS:x")

    def test_a_whitespace_only_goal_document_is_refused_by_the_database(self):
        # The API strips the source and requires a non-empty document after the
        # prefix, so 'goals: ' and 'goals:\t' are rejected there; the CHECK
        # mirrors that instead of accepting a whitespace-only document name.
        for source in ("goals: ", "goals:\t", "goals:   ", "goals:\t\n"):
            with self.assertRaises(sqlite3.IntegrityError, msg=source):
                self.store.create_task(
                    title="t", source=source, release_id=self.release["id"])
        task = self.store.create_task(title="t", release_id=self.release["id"])
        with self.assertRaises(sqlite3.IntegrityError):
            self.store.update_task(task["id"], source="goals: ")

    def test_the_api_refuses_an_uppercase_goals_prefix(self):
        resp = self.client.post("/v1/tasks", json={
            "title": "t", "release_id": self.release["id"], "source": "GOALS:x"})
        self.assertEqual(400, resp.status_code)

    def test_the_api_refuses_a_whitespace_only_goal_document(self):
        resp = self.client.post("/v1/tasks", json={
            "title": "t", "release_id": self.release["id"], "source": "goals: "})
        self.assertEqual(400, resp.status_code)

    def test_person_and_goal_sources_are_accepted(self):
        person = self.store.create_task(title="p", release_id=self.release["id"])
        self.assertEqual("person", person["source"])
        goal = self.store.create_task(
            title="g", source="goals:docs/GOALS.md", release_id=self.release["id"])
        self.assertEqual("goals:docs/GOALS.md", goal["source"])


class DuplicateReleaseTests(_StoreTestBase):
    """Release names are not unique, so lookup resolves one deterministically."""

    def test_lookup_resolves_the_oldest_duplicate_every_time(self):
        for rid, created in (("rel-new", "2021-01-01T00:00:00Z"),
                             ("rel-old", "2020-01-01T00:00:00Z")):
            self.store._conn.execute(
                "INSERT INTO releases (id, name, project_id, created_at) "
                "VALUES (?, ?, ?, ?)",
                (rid, "Dup 1.0", self.project["id"], created))
        self.store._conn.commit()
        # The oldest wins, and the same release resolves on repeated lookups, so
        # a claimable count and a claim can never target different ids.
        self.assertEqual("rel-old", self.store.find_release("Framework", "Dup 1.0")["id"])
        self.assertEqual("rel-old", self.store.find_release("Framework", "Dup 1.0")["id"])


class EnsureReleaseTests(_StoreTestBase):
    """Get-or-create of a release by name is atomic."""

    def _releases_named(self, name):
        return self.store._conn.execute(
            "SELECT COUNT(*) FROM releases WHERE name = ?", (name,)).fetchone()[0]

    def test_an_existing_release_is_returned_not_duplicated(self):
        release, created = self.store.ensure_release("Framework", "Framework 1.2")
        self.assertFalse(created)
        self.assertEqual(self.release["id"], release["id"])
        self.assertEqual(1, self._releases_named("Framework 1.2"))

    def test_a_missing_release_is_created_in_the_project(self):
        release, created = self.store.ensure_release("Framework", "Framework 1.3")
        self.assertTrue(created)
        self.assertEqual(self.project["id"], release["project_id"])
        self.assertEqual("Framework", release["project_name"])
        # A second call finds the release the first one created.
        again, created_again = self.store.ensure_release("Framework", "Framework 1.3")
        self.assertFalse(created_again)
        self.assertEqual(release["id"], again["id"])
        self.assertEqual(release["id"], self.store.find_release("Framework", "Framework 1.3")["id"])

    def test_a_missing_project_is_never_created(self):
        release, created = self.store.ensure_release("Nowhere", "Nowhere 1.0")
        self.assertIsNone(release)
        self.assertFalse(created)
        self.assertEqual(["Framework"], [p["name"] for p in self.store.list_projects()])
        self.assertEqual(0, self._releases_named("Nowhere 1.0"))

    def test_the_same_name_in_another_project_is_a_different_release(self):
        self.store.create_project("Application")
        release, created = self.store.ensure_release("Application", "Framework 1.2")
        self.assertTrue(created)
        self.assertNotEqual(self.release["id"], release["id"])

    def test_concurrent_callers_receive_one_release(self):
        barrier = threading.Barrier(8)
        ids = []

        def ensure():
            barrier.wait()
            release, _ = self.store.ensure_release("Framework", "Framework 2.0")
            ids.append(release["id"])

        threads = [threading.Thread(target=ensure) for _ in range(8)]
        for t in threads:
            t.start()
        for t in threads:
            t.join(timeout=30)
        self.assertEqual(8, len(ids))
        self.assertEqual(1, len(set(ids)))
        self.assertEqual(1, self._releases_named("Framework 2.0"))


class SharedConnectionLockTests(_StoreTestBase):
    """Every write on the shared connection is serialized by the store lock.

    Without this, a write that commits on the shared connection while another
    thread holds an open :meth:`TrackerStore._write` transaction commits that
    transaction's uncommitted rows, so a rollback in the failing write becomes
    a no-op and leaves a partial task behind.
    """

    class _CountingLock:
        """Wraps a real lock and counts how often it is entered."""

        def __init__(self, real):
            self._real = real
            self.entered = 0

        def __enter__(self):
            self.entered += 1
            return self._real.__enter__()

        def __exit__(self, *exc):
            return self._real.__exit__(*exc)

    def _entries_during(self, call):
        counting = self._CountingLock(self.store._lock)
        self.store._lock = counting
        try:
            call()
        finally:
            self.store._lock = counting._real
        return counting.entered

    def test_every_mutating_method_takes_the_store_lock(self):
        project = self.store.create_project("Locked")
        release = self.store.create_release("Locked 1.0", project["id"])
        task = self.store.create_task(title="t", release_id=release["id"])
        cases = {
            "create_project": lambda: self.store.create_project("p2"),
            "update_project": lambda: self.store.update_project(project["id"], "renamed"),
            "create_release": lambda: self.store.create_release("Locked 2.0", project["id"]),
            "update_release": lambda: self.store.update_release(release["id"], name="Locked 1.1"),
            "delete_task": lambda: self.store.delete_task(task["id"]),
            "delete_release": lambda: self.store.delete_release(release["id"]),
            "delete_project": lambda: self.store.delete_project(project["id"]),
        }
        for name, call in cases.items():
            self.assertGreaterEqual(self._entries_during(call), 1, name)

    def test_a_concurrent_write_cannot_commit_a_failing_writes_partial_row(self):
        # While a create_task that is about to fail holds the lock, a concurrent
        # delete must wait for it to roll back rather than commit its partial
        # INSERT. Before the fix the delete committed on the shared connection
        # without the lock and left the half-written task behind.
        victim = self._task("victim")
        started = threading.Event()
        proceed = threading.Event()
        real_set_blockers = self.store._set_blockers

        def slow_set_blockers(task_id, blocker_ids):
            started.set()
            proceed.wait(5)
            return real_set_blockers(task_id, blocker_ids)

        def failing_create():
            try:
                with patch.object(self.store, "_set_blockers", slow_set_blockers):
                    self.store.create_task(title="leak", task_id="leak-id",
                                           blocked_by=["no-such-task"])
            except sqlite3.IntegrityError:
                pass

        creator = threading.Thread(target=failing_create)
        creator.start()
        self.assertTrue(started.wait(5))
        deleter = threading.Thread(target=lambda: self.store.delete_task(victim["id"]))
        deleter.start()
        proceed.set()
        creator.join(5)
        deleter.join(5)
        self.store.create_project("later write")
        self.assertIsNone(self.store.get_task("leak-id"))
        self.assertIsNone(self.store.get_task(victim["id"]))


class GoalTaskUpsertTests(_StoreTestBase):
    """Ensuring the release and writing the goal task are one transaction."""

    def _releases_named(self, name):
        return self.store._conn.execute(
            "SELECT COUNT(*) FROM releases WHERE name = ?", (name,)).fetchone()[0]

    def test_a_new_task_is_created_ready_in_a_created_release(self):
        result = self.store.upsert_goal_task(
            "Framework", "Framework 3.0", "Add a thing", "goals:docs/PLAN.md",
            description="why", priority=1)
        self.assertEqual("ok", result["status"])
        self.assertTrue(result["created_release"])
        task = result["task"]
        self.assertEqual(("Add a thing", "ready", "open", "goals:docs/PLAN.md", 1),
                         (task["title"], task["stage"], task["status"],
                          task["source"], task["priority"]))
        self.assertEqual(result["release"]["id"], task["release_id"])

    def test_an_existing_release_is_reused_not_duplicated(self):
        result = self.store.upsert_goal_task(
            "Framework", "Framework 1.2", "t", "goals:docs/PLAN.md")
        self.assertEqual("ok", result["status"])
        self.assertFalse(result["created_release"])
        self.assertEqual(self.release["id"], result["release"]["id"])
        self.assertEqual(1, self._releases_named("Framework 1.2"))

    def test_a_missing_project_creates_no_release(self):
        result = self.store.upsert_goal_task(
            "Nowhere", "Nowhere 1.0", "t", "goals:docs/PLAN.md")
        self.assertEqual("project_not_found", result["status"])
        self.assertEqual(0, self._releases_named("Nowhere 1.0"))

    def test_an_unknown_blocker_rolls_back_the_created_release(self):
        result = self.store.upsert_goal_task(
            "Framework", "Framework 3.1", "t", "goals:docs/PLAN.md",
            blocked_by=["no-such-task"])
        self.assertEqual("db_error", result["status"])
        # The release this call would have created is rolled back with the task.
        self.store.create_project("later write")
        self.assertEqual(0, self._releases_named("Framework 3.1"))
        self.assertIsNone(self.store.find_release("Framework", "Framework 3.1"))

    def test_updating_a_person_task_is_refused_and_creates_no_release(self):
        person = self.store.create_task(
            title="hand written", release_id=self.release["id"], source="person")
        result = self.store.upsert_goal_task(
            "Framework", "Framework 3.2", "hijack", "goals:docs/PLAN.md",
            task_id=person["id"])
        self.assertEqual("not_goal_derived", result["status"])
        self.store.create_project("later write")
        self.assertEqual(0, self._releases_named("Framework 3.2"))
        kept = self.store.get_task(person["id"])
        self.assertEqual(("hand written", "person"), (kept["title"], kept["source"]))

    def test_updating_a_missing_task_is_task_not_found_and_creates_no_release(self):
        result = self.store.upsert_goal_task(
            "Framework", "Framework 3.3", "t", "goals:docs/PLAN.md",
            task_id="ghost")
        self.assertEqual("task_not_found", result["status"])
        self.store.create_project("later write")
        self.assertEqual(0, self._releases_named("Framework 3.3"))

    def test_updating_a_goal_task_changes_fields_and_keeps_stage_and_status(self):
        goal = self.store.create_task(
            title="original", release_id=self.release["id"], stage="ready",
            source="goals:docs/OLD.md", priority=0)
        result = self.store.upsert_goal_task(
            "Framework", "Framework 1.2", "renamed", "goals:docs/PLAN.md",
            priority=2, task_id=goal["id"])
        self.assertEqual("ok", result["status"])
        task = result["task"]
        self.assertEqual(("renamed", 2, "goals:docs/PLAN.md"),
                         (task["title"], task["priority"], task["source"]))
        # Stage and status are never touched by an upsert.
        self.assertEqual(("ready", "open"), (task["stage"], task["status"]))

    # --- API surface -------------------------------------------------------

    def _post_goal_task(self, **body):
        payload = {"project": "Framework", "release": "Framework 1.2",
                   "title": "t", "source": "goals:docs/PLAN.md"}
        payload.update(body)
        return self.client.post("/v1/goal-tasks", json=payload)

    def test_the_endpoint_creates_a_task_and_release(self):
        resp = self._post_goal_task(release="Framework 4.0", title="new")
        self.assertEqual(201, resp.status_code, resp.text)
        body = resp.json()
        self.assertTrue(body["ok"])
        self.assertTrue(body["created_release"])
        self.assertEqual("ready", body["task"]["stage"])

    def test_the_endpoint_updates_with_a_task_id(self):
        goal = self.store.create_task(
            title="orig", release_id=self.release["id"], stage="ready",
            source="goals:docs/OLD.md")
        resp = self._post_goal_task(title="renamed", task_id=goal["id"])
        self.assertEqual(200, resp.status_code, resp.text)
        self.assertEqual("renamed", resp.json()["task"]["title"])

    def test_a_person_task_update_is_409_and_leaves_no_orphan_release(self):
        person = self.store.create_task(
            title="hand written", release_id=self.release["id"], source="person")
        resp = self._post_goal_task(release="Framework 4.1", task_id=person["id"])
        self.assertEqual(409, resp.status_code, resp.text)
        self.assertEqual(0, self._releases_named("Framework 4.1"))

    def test_an_unknown_blocker_is_a_bad_request_and_leaves_no_orphan_release(self):
        resp = self._post_goal_task(release="Framework 4.2", blocked_by=["no-such-task"])
        self.assertEqual(400, resp.status_code, resp.text)
        self.assertEqual(0, self._releases_named("Framework 4.2"))

    def test_a_missing_project_is_404(self):
        resp = self._post_goal_task(project="Nowhere", release="Nowhere 9.9")
        self.assertEqual(404, resp.status_code, resp.text)

    def test_a_non_goal_source_is_refused(self):
        for source in ("person", "goals:", "automation", "GOALS:x"):
            resp = self._post_goal_task(release="Framework 4.3", source=source)
            self.assertEqual(400, resp.status_code, source)
        self.assertEqual(0, self._releases_named("Framework 4.3"))

    def test_a_blank_title_is_refused(self):
        resp = self._post_goal_task(release="Framework 4.4", title="   ")
        self.assertEqual(400, resp.status_code)
        self.assertEqual(0, self._releases_named("Framework 4.4"))


class BulkImportTests(_StoreTestBase):
    """POST /v1/import carries readiness, provenance and blockers."""

    def _import(self, *tasks):
        return self.client.post("/v1/import", json={"tasks": list(tasks)})

    def _goal_task(self, task_id, **fields):
        task = {"id": task_id, "title": task_id, "release_id": self.release["id"],
                "stage": "ready", "source": "goals:docs/GOALS.md"}
        task.update(fields)
        return task

    def test_a_ready_goal_task_is_imported_as_given(self):
        resp = self._import(self._goal_task("g1"))
        self.assertEqual(200, resp.status_code, resp.text)
        task = self.store.get_task("g1")
        self.assertEqual("ready", task["stage"])
        self.assertEqual("goals:docs/GOALS.md", task["source"])
        self.assertEqual(1, self._count())

    def test_a_blocker_may_appear_later_in_the_same_import(self):
        resp = self._import(self._goal_task("blocked", blocked_by=["blocker"]),
                            self._goal_task("blocker", stage="backlog"))
        self.assertEqual(200, resp.status_code, resp.text)
        self.assertEqual(["blocker"], self.store.get_task("blocked")["blocked_by"])
        # The blocker is still open, so the imported ready task is held back.
        self.assertEqual(0, self._count())

    def test_absent_fields_take_defaults_on_insert_and_are_kept_on_update(self):
        blocker = self._task("blocker", stage="backlog")
        self._import({"id": "plain", "title": "plain", "release_id": self.release["id"]})
        plain = self.store.get_task("plain")
        self.assertEqual(("backlog", "person", []),
                         (plain["stage"], plain["source"], plain["blocked_by"]))

        self._import(self._goal_task("g1", blocked_by=[blocker["id"]]))
        self._import({"id": "g1", "title": "renamed"})
        kept = self.store.get_task("g1")
        self.assertEqual("renamed", kept["title"])
        self.assertEqual(("ready", "goals:docs/GOALS.md", [blocker["id"]]),
                         (kept["stage"], kept["source"], kept["blocked_by"]))

    def test_supplied_fields_replace_existing_values_on_update(self):
        blocker = self._task("blocker", stage="backlog")
        self._import(self._goal_task("g1", blocked_by=[blocker["id"]]))
        self._import({"id": "g1", "stage": "declined", "source": "person", "blocked_by": []})
        task = self.store.get_task("g1")
        self.assertEqual(("declined", "person", []),
                         (task["stage"], task["source"], task["blocked_by"]))

    def test_invalid_task_fields_are_refused_before_anything_is_written(self):
        valid = self._goal_task("ok")
        for bad in (self._goal_task("bad", stage="started"),
                    self._goal_task("bad", source="GOALS:x"),
                    self._goal_task("bad", source="goals:"),
                    self._goal_task("bad", blocked_by=["missing"]),
                    self._goal_task("bad", blocked_by=["bad"]),
                    self._goal_task("bad", blocked_by="ok")):
            resp = self._import(valid, bad)
            self.assertEqual(400, resp.status_code, bad)
            self.assertIn("tasks[1]", resp.json()["error"])
        self.assertIsNone(self.store.get_task("ok"))
        self.assertIsNone(self.store.get_task("bad"))

    def test_a_task_that_is_not_an_object_is_refused(self):
        resp = self._import("not-a-task")
        self.assertEqual(400, resp.status_code)

    def test_a_rejection_by_the_store_rolls_back_the_whole_import(self):
        # Rejections only the store or the database detects - a missing id, an
        # out-of-range priority, an unknown foreign key - must not leave the
        # records before them in an open transaction for a later write to commit.
        valid = self._goal_task("ok")
        for bad in ({"title": "no id"},
                    self._goal_task("bad", priority=3),
                    self._goal_task("bad", project_id="no-such-project")):
            resp = self._import(valid, bad)
            self.assertEqual(400, resp.status_code, bad)
            self.store.create_task(title="later write")
            self.assertIsNone(self.store.get_task("ok"), bad)
            self.assertIsNone(self.store.get_task("bad"), bad)

    def test_projects_and_releases_are_rolled_back_with_a_rejected_task(self):
        resp = self.client.post("/v1/import", json={
            "projects": [{"id": "p-new", "name": "New"}],
            "releases": [{"id": "r-new", "name": "New 1.0", "project_id": "p-new"}],
            "tasks": [{"title": "no id"}]})
        self.assertEqual(400, resp.status_code)
        self.store.create_task(title="later write")
        self.assertIsNone(self.store.get_project("p-new"))
        self.assertIsNone(self.store.get_release("r-new"))

    def test_an_unexpected_failure_rolls_back_the_whole_import(self):
        # A malformed record that raises something other than a database
        # error must still leave nothing for a later write to commit.
        with self.assertRaises(AttributeError):
            self.store.bulk_import([{"id": "p-new", "name": "New"}, "not-a-project"], [], [])
        self.store.create_task(title="later write")
        self.assertIsNone(self.store.get_project("p-new"))

    def test_malformed_projects_and_releases_are_refused_with_a_bad_request(self):
        for body, expected in (
                ({"projects": [{"id": "p-new", "name": "New"}, "x"]}, "projects[1]"),
                ({"releases": [7]}, "releases[0]"),
                ({"projects": {"id": "p-new"}}, "projects must be a list"),
                ({"tasks": "t"}, "tasks must be a list")):
            resp = self.client.post("/v1/import", json=body)
            self.assertEqual(400, resp.status_code, body)
            self.assertIn(expected, resp.json()["error"])
        self.store.create_task(title="later write")
        self.assertIsNone(self.store.get_project("p-new"))

    def test_falsey_collections_of_the_wrong_type_are_refused(self):
        for body, expected in (
                ({"tasks": {}}, "tasks must be a list"),
                ({"projects": ""}, "projects must be a list"),
                ({"releases": 0}, "releases must be a list"),
                ({"tasks": False}, "tasks must be a list")):
            resp = self.client.post("/v1/import", json=body)
            self.assertEqual(400, resp.status_code, body)
            self.assertIn(expected, resp.json()["error"])

    def test_absent_collections_are_an_empty_import(self):
        resp = self.client.post("/v1/import", json={})
        self.assertEqual(200, resp.status_code, resp.text)
        self.assertEqual({"projects": 0, "releases": 0, "tasks": 0}, resp.json()["created"])
        self.assertEqual({"projects": 0, "releases": 0, "tasks": 0}, resp.json()["updated"])

    def test_a_new_task_the_database_refuses_is_reported_not_counted(self):
        # A direct store caller bypasses the API's field checks; the CHECK and
        # NOT NULL constraints must then fail the import instead of the insert
        # being skipped and the task counted as updated.
        for bad in (self._goal_task("bad", stage="started"),
                    self._goal_task("bad", source="GOALS:x"),
                    self._goal_task("bad", title=None)):
            result = self.store.bulk_import(
                [{"id": "p-new", "name": "New"}], [], [self._goal_task("ok"), bad])
            self.assertIn("error", result, bad)
            self.assertIn("import rejected by the database", result["error"])
            self.store.create_task(title="later write")
            self.assertIsNone(self.store.get_task("ok"), bad)
            self.assertIsNone(self.store.get_task("bad"), bad)
            self.assertIsNone(self.store.get_project("p-new"), bad)

    def test_new_and_existing_records_are_counted_separately(self):
        payload = {"projects": [{"id": "p-new", "name": "New"}],
                   "releases": [{"id": "r-new", "name": "New 1.0", "project_id": "p-new"}],
                   "tasks": [self._goal_task("g1")]}
        first = self.client.post("/v1/import", json=payload).json()
        self.assertEqual({"projects": 1, "releases": 1, "tasks": 1}, first["created"])
        self.assertEqual({"projects": 0, "releases": 0, "tasks": 0}, first["updated"])
        second = self.client.post("/v1/import", json=payload).json()
        self.assertEqual({"projects": 0, "releases": 0, "tasks": 0}, second["created"])
        self.assertEqual({"projects": 1, "releases": 1, "tasks": 1}, second["updated"])


class GoalOnlyUpdateTests(_StoreTestBase):
    """An update restricted to goal-derived tasks checks and writes atomically."""

    def test_a_goal_task_is_updated(self):
        task = self._task("goal", source="goals:docs/GOALS.md")
        updated = self.store.update_task(task["id"], title="renamed", only_goal_derived=True)
        self.assertEqual("renamed", updated["title"])

    def test_a_person_task_is_left_untouched(self):
        blocker = self._task("blocker", stage="backlog")
        task = self._task("mine", source="person", blocked_by=[blocker["id"]])
        result = self.store.update_task(
            task["id"], title="taken", source="goals:docs/GOALS.md", blocked_by=[],
            only_goal_derived=True)
        self.assertIsNone(result)
        kept = self.store.get_task(task["id"])
        self.assertEqual(("mine", "person", [blocker["id"]]),
                         (kept["title"], kept["source"], kept["blocked_by"]))

    def test_without_the_restriction_a_person_task_can_be_changed(self):
        task = self._task("mine", source="person")
        self.assertEqual("edited", self.store.update_task(task["id"], title="edited")["title"])

    def test_a_missing_task_is_not_updated(self):
        self.assertIsNone(self.store.update_task("missing", title="x"))
        self.assertIsNone(self.store.update_task("missing", title="x", only_goal_derived=True))

    def test_the_api_refuses_a_person_task_with_a_conflict(self):
        task = self._task("mine", source="person")
        resp = self.client.put(f"/v1/tasks/{task['id']}?only_goal_derived=true",
                               json={"title": "taken", "source": "goals:docs/GOALS.md"})
        self.assertEqual(409, resp.status_code)
        self.assertIn("only a person may change it", resp.json()["error"])
        self.assertEqual("mine", self.store.get_task(task["id"])["title"])

    def test_the_api_updates_a_goal_task(self):
        task = self._task("goal", source="goals:docs/GOALS.md")
        resp = self.client.put(f"/v1/tasks/{task['id']}?only_goal_derived=true",
                               json={"title": "renamed"})
        self.assertEqual(200, resp.status_code, resp.text)
        self.assertEqual("renamed", resp.json()["task"]["title"])

    def test_the_api_ignores_the_restriction_unless_it_is_true(self):
        task = self._task("mine", source="person")
        resp = self.client.put(f"/v1/tasks/{task['id']}?only_goal_derived=false",
                               json={"title": "edited"})
        self.assertEqual(200, resp.status_code)
        self.assertEqual("edited", resp.json()["task"]["title"])

    def test_the_api_reports_a_missing_task(self):
        resp = self.client.put("/v1/tasks/missing?only_goal_derived=true",
                               json={"title": "x"})
        self.assertEqual(404, resp.status_code)


class ApiTests(_StoreTestBase):
    """The HTTP surface the controller and ar-manager use."""

    def test_claimable_counts_by_name(self):
        self._task("ready")
        resp = self.client.get("/v1/claimable",
                               params={"project": "Framework", "release": "Framework 1.2"})
        self.assertEqual(200, resp.status_code)
        self.assertEqual(1, resp.json()["count"])
        self.assertEqual(self.release["id"], resp.json()["release_id"])

    def test_an_unknown_release_has_nothing_to_claim(self):
        resp = self.client.get("/v1/claimable",
                               params={"project": "Framework", "release": "Framework 9.9"})
        self.assertEqual(200, resp.status_code)
        self.assertEqual(0, resp.json()["count"])

    def test_claimable_requires_both_names(self):
        resp = self.client.get("/v1/claimable", params={"project": "Framework"})
        self.assertEqual(400, resp.status_code)

    def test_claim_links_the_task_to_the_workstream(self):
        task = self._task("ready")
        resp = self.client.post("/v1/claim", json={
            "project": "Framework", "release": "Framework 1.2", "workstream_id": "ws-1"})
        self.assertEqual(task["id"], resp.json()["task"]["id"])
        self.assertEqual("ws-1", self.store.get_task(task["id"])["workstream_id"])
        resp = self.client.post("/v1/claim", json={
            "project": "Framework", "release": "Framework 1.2", "workstream_id": "ws-2"})
        self.assertIsNone(resp.json()["task"])

    def test_claim_requires_a_workstream(self):
        resp = self.client.post("/v1/claim", json={
            "project": "Framework", "release": "Framework 1.2"})
        self.assertEqual(400, resp.status_code)

    def test_release_lookup_by_name(self):
        resp = self.client.get("/v1/releases/lookup",
                               params={"project": "Framework", "release": "Framework 1.2"})
        self.assertEqual(self.release["id"], resp.json()["release"]["id"])
        missing = self.client.get("/v1/releases/lookup",
                                  params={"project": "Framework", "release": "nope"})
        self.assertEqual(404, missing.status_code)

    def test_release_ensure_gets_or_creates_by_name(self):
        existing = self.client.post("/v1/releases/ensure", json={
            "project": "Framework", "release": "Framework 1.2"})
        self.assertEqual(200, existing.status_code)
        self.assertFalse(existing.json()["created"])
        self.assertEqual(self.release["id"], existing.json()["release"]["id"])
        created = self.client.post("/v1/releases/ensure", json={
            "project": "Framework", "release": " Framework 1.3 "})
        self.assertEqual(201, created.status_code)
        self.assertTrue(created.json()["created"])
        self.assertEqual("Framework 1.3", created.json()["release"]["name"])
        again = self.client.post("/v1/releases/ensure", json={
            "project": "Framework", "release": "Framework 1.3"})
        self.assertEqual(200, again.status_code)
        self.assertEqual(created.json()["release"]["id"], again.json()["release"]["id"])

    def test_release_ensure_never_creates_a_project(self):
        resp = self.client.post("/v1/releases/ensure", json={
            "project": "Nowhere", "release": "Nowhere 1.0"})
        self.assertEqual(404, resp.status_code)
        self.assertEqual("Project not found", resp.json()["error"])
        self.assertEqual(400, self.client.post("/v1/releases/ensure", json={
            "project": "Framework", "release": "  "}).status_code)
        self.assertEqual(400, self.client.post("/v1/releases/ensure", json={
            "release": "Framework 1.3"}).status_code)

    def test_task_fields_are_validated(self):
        base = {"title": "t", "release_id": self.release["id"]}
        self.assertEqual(400, self.client.post(
            "/v1/tasks", json=dict(base, stage="started")).status_code)
        self.assertEqual(400, self.client.post(
            "/v1/tasks", json=dict(base, blocked_by=["missing"])).status_code)
        self.assertEqual(400, self.client.post(
            "/v1/tasks", json=dict(base, source="  ")).status_code)
        created = self.client.post("/v1/tasks", json=dict(
            base, stage="ready", source="goals:docs/GOALS.md")).json()["task"]
        self.assertEqual("ready", created["stage"])
        self.assertEqual("goals:docs/GOALS.md", created["source"])
        self.assertEqual(400, self.client.put(
            f"/v1/tasks/{created['id']}", json={"blocked_by": [created["id"]]}).status_code)
        # source is confined to 'person' or 'goals:<document>'; a free-form
        # value and a bare 'goals:' prefix are refused on both create and update.
        self.assertEqual(400, self.client.post(
            "/v1/tasks", json=dict(base, source="automation")).status_code)
        self.assertEqual(400, self.client.post(
            "/v1/tasks", json=dict(base, source="goals:")).status_code)
        self.assertEqual(400, self.client.put(
            f"/v1/tasks/{created['id']}", json={"source": "automation"}).status_code)
        self.assertEqual("person", self.client.post(
            "/v1/tasks", json=dict(base, source="person")).json()["task"]["source"])

    def test_list_filters_by_stage(self):
        self._task("ready")
        self._task("backlog", stage="backlog")
        resp = self.client.get("/v1/tasks", params={"stage": "ready"})
        self.assertEqual(["ready"], [t["title"] for t in resp.json()["tasks"]])

    def test_non_object_bodies_are_refused_with_400(self):
        task = self._task("t")
        writes = [
            ("post", "/v1/projects"),
            ("put", f"/v1/projects/{self.project['id']}"),
            ("post", "/v1/releases"),
            ("put", f"/v1/releases/{self.release['id']}"),
            ("post", "/v1/releases/ensure"),
            ("post", "/v1/tasks"),
            ("put", f"/v1/tasks/{task['id']}"),
            ("post", "/v1/claim"),
            ("post", "/v1/import"),
        ]
        for method, path in writes:
            for raw in (b"[]", b"null", b"\"text\"", b"42", b"[{\"title\": \"t\"}]"):
                with self.subTest(method=method, path=path, body=raw):
                    resp = getattr(self.client, method)(
                        path, content=raw, headers={"Content-Type": "application/json"})
                    self.assertEqual(400, resp.status_code)
                    self.assertEqual(
                        {"ok": False, "error": "JSON body must be an object"}, resp.json())
            with self.subTest(method=method, path=path, body="malformed"):
                resp = getattr(self.client, method)(
                    path, content=b"{not json", headers={"Content-Type": "application/json"})
                self.assertEqual(400, resp.status_code)
                self.assertEqual("Invalid JSON body", resp.json()["error"])
        # Nothing was written by any of the refused requests.
        self.assertEqual("Framework", self.client.get(
            f"/v1/projects/{self.project['id']}").json()["project"]["name"])
        self.assertEqual("t", self.store.get_task(task["id"])["title"])
        self.assertEqual(1, len(self.client.get("/v1/tasks").json()["tasks"]))


class ReadIsolationTests(_StoreTestBase):
    """Reads on the shared connection never see another thread's open write.

    A read on a SQLite connection sees the uncommitted rows of the transaction
    open on that same connection, so a read that did not wait for the store
    lock could report a task a failing write is about to roll back.
    """

    def _read_during_failing_create(self, read):
        """Run *read* on another thread while a doomed create_task holds its
        transaction open, and return what it saw."""
        started = threading.Event()
        proceed = threading.Event()
        real_set_blockers = self.store._set_blockers

        def slow_set_blockers(task_id, blocker_ids):
            started.set()
            proceed.wait(5)
            return real_set_blockers(task_id, blocker_ids)

        def failing_create():
            try:
                with patch.object(self.store, "_set_blockers", slow_set_blockers):
                    self.store.create_task(
                        title="leak", task_id="leak-id", stage="ready",
                        project_id=self.project["id"], release_id=self.release["id"],
                        blocked_by=["no-such-task"])
            except sqlite3.IntegrityError:
                pass

        seen = []
        creator = threading.Thread(target=failing_create)
        creator.start()
        self.assertTrue(started.wait(5))
        reader = threading.Thread(target=lambda: seen.append(read()))
        reader.start()
        # The reader must wait for the write to finish rather than answer from
        # the half-written transaction.
        reader.join(0.5)
        self.assertTrue(reader.is_alive())
        self.assertEqual([], seen)
        proceed.set()
        creator.join(5)
        reader.join(5)
        self.assertEqual(1, len(seen))
        return seen[0]

    def test_get_task_does_not_see_a_row_being_rolled_back(self):
        self.assertIsNone(self._read_during_failing_create(
            lambda: self.store.get_task("leak-id")))

    def test_count_claimable_does_not_count_a_row_being_rolled_back(self):
        self.assertEqual(0, self._read_during_failing_create(self._count))

    def test_list_tasks_does_not_list_a_row_being_rolled_back(self):
        listed = self._read_during_failing_create(
            lambda: self.store.list_tasks(release_id=self.release["id"]))
        self.assertEqual((0, []), (listed["total"], listed["tasks"]))

    def test_health_counts_do_not_count_a_row_being_rolled_back(self):
        self.assertEqual(0, self._read_during_failing_create(self.store.counts)["tasks"])

    def test_reads_inside_a_locked_write_do_not_deadlock(self):
        # bulk_import and upsert_goal_task resolve rows through the locking
        # read methods while holding the lock, which needs the lock reentrant.
        existing = self._task("existing")
        result = self.store.bulk_import([], [], [{"id": existing["id"], "title": "renamed"}])
        self.assertEqual(1, result["updated"]["tasks"])
        upsert = self.store.upsert_goal_task(
            "Framework", "Framework 1.2", "g", "goals:docs/PLAN.md")
        self.assertEqual(("ok", "g"), (upsert["status"], upsert["task"]["title"]))
        self.assertEqual("renamed", self.store.get_task(existing["id"])["title"])


class NonStringFieldTests(_StoreTestBase):
    """A JSON value of the wrong type is a 400, never a 500."""

    _WRONG_TYPES = ([], {}, 7, True)

    def test_an_unhashable_stage_or_status_is_refused_on_create(self):
        for field in ("stage", "status"):
            for value in self._WRONG_TYPES:
                with self.subTest(field=field, value=value):
                    resp = self.client.post("/v1/tasks", json={"title": "t", field: value})
                    self.assertEqual(400, resp.status_code, resp.text)
                    self.assertIn(f"{field} must be one of", resp.json()["error"])
        self.assertEqual(0, self.store.counts()["tasks"])

    def test_an_unhashable_stage_or_status_is_refused_on_update(self):
        task = self._task("t")
        for field in ("stage", "status"):
            for value in self._WRONG_TYPES:
                with self.subTest(field=field, value=value):
                    resp = self.client.put(f"/v1/tasks/{task['id']}", json={field: value})
                    self.assertEqual(400, resp.status_code, resp.text)
        kept = self.store.get_task(task["id"])
        self.assertEqual(("ready", "open"), (kept["stage"], kept["status"]))

    def test_an_unhashable_stage_is_refused_in_a_bulk_import(self):
        resp = self.client.post("/v1/import", json={
            "tasks": [{"id": "imp-1", "title": "t", "stage": ["ready"]}]})
        self.assertEqual(400, resp.status_code, resp.text)
        self.assertTrue(resp.json()["error"].startswith("tasks[0]: stage must be one of"))
        self.assertIsNone(self.store.get_task("imp-1"))

    def test_a_non_string_name_or_title_is_refused(self):
        task = self._task("t")
        writes = [
            ("post", "/v1/projects", "name"),
            ("put", f"/v1/projects/{self.project['id']}", "name"),
            ("post", "/v1/releases", "name"),
            ("post", "/v1/tasks", "title"),
            ("put", f"/v1/tasks/{task['id']}", "title"),
        ]
        for method, path, field in writes:
            for value in self._WRONG_TYPES:
                with self.subTest(path=path, value=value):
                    resp = getattr(self.client, method)(path, json={field: value})
                    self.assertEqual(400, resp.status_code, resp.text)
        self.assertEqual("Framework", self.store.get_project(self.project["id"])["name"])
        self.assertEqual("t", self.store.get_task(task["id"])["title"])

    def test_non_string_release_names_are_refused(self):
        for path, extra in (("/v1/releases/ensure", {}),
                            ("/v1/claim", {"workstream_id": "ws-1"}),
                            ("/v1/goal-tasks", {"title": "t", "source": "goals:d.md"})):
            for field in ("project", "release"):
                with self.subTest(path=path, field=field):
                    body = {"project": "Framework", "release": "Framework 9.0", **extra}
                    body[field] = ["Framework"]
                    resp = self.client.post(path, json=body)
                    self.assertEqual(400, resp.status_code, resp.text)
        self.assertIsNone(self.store.find_release("Framework", "Framework 9.0"))

    def test_a_non_string_workstream_id_cannot_claim(self):
        self._task("ready")
        resp = self.client.post("/v1/claim", json={
            "project": "Framework", "release": "Framework 1.2", "workstream_id": 42})
        self.assertEqual(400, resp.status_code, resp.text)
        self.assertEqual(1, self._count())

    def test_goal_task_fields_of_the_wrong_type_are_refused(self):
        goal = self._task("orig", source="goals:docs/OLD.md")
        base = {"project": "Framework", "release": "Framework 1.2",
                "title": "t", "source": "goals:docs/PLAN.md"}
        for field, value, message in (
                ("title", ["t"], "title is required"),
                ("source", {"goals": "x"}, "source must be 'goals:<document>'"),
                ("task_id", [goal["id"]], "task_id must be a string"),
                ("task_id", 5, "task_id must be a string")):
            with self.subTest(field=field, value=value):
                resp = self.client.post("/v1/goal-tasks", json={**base, field: value})
                self.assertEqual(400, resp.status_code, resp.text)
                self.assertEqual(message, resp.json()["error"])
        # A task_id of the wrong type is refused, not read as "create a new task".
        self.assertEqual(1, self.store.list_tasks()["total"])
        self.assertEqual("orig", self.store.get_task(goal["id"])["title"])


if __name__ == "__main__":
    unittest.main()
