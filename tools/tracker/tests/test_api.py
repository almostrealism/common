"""Tests for the ar-tracker HTTP API handlers."""

import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from starlette.testclient import TestClient

from store import TrackerStore
from api import create_http_app


def _make_client(auth_token=None):
    """Create a TestClient backed by an in-memory database."""
    tmp = tempfile.NamedTemporaryFile(suffix=".db", delete=False)
    tmp.close()
    store = TrackerStore(tmp.name)
    app = create_http_app(store, auth_token=auth_token)
    return TestClient(app), store, tmp.name


class TestHealth(unittest.TestCase):

    def test_health_returns_ok(self):
        client, _, path = _make_client()
        resp = client.get("/api/health")
        self.assertEqual(resp.status_code, 200)
        body = resp.json()
        self.assertTrue(body["ok"])
        self.assertIn("counts", body)
        os.unlink(path)


class TestAuth(unittest.TestCase):

    def test_no_auth_required_when_token_not_set(self):
        client, _, path = _make_client(auth_token=None)
        resp = client.get("/v1/projects")
        self.assertEqual(resp.status_code, 200)
        os.unlink(path)

    def test_auth_required_when_token_set(self):
        client, _, path = _make_client(auth_token="secret")
        resp = client.get("/v1/projects")
        self.assertEqual(resp.status_code, 401)
        os.unlink(path)

    def test_valid_token_grants_access(self):
        client, _, path = _make_client(auth_token="secret")
        resp = client.get("/v1/projects", headers={"Authorization": "Bearer secret"})
        self.assertEqual(resp.status_code, 200)
        os.unlink(path)

    def test_wrong_token_rejected(self):
        client, _, path = _make_client(auth_token="secret")
        resp = client.get("/v1/projects", headers={"Authorization": "Bearer wrong"})
        self.assertEqual(resp.status_code, 401)
        os.unlink(path)


class TestProjects(unittest.TestCase):

    def setUp(self):
        self.client, self.store, self.db_path = _make_client()

    def tearDown(self):
        os.unlink(self.db_path)

    def test_list_empty(self):
        resp = self.client.get("/v1/projects")
        self.assertEqual(resp.status_code, 200)
        self.assertEqual(resp.json()["projects"], [])

    def test_create_and_get(self):
        resp = self.client.post("/v1/projects", json={"name": "Rings"})
        self.assertEqual(resp.status_code, 201)
        body = resp.json()
        self.assertTrue(body["ok"])
        project_id = body["project"]["id"]

        resp2 = self.client.get(f"/v1/projects/{project_id}")
        self.assertEqual(resp2.status_code, 200)
        self.assertEqual(resp2.json()["project"]["name"], "Rings")

    def test_create_requires_name(self):
        resp = self.client.post("/v1/projects", json={})
        self.assertEqual(resp.status_code, 400)

    def test_update_project(self):
        create_resp = self.client.post("/v1/projects", json={"name": "Old"})
        project_id = create_resp.json()["project"]["id"]

        resp = self.client.put(f"/v1/projects/{project_id}", json={"name": "New"})
        self.assertEqual(resp.status_code, 200)
        self.assertEqual(resp.json()["project"]["name"], "New")

    def test_delete_project(self):
        create_resp = self.client.post("/v1/projects", json={"name": "ToDelete"})
        project_id = create_resp.json()["project"]["id"]

        resp = self.client.delete(f"/v1/projects/{project_id}")
        self.assertEqual(resp.status_code, 200)
        self.assertTrue(resp.json()["ok"])

        resp2 = self.client.get(f"/v1/projects/{project_id}")
        self.assertEqual(resp2.status_code, 404)

    def test_get_nonexistent_returns_404(self):
        resp = self.client.get("/v1/projects/does-not-exist")
        self.assertEqual(resp.status_code, 404)


class TestReleases(unittest.TestCase):

    def setUp(self):
        self.client, self.store, self.db_path = _make_client()
        proj_resp = self.client.post("/v1/projects", json={"name": "Rings"})
        self.project_id = proj_resp.json()["project"]["id"]

    def tearDown(self):
        os.unlink(self.db_path)

    def test_create_release_with_project(self):
        resp = self.client.post(
            "/v1/releases",
            json={"name": "0.38", "project_id": self.project_id},
        )
        self.assertEqual(resp.status_code, 201)
        self.assertEqual(resp.json()["release"]["project_id"], self.project_id)

    def test_list_releases_filtered(self):
        self.client.post(
            "/v1/releases",
            json={"name": "0.38", "project_id": self.project_id},
        )
        self.client.post("/v1/releases", json={"name": "Other"})

        resp = self.client.get(f"/v1/releases?project_id={self.project_id}")
        releases = resp.json()["releases"]
        self.assertEqual(len(releases), 1)
        self.assertEqual(releases[0]["name"], "0.38")

    def test_delete_release_nullifies_tasks(self):
        rel_resp = self.client.post(
            "/v1/releases", json={"name": "0.38", "project_id": self.project_id}
        )
        release_id = rel_resp.json()["release"]["id"]
        task_resp = self.client.post(
            "/v1/tasks",
            json={"title": "Fix bug", "release_id": release_id},
        )
        task_id = task_resp.json()["task"]["id"]

        self.client.delete(f"/v1/releases/{release_id}")

        task = self.client.get(f"/v1/tasks/{task_id}").json()["task"]
        self.assertIsNone(task["release_id"])


class TestTasks(unittest.TestCase):

    def setUp(self):
        self.client, self.store, self.db_path = _make_client()
        proj_resp = self.client.post("/v1/projects", json={"name": "Rings"})
        self.project_id = proj_resp.json()["project"]["id"]

    def tearDown(self):
        os.unlink(self.db_path)

    def test_create_and_get_task(self):
        resp = self.client.post(
            "/v1/tasks",
            json={"title": "Add OAuth", "project_id": self.project_id},
        )
        self.assertEqual(resp.status_code, 201)
        task_id = resp.json()["task"]["id"]

        resp2 = self.client.get(f"/v1/tasks/{task_id}")
        self.assertEqual(resp2.status_code, 200)
        self.assertEqual(resp2.json()["task"]["title"], "Add OAuth")
        self.assertEqual(resp2.json()["task"]["status"], "open")

    def test_create_requires_title(self):
        resp = self.client.post("/v1/tasks", json={})
        self.assertEqual(resp.status_code, 400)

    def test_invalid_status_rejected(self):
        resp = self.client.post(
            "/v1/tasks", json={"title": "t", "status": "invalid"}
        )
        self.assertEqual(resp.status_code, 400)

    def test_list_tasks_filtered_by_status(self):
        self.client.post("/v1/tasks", json={"title": "Open task", "status": "open"})
        self.client.post("/v1/tasks", json={"title": "Closed task", "status": "closed"})

        resp = self.client.get("/v1/tasks?status=open")
        tasks = resp.json()["tasks"]
        self.assertEqual(len(tasks), 1)
        self.assertEqual(tasks[0]["title"], "Open task")

    def test_list_pagination(self):
        for i in range(5):
            self.client.post("/v1/tasks", json={"title": f"Task {i}"})

        resp = self.client.get("/v1/tasks?limit=2&offset=0")
        body = resp.json()
        self.assertEqual(len(body["tasks"]), 2)
        self.assertEqual(body["total"], 5)

    def test_update_task_title(self):
        create_resp = self.client.post("/v1/tasks", json={"title": "Old title"})
        task_id = create_resp.json()["task"]["id"]

        resp = self.client.put(f"/v1/tasks/{task_id}", json={"title": "New title"})
        self.assertEqual(resp.status_code, 200)
        self.assertEqual(resp.json()["task"]["title"], "New title")

    def test_update_task_close(self):
        create_resp = self.client.post("/v1/tasks", json={"title": "Task"})
        task_id = create_resp.json()["task"]["id"]

        resp = self.client.put(f"/v1/tasks/{task_id}", json={"status": "closed"})
        self.assertEqual(resp.status_code, 200)
        self.assertEqual(resp.json()["task"]["status"], "closed")

    def test_update_task_clear_release(self):
        rel_resp = self.client.post("/v1/releases", json={"name": "0.38"})
        release_id = rel_resp.json()["release"]["id"]
        create_resp = self.client.post(
            "/v1/tasks", json={"title": "Task", "release_id": release_id}
        )
        task_id = create_resp.json()["task"]["id"]

        resp = self.client.put(f"/v1/tasks/{task_id}", json={"release_id": None})
        self.assertEqual(resp.status_code, 200)
        self.assertIsNone(resp.json()["task"]["release_id"])

    def test_delete_task(self):
        create_resp = self.client.post("/v1/tasks", json={"title": "To delete"})
        task_id = create_resp.json()["task"]["id"]

        self.client.delete(f"/v1/tasks/{task_id}")
        resp = self.client.get(f"/v1/tasks/{task_id}")
        self.assertEqual(resp.status_code, 404)

    def test_project_tasks_sub_resource(self):
        self.client.post(
            "/v1/tasks",
            json={"title": "In project", "project_id": self.project_id},
        )
        self.client.post("/v1/tasks", json={"title": "No project"})

        resp = self.client.get(f"/v1/projects/{self.project_id}/tasks")
        tasks = resp.json()["tasks"]
        self.assertEqual(len(tasks), 1)
        self.assertEqual(tasks[0]["title"], "In project")


class TestSearch(unittest.TestCase):

    def setUp(self):
        self.client, self.store, self.db_path = _make_client()
        self.client.post(
            "/v1/tasks",
            json={"title": "Add OAuth support", "description": "Implement OAuth 2.0"},
        )
        self.client.post(
            "/v1/tasks",
            json={"title": "Fix login bug", "description": "The login page crashes"},
        )

    def tearDown(self):
        os.unlink(self.db_path)

    def test_search_by_title(self):
        resp = self.client.get("/v1/search/tasks?q=OAuth")
        self.assertEqual(resp.status_code, 200)
        tasks = resp.json()["tasks"]
        self.assertEqual(len(tasks), 1)
        self.assertEqual(tasks[0]["title"], "Add OAuth support")

    def test_search_requires_q(self):
        resp = self.client.get("/v1/search/tasks")
        self.assertEqual(resp.status_code, 400)


class TestBulkImport(unittest.TestCase):

    def setUp(self):
        self.client, self.store, self.db_path = _make_client()

    def tearDown(self):
        os.unlink(self.db_path)

    def test_bulk_import_creates_records(self):
        import uuid
        project_id = str(uuid.uuid4())
        task_id = str(uuid.uuid4())
        resp = self.client.post(
            "/v1/import",
            json={
                "projects": [{"id": project_id, "name": "Imported Project"}],
                "releases": [],
                "tasks": [
                    {
                        "id": task_id,
                        "title": "Imported task",
                        "project_id": project_id,
                        "status": "closed",
                    }
                ],
            },
        )
        self.assertEqual(resp.status_code, 200)
        body = resp.json()
        self.assertTrue(body["ok"])
        self.assertEqual(body["created"]["projects"], 1)
        self.assertEqual(body["created"]["tasks"], 1)

    def test_bulk_import_is_idempotent(self):
        import uuid
        project_id = str(uuid.uuid4())
        payload = {
            "projects": [{"id": project_id, "name": "Idempotent"}],
            "releases": [],
            "tasks": [],
        }
        self.client.post("/v1/import", json=payload)
        resp = self.client.post("/v1/import", json=payload)
        body = resp.json()
        self.assertEqual(body["created"]["projects"], 0)
        self.assertEqual(body["updated"]["projects"], 1)


if __name__ == "__main__":
    unittest.main()
