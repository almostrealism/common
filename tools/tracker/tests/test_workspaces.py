"""Tests for project workspace ownership and workspace-limited reads.

A project is owned by at most one workspace, and its releases and tasks belong
to that workspace. A read limited to some workspaces returns only what they
own: never a task with no project, never a project no workspace owns, and
nothing at all when the limit names no workspace.
"""

import os
import sqlite3
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from starlette.testclient import TestClient

import migrate
from store import TrackerStore
from api import create_http_app


class _WorkspaceTestBase(unittest.TestCase):
    """Two projects owned by different workspaces, and one owned by none."""

    def setUp(self):
        tmp = tempfile.NamedTemporaryFile(suffix=".db", delete=False)
        tmp.close()
        self.path = tmp.name
        self.store = TrackerStore(self.path)
        self.ours = self.store.create_project("Framework", "W-OURS")
        self.theirs = self.store.create_project("Elsewhere", "W-THEIRS")
        self.unowned = self.store.create_project("Orphan")
        self.client = TestClient(create_http_app(self.store))

    def tearDown(self):
        self.store.close()
        os.unlink(self.path)

    def _task(self, title, project=None, **kwargs):
        return self.store.create_task(
            title=title, project_id=project["id"] if project else None, **kwargs)

    def _titles(self, result):
        return sorted(t["title"] for t in result["tasks"])


class ProjectOwnershipStoreTests(_WorkspaceTestBase):

    def test_a_project_records_its_workspace(self):
        self.assertEqual("W-OURS", self.store.get_project(self.ours["id"])["workspace_id"])
        self.assertIsNone(self.store.get_project(self.unowned["id"])["workspace_id"])

    def test_create_project_returns_the_workspace(self):
        self.assertEqual("W-OURS", self.ours["workspace_id"])

    def test_update_project_assigns_and_clears_the_workspace(self):
        self.store.update_project(self.unowned["id"], workspace_id="W-OURS")
        self.assertEqual("W-OURS", self.store.get_project(self.unowned["id"])["workspace_id"])
        self.store.update_project(self.unowned["id"], workspace_id=None)
        self.assertIsNone(self.store.get_project(self.unowned["id"])["workspace_id"])

    def test_update_project_leaves_an_unsupplied_field_alone(self):
        self.store.update_project(self.ours["id"], name="Renamed")
        project = self.store.get_project(self.ours["id"])
        self.assertEqual(("Renamed", "W-OURS"), (project["name"], project["workspace_id"]))

    def test_list_projects_is_limited_to_the_named_workspaces(self):
        names = [p["name"] for p in self.store.list_projects(workspace_ids=["W-OURS"])]
        self.assertEqual(["Framework"], names)
        self.assertEqual(3, len(self.store.list_projects()))

    def test_a_limit_naming_no_workspace_lists_nothing(self):
        self.assertEqual([], self.store.list_projects(workspace_ids=[]))

    def test_releases_carry_and_are_limited_by_their_project_workspace(self):
        ours = self.store.create_release("Framework 1.0", self.ours["id"])
        self.store.create_release("Elsewhere 1.0", self.theirs["id"])
        self.store.create_release("Loose 1.0")
        self.assertEqual("W-OURS", ours["workspace_id"])
        names = [r["name"] for r in self.store.list_releases(workspace_ids=["W-OURS"])]
        self.assertEqual(["Framework 1.0"], names)
        self.assertEqual(3, len(self.store.list_releases()))


class TaskWorkspaceStoreTests(_WorkspaceTestBase):

    def setUp(self):
        super().setUp()
        self._task("ours", self.ours)
        self._task("theirs", self.theirs)
        self._task("orphan", self.unowned)
        self._task("loose")

    def test_a_task_carries_its_project_workspace(self):
        tasks = {t["title"]: t for t in self.store.list_tasks()["tasks"]}
        self.assertEqual("W-OURS", tasks["ours"]["workspace_id"])
        self.assertIsNone(tasks["orphan"]["workspace_id"])
        self.assertIsNone(tasks["loose"]["workspace_id"])

    def test_a_headline_task_carries_its_workspace_too(self):
        tasks = self.store.list_tasks(headlines_only=True, workspace_ids=["W-THEIRS"])["tasks"]
        self.assertEqual(["W-THEIRS"], [t["workspace_id"] for t in tasks])

    def test_list_tasks_is_limited_to_the_named_workspaces(self):
        result = self.store.list_tasks(workspace_ids=["W-OURS"])
        self.assertEqual((["ours"], 1), (self._titles(result), result["total"]))

    def test_several_workspaces_may_be_named(self):
        result = self.store.list_tasks(workspace_ids=["W-OURS", "W-THEIRS"])
        self.assertEqual(["ours", "theirs"], self._titles(result))

    def test_a_task_with_no_project_or_an_unowned_project_is_never_in_a_workspace(self):
        result = self.store.list_tasks(workspace_ids=["W-OURS", "W-THEIRS"])
        self.assertNotIn("orphan", self._titles(result))
        self.assertNotIn("loose", self._titles(result))

    def test_a_limit_naming_no_workspace_lists_no_task(self):
        result = self.store.list_tasks(workspace_ids=[])
        self.assertEqual((0, []), (result["total"], result["tasks"]))

    def test_search_is_limited_to_the_named_workspaces(self):
        result = self.store.search_tasks("ours OR theirs", workspace_ids=["W-THEIRS"])
        self.assertEqual((["theirs"], 1), (self._titles(result), result["total"]))

    def test_moving_a_project_moves_its_tasks(self):
        self.store.update_project(self.unowned["id"], workspace_id="W-OURS")
        result = self.store.list_tasks(workspace_ids=["W-OURS"])
        self.assertEqual(["orphan", "ours"], self._titles(result))


class WorkspaceApiTests(_WorkspaceTestBase):

    def setUp(self):
        super().setUp()
        self._task("ours", self.ours)
        self._task("theirs", self.theirs)

    def test_list_tasks_accepts_a_comma_separated_workspace_limit(self):
        resp = self.client.get("/v1/tasks?workspace_id=W-OURS,%20W-THEIRS")
        self.assertEqual(200, resp.status_code, resp.text)
        self.assertEqual(["ours", "theirs"], self._titles(resp.json()))

    def test_an_empty_workspace_limit_lists_nothing(self):
        resp = self.client.get("/v1/tasks?workspace_id=")
        self.assertEqual((0, []), (resp.json()["total"], resp.json()["tasks"]))

    def test_search_accepts_a_workspace_limit(self):
        resp = self.client.get("/v1/search/tasks?q=ours%20OR%20theirs&workspace_id=W-OURS")
        self.assertEqual(["ours"], self._titles(resp.json()))

    def test_projects_and_releases_accept_a_workspace_limit(self):
        self.store.create_release("Elsewhere 1.0", self.theirs["id"])
        projects = self.client.get("/v1/projects?workspace_id=W-THEIRS").json()["projects"]
        releases = self.client.get("/v1/releases?workspace_id=W-THEIRS").json()["releases"]
        self.assertEqual(["Elsewhere"], [p["name"] for p in projects])
        self.assertEqual(["Elsewhere 1.0"], [r["name"] for r in releases])

    def test_create_project_takes_a_workspace(self):
        resp = self.client.post("/v1/projects", json={"name": "New", "workspace_id": "W-OURS"})
        self.assertEqual(201, resp.status_code, resp.text)
        self.assertEqual("W-OURS", resp.json()["project"]["workspace_id"])

    def test_update_project_assigns_a_workspace_without_a_name(self):
        resp = self.client.put(f"/v1/projects/{self.unowned['id']}",
                               json={"workspace_id": "W-OURS"})
        self.assertEqual(200, resp.status_code, resp.text)
        self.assertEqual(("Orphan", "W-OURS"),
                         (resp.json()["project"]["name"], resp.json()["project"]["workspace_id"]))

    def test_update_project_clears_a_workspace_with_null(self):
        resp = self.client.put(f"/v1/projects/{self.ours['id']}", json={"workspace_id": None})
        self.assertEqual(200, resp.status_code, resp.text)
        self.assertIsNone(self.store.get_project(self.ours["id"])["workspace_id"])

    def test_a_non_string_workspace_is_refused(self):
        for method, path in (("post", "/v1/projects"),
                             ("put", f"/v1/projects/{self.ours['id']}")):
            with self.subTest(path=path):
                resp = getattr(self.client, method)(
                    path, json={"name": "X", "workspace_id": ["W-OURS"]})
                self.assertEqual(400, resp.status_code, resp.text)
                self.assertEqual("workspace_id must be a string or null", resp.json()["error"])
        self.assertEqual("W-OURS", self.store.get_project(self.ours["id"])["workspace_id"])

    def test_bulk_import_keeps_an_owner_the_record_does_not_name(self):
        resp = self.client.post("/v1/import", json={"projects": [
            {"id": self.ours["id"], "name": "Renamed"},
            {"id": "p-new", "name": "Imported", "workspace_id": "W-THEIRS"}]})
        self.assertEqual(200, resp.status_code, resp.text)
        self.assertEqual("W-OURS", self.store.get_project(self.ours["id"])["workspace_id"])
        self.assertEqual("W-THEIRS", self.store.get_project("p-new")["workspace_id"])


class WorkspaceMigrationTests(unittest.TestCase):

    def test_a_v4_database_gains_an_unowned_workspace_on_each_project(self):
        tmp = tempfile.NamedTemporaryFile(suffix=".db", delete=False)
        tmp.close()
        try:
            conn = sqlite3.connect(tmp.name)
            migrate.run_migrations(conn)
            conn.execute("DROP INDEX idx_projects_workspace")
            conn.execute("ALTER TABLE projects DROP COLUMN workspace_id")
            conn.execute("UPDATE schema_version SET version = 4")
            conn.execute("INSERT INTO projects (id, name, created_at) "
                         "VALUES ('p1', 'Old', '2026-01-01T00:00:00Z')")
            conn.commit()
            conn.close()

            store = TrackerStore(tmp.name)
            try:
                self.assertEqual(5, migrate._get_version(store._conn))
                self.assertIsNone(store.get_project("p1")["workspace_id"])
            finally:
                store.close()
        finally:
            os.unlink(tmp.name)


if __name__ == "__main__":
    unittest.main()
