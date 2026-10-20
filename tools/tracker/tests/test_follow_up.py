"""Tests for agent follow-up tasks.

A coding agent files a follow-up task for a valid review point that is out of
scope for its branch. The server, not the caller, holds every limit: the task
is always in the backlog, never linked to a workstream, filed under the job's
own provenance source, and capped per job. These tests exercise those limits
at the store and through the HTTP API.
"""

import os
import sqlite3
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from starlette.testclient import TestClient

import migrate
from store import TrackerStore, FOLLOW_UP_SOURCE_PREFIX, MAX_FOLLOW_UP_TASKS_PER_JOB
from api import create_http_app


class _FollowUpTestBase(unittest.TestCase):
    """A fresh store with one project and release, and an API client."""

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

    def _source(self, job="job-1", workstream="ws-1"):
        return f"{FOLLOW_UP_SOURCE_PREFIX}{workstream}/{job}"


class FollowUpStoreTests(_FollowUpTestBase):
    """The store forces the follow-up invariants whatever the caller asks."""

    def test_a_follow_up_task_is_always_backlog_open_and_unlinked(self):
        result = self.store.file_follow_up_task(
            title="cover the empty-input edge case",
            source=self._source(),
            project_id=self.project["id"])
        self.assertEqual("ok", result["status"])
        task = result["task"]
        self.assertEqual("backlog", task["stage"])
        self.assertEqual("open", task["status"])
        self.assertIsNone(task["workstream_id"])
        self.assertEqual(0, task["priority"])
        self.assertEqual(self._source(), task["source"])

    def test_the_release_is_recorded_when_given(self):
        result = self.store.file_follow_up_task(
            title="t", source=self._source(), project_id=self.project["id"],
            release_id=self.release["id"])
        self.assertEqual(self.release["id"], result["task"]["release_id"])

    def test_an_unknown_project_is_refused_and_nothing_is_written(self):
        result = self.store.file_follow_up_task(
            title="t", source=self._source(), project_id="no-such-project")
        self.assertEqual("project_not_found", result["status"])
        self.assertEqual(0, self.store.list_tasks()["total"])

    def test_a_job_may_not_file_more_than_the_cap(self):
        source = self._source()
        for i in range(MAX_FOLLOW_UP_TASKS_PER_JOB):
            self.assertEqual("ok", self.store.file_follow_up_task(
                title=f"t{i}", source=source, project_id=self.project["id"])["status"])
        capped = self.store.file_follow_up_task(
            title="one too many", source=source, project_id=self.project["id"])
        self.assertEqual("cap_reached", capped["status"])
        self.assertEqual(MAX_FOLLOW_UP_TASKS_PER_JOB, capped["count"])
        self.assertEqual(MAX_FOLLOW_UP_TASKS_PER_JOB, capped["cap"])
        self.assertEqual(MAX_FOLLOW_UP_TASKS_PER_JOB, self.store.list_tasks()["total"])

    def test_the_cap_is_per_job_not_global(self):
        for i in range(MAX_FOLLOW_UP_TASKS_PER_JOB):
            self.store.file_follow_up_task(
                title=f"t{i}", source=self._source(job="job-1"),
                project_id=self.project["id"])
        # A different job has its own count, so it may still file.
        other = self.store.file_follow_up_task(
            title="other job", source=self._source(job="job-2"),
            project_id=self.project["id"])
        self.assertEqual("ok", other["status"])

    def test_an_unknown_release_is_a_db_error_and_rolls_back(self):
        result = self.store.file_follow_up_task(
            title="t", source=self._source(), project_id=self.project["id"],
            release_id="no-such-release")
        self.assertEqual("db_error", result["status"])
        self.assertEqual(0, self.store.list_tasks()["total"])

    def test_the_database_refuses_a_non_follow_up_source_here(self):
        # The store method is the follow-up path; a caller that smuggles a
        # person/goal source still only reaches a follow-up create, but the
        # column CHECK confines provenance regardless.
        result = self.store.file_follow_up_task(
            title="t", source="automation", project_id=self.project["id"])
        self.assertEqual("db_error", result["status"])


class FollowUpSourceConstraintTests(_FollowUpTestBase):
    """The widened source CHECK admits follow-up provenance, nothing looser."""

    def test_a_follow_up_source_is_accepted_by_the_database(self):
        task = self.store.create_task(
            title="t", source=self._source(), release_id=self.release["id"])
        self.assertEqual(self._source(), task["source"])

    def test_a_bare_follow_up_prefix_is_refused_by_the_database(self):
        for source in ("follow-up:", "follow-up: ", "follow-up:\t"):
            with self.assertRaises(sqlite3.IntegrityError, msg=source):
                self.store.create_task(
                    title="t", source=source, release_id=self.release["id"])

    def test_the_follow_up_prefix_is_case_sensitive_in_the_database(self):
        with self.assertRaises(sqlite3.IntegrityError):
            self.store.create_task(
                title="t", source="FOLLOW-UP:ws/job", release_id=self.release["id"])

    def test_existing_task_blockers_survive_the_v4_rebuild(self):
        # v4 rebuilds the tasks table. Foreign keys are disabled around it so
        # the rebuild does not cascade-delete the blocking relationships.
        tmp = tempfile.NamedTemporaryFile(suffix=".db", delete=False)
        tmp.close()
        try:
            conn = sqlite3.connect(tmp.name)
            conn.executescript(migrate._SCHEMA_V1)
            conn.execute("INSERT INTO schema_version VALUES (1)")
            conn.executescript(migrate._SCHEMA_V2)
            conn.execute("UPDATE schema_version SET version = 2")
            conn.executescript(migrate._SCHEMA_V3)
            conn.execute("UPDATE schema_version SET version = 3")
            for tid in ("a", "b"):
                conn.execute(
                    "INSERT INTO tasks (id, title, status, created_at, updated_at) "
                    "VALUES (?, ?, 'open', 'x', 'x')", (tid, tid))
            conn.execute(
                "INSERT INTO task_blockers (task_id, blocker_id) VALUES ('b', 'a')")
            conn.commit()
            conn.close()

            store = TrackerStore(tmp.name)
            try:
                self.assertGreaterEqual(migrate._get_version(store._conn), 4)
                self.assertEqual(["a"], store.get_task("b")["blocked_by"])
                # The widened CHECK is in force on the rebuilt table.
                store.create_task(title="f", source=f"{FOLLOW_UP_SOURCE_PREFIX}ws/job")
                with self.assertRaises(sqlite3.IntegrityError):
                    store.create_task(title="bad", source="automation")
            finally:
                store.close()
        finally:
            os.unlink(tmp.name)

    def test_full_text_search_still_finds_rows_after_the_v4_rebuild(self):
        # Rowids are preserved across the rebuild, so the external-content FTS
        # index keeps matching the rows it indexed before the migration.
        self.store.create_task(
            title="searchable follow up marker", release_id=self.release["id"])
        found = self.store.search_tasks("searchable")
        self.assertEqual(1, found["total"])


class FollowUpApiTests(_FollowUpTestBase):
    """The HTTP endpoint maps the store outcomes to status codes."""

    def _post(self, **body):
        return self.client.post("/v1/follow-up-tasks", json=body)

    def test_a_follow_up_task_is_filed_into_the_backlog(self):
        resp = self._post(project_id=self.project["id"], title="t", source=self._source())
        self.assertEqual(201, resp.status_code, resp.text)
        task = resp.json()["task"]
        self.assertEqual("backlog", task["stage"])
        self.assertIsNone(task["workstream_id"])

    def test_project_id_and_title_and_source_are_required(self):
        self.assertEqual(400, self._post(title="t", source=self._source()).status_code)
        self.assertEqual(400, self._post(
            project_id=self.project["id"], source=self._source()).status_code)
        self.assertEqual(400, self._post(
            project_id=self.project["id"], title="t").status_code)

    def test_a_non_follow_up_source_is_refused_with_400(self):
        for source in ("person", "goals:docs/GOALS.md", "follow-up:", "automation"):
            resp = self._post(project_id=self.project["id"], title="t", source=source)
            self.assertEqual(400, resp.status_code, source)

    def test_an_unknown_project_is_404(self):
        resp = self._post(project_id="no-such", title="t", source=self._source())
        self.assertEqual(404, resp.status_code)

    def test_the_per_job_cap_is_429(self):
        for i in range(MAX_FOLLOW_UP_TASKS_PER_JOB):
            self._post(project_id=self.project["id"], title=f"t{i}", source=self._source())
        resp = self._post(project_id=self.project["id"], title="x", source=self._source())
        self.assertEqual(429, resp.status_code)
        self.assertEqual(MAX_FOLLOW_UP_TASKS_PER_JOB, resp.json()["cap"])

    def test_a_non_string_description_is_refused_with_400(self):
        resp = self._post(project_id=self.project["id"], title="t",
                          source=self._source(), description=["not", "a", "string"])
        self.assertEqual(400, resp.status_code)

    def test_a_non_string_release_id_is_refused_with_400(self):
        resp = self._post(project_id=self.project["id"], title="t",
                          source=self._source(), release_id=7)
        self.assertEqual(400, resp.status_code)

    def test_caller_supplied_stage_priority_and_workstream_are_ignored(self):
        resp = self._post(project_id=self.project["id"], title="t", source=self._source(),
                          stage="ready", priority=2, workstream_id="ws-1", status="closed")
        self.assertEqual(201, resp.status_code, resp.text)
        task = resp.json()["task"]
        self.assertEqual("backlog", task["stage"])
        self.assertEqual(0, task["priority"])
        self.assertEqual("open", task["status"])
        self.assertIsNone(task["workstream_id"])

    def test_an_unknown_release_is_400(self):
        resp = self._post(project_id=self.project["id"], title="t",
                          source=self._source(), release_id="no-such-release")
        self.assertEqual(400, resp.status_code)
        self.assertEqual([], self.store.list_tasks(project_id=self.project["id"])["tasks"])


class FollowUpSourceOnGenericEndpointsTests(_FollowUpTestBase):
    """The generic task endpoints accept a follow-up provenance so a filed task
    survives an export/import round trip, and still refuse a bare prefix."""

    def test_a_task_with_a_follow_up_source_is_accepted_by_import(self):
        resp = self.client.post("/v1/import", json={"tasks": [
            {"id": "imported-1", "title": "t", "source": self._source(),
             "project_id": self.project["id"]}]})
        self.assertEqual(200, resp.status_code, resp.text)
        self.assertEqual(self._source(), self.store.get_task("imported-1")["source"])

    def test_a_bare_follow_up_prefix_is_refused_by_import(self):
        resp = self.client.post("/v1/import", json={"tasks": [
            {"id": "imported-1", "title": "t", "source": FOLLOW_UP_SOURCE_PREFIX}]})
        self.assertEqual(400, resp.status_code)
        self.assertIsNone(self.store.get_task("imported-1"))

    def test_a_follow_up_source_is_accepted_by_task_create(self):
        resp = self.client.post("/v1/tasks", json={"title": "t", "source": self._source()})
        self.assertEqual(201, resp.status_code, resp.text)
        self.assertEqual(self._source(), resp.json()["task"]["source"])


if __name__ == "__main__":
    unittest.main()
