"""Tests for the TrackerStore data access layer."""

import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from store import TrackerStore


def _make_store():
    tmp = tempfile.NamedTemporaryFile(suffix=".db", delete=False)
    tmp.close()
    return TrackerStore(tmp.name), tmp.name


class TestProjects(unittest.TestCase):

    def setUp(self):
        self.store, self.db_path = _make_store()

    def tearDown(self):
        os.unlink(self.db_path)

    def test_create_and_get(self):
        p = self.store.create_project("Rings")
        self.assertIsNotNone(p["id"])
        self.assertEqual(p["name"], "Rings")
        fetched = self.store.get_project(p["id"])
        self.assertEqual(fetched["name"], "Rings")

    def test_list_empty(self):
        self.assertEqual(self.store.list_projects(), [])

    def test_list_multiple(self):
        self.store.create_project("Rings")
        self.store.create_project("Flowtree")
        projects = self.store.list_projects()
        self.assertEqual(len(projects), 2)
        names = [p["name"] for p in projects]
        self.assertIn("Rings", names)

    def test_update(self):
        p = self.store.create_project("Old")
        updated = self.store.update_project(p["id"], "New")
        self.assertEqual(updated["name"], "New")

    def test_delete(self):
        p = self.store.create_project("ToDelete")
        result = self.store.delete_project(p["id"])
        self.assertTrue(result)
        self.assertIsNone(self.store.get_project(p["id"]))

    def test_delete_nonexistent_returns_false(self):
        self.assertFalse(self.store.delete_project("no-such-id"))


class TestReleases(unittest.TestCase):

    def setUp(self):
        self.store, self.db_path = _make_store()
        self.project = self.store.create_project("Rings")

    def tearDown(self):
        os.unlink(self.db_path)

    def test_create_with_project(self):
        r = self.store.create_release("0.38", self.project["id"])
        self.assertEqual(r["name"], "0.38")
        self.assertEqual(r["project_id"], self.project["id"])

    def test_create_without_project(self):
        r = self.store.create_release("Standalone")
        self.assertIsNone(r["project_id"])

    def test_list_filtered_by_project(self):
        self.store.create_release("0.38", self.project["id"])
        self.store.create_release("Other")
        releases = self.store.list_releases(self.project["id"])
        self.assertEqual(len(releases), 1)
        self.assertEqual(releases[0]["name"], "0.38")

    def test_update_name(self):
        r = self.store.create_release("Old", self.project["id"])
        updated = self.store.update_release(r["id"], name="New")
        self.assertEqual(updated["name"], "New")


class TestTasks(unittest.TestCase):

    def setUp(self):
        self.store, self.db_path = _make_store()
        self.project = self.store.create_project("Rings")

    def tearDown(self):
        os.unlink(self.db_path)

    def test_create_and_get(self):
        t = self.store.create_task("Add OAuth")
        fetched = self.store.get_task(t["id"])
        self.assertEqual(fetched["title"], "Add OAuth")
        self.assertEqual(fetched["status"], "open")
        self.assertEqual(fetched["priority"], 0)

    def test_create_with_all_fields(self):
        release = self.store.create_release("0.38", self.project["id"])
        t = self.store.create_task(
            title="Full task",
            description="Some description",
            status="closed",
            priority=2,
            project_id=self.project["id"],
            release_id=release["id"],
            workstream_id="ws-abc",
        )
        self.assertEqual(t["description"], "Some description")
        self.assertEqual(t["status"], "closed")
        self.assertEqual(t["priority"], 2)
        self.assertEqual(t["project_id"], self.project["id"])
        self.assertEqual(t["workstream_id"], "ws-abc")

    def test_priority_default_is_zero(self):
        t = self.store.create_task("No priority specified")
        self.assertEqual(t["priority"], 0)

    def test_update_priority(self):
        t = self.store.create_task("Task")
        updated = self.store.update_task(t["id"], priority=-2)
        self.assertEqual(updated["priority"], -2)

    def test_priority_check_constraint(self):
        import sqlite3
        with self.assertRaises(sqlite3.IntegrityError):
            self.store.create_task("Bad priority", priority=99)

    def test_list_sorted_by_priority(self):
        self.store.create_task("Lowest", priority=-2)
        self.store.create_task("Highest", priority=2)
        self.store.create_task("Medium", priority=0)
        result = self.store.list_tasks(sort="priority", order="desc")
        titles = [t["title"] for t in result["tasks"]]
        self.assertEqual(titles, ["Highest", "Medium", "Lowest"])

    def test_list_filter_by_status(self):
        self.store.create_task("Open task", status="open")
        self.store.create_task("Closed task", status="closed")
        result = self.store.list_tasks(status="open")
        self.assertEqual(result["total"], 1)
        self.assertEqual(result["tasks"][0]["title"], "Open task")

    def test_list_pagination(self):
        for i in range(5):
            self.store.create_task(f"Task {i}")
        result = self.store.list_tasks(limit=2, offset=0)
        self.assertEqual(len(result["tasks"]), 2)
        self.assertEqual(result["total"], 5)

    def test_update_status(self):
        t = self.store.create_task("Task")
        updated = self.store.update_task(t["id"], status="closed")
        self.assertEqual(updated["status"], "closed")

    def test_update_clear_project(self):
        t = self.store.create_task("Task", project_id=self.project["id"])
        updated = self.store.update_task(t["id"], project_id=None)
        # Passing None clears the optional FK field; UNSET leaves it unchanged.
        self.assertIsNone(updated["project_id"])

    def test_delete(self):
        t = self.store.create_task("To delete")
        self.store.delete_task(t["id"])
        self.assertIsNone(self.store.get_task(t["id"]))

    def test_search(self):
        self.store.create_task("OAuth support", description="Implement OAuth 2.0 flow")
        self.store.create_task("Login fix", description="Fix broken login page")
        result = self.store.search_tasks("OAuth")
        self.assertEqual(result["total"], 1)
        self.assertEqual(result["tasks"][0]["title"], "OAuth support")

    def test_counts(self):
        self.store.create_project("Extra")
        self.store.create_task("T1")
        counts = self.store.counts()
        self.assertEqual(counts["projects"], 2)
        self.assertEqual(counts["tasks"], 1)


class TestBulkImport(unittest.TestCase):

    def setUp(self):
        self.store, self.db_path = _make_store()

    def tearDown(self):
        os.unlink(self.db_path)

    def test_import_and_idempotency(self):
        import uuid
        project_id = str(uuid.uuid4())
        task_id = str(uuid.uuid4())
        payload = {
            "projects": [{"id": project_id, "name": "Imported"}],
            "releases": [],
            "tasks": [{"id": task_id, "title": "T1", "project_id": project_id}],
        }
        result1 = self.store.bulk_import(**payload)
        self.assertEqual(result1["created"]["projects"], 1)
        self.assertEqual(result1["created"]["tasks"], 1)

        result2 = self.store.bulk_import(**payload)
        self.assertEqual(result2["created"]["projects"], 0)
        self.assertEqual(result2["updated"]["projects"], 1)

    def test_import_carries_priority(self):
        import uuid
        task_id = str(uuid.uuid4())
        result = self.store.bulk_import(
            projects=[],
            releases=[],
            tasks=[{"id": task_id, "title": "Important", "priority": 2}],
        )
        self.assertEqual(result["created"]["tasks"], 1)
        self.assertEqual(self.store.get_task(task_id)["priority"], 2)

    def test_import_rejects_priority_out_of_range(self):
        import uuid
        result = self.store.bulk_import(
            projects=[],
            releases=[],
            tasks=[{"id": str(uuid.uuid4()), "title": "Bad", "priority": 5}],
        )
        self.assertIn("error", result)


if __name__ == "__main__":
    unittest.main()
