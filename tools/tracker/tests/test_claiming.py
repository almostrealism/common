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

    def test_the_api_refuses_an_uppercase_goals_prefix(self):
        resp = self.client.post("/v1/tasks", json={
            "title": "t", "release_id": self.release["id"], "source": "GOALS:x"})
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


if __name__ == "__main__":
    unittest.main()
