# ar-tracker

Lightweight self-hosted ticket tracking service. Replaces Jira for internal work
organization. Projects, releases, and tasks — nothing more.

The service is a Python/Starlette REST API backed by SQLite, deployed alongside
`ar-memory` and `ar-manager` in the FlowTree controller stack.

---

## Quick Start

### Start via docker-compose

```bash
cd flowtree/controller
docker-compose up --build ar-tracker
```

All data is persisted to `/Users/Shared/flowtree/tracker/tracker.db` on the host.

### Start for local development

```bash
cd tools/tracker
pip install -r requirements.txt
AR_TRACKER_DATA_DIR=/tmp/tracker-dev python server.py
# API is now at http://localhost:8030
```

---

## Configuration

| Environment Variable    | Default              | Description                                    |
|-------------------------|----------------------|------------------------------------------------|
| `AR_TRACKER_DATA_DIR`   | `/data`              | Directory where `tracker.db` is created        |
| `AR_TRACKER_PORT`       | `8030`               | Port the service listens on                    |
| `AR_TRACKER_AUTH_TOKEN` | *(unset = open)*     | Bearer token for authentication                |

Set `AR_TRACKER_AUTH_TOKEN` to the same value in both the `ar-tracker` and
`ar-manager` service environments. When unset, the API is open (development mode).

---

## Pointing ar-manager at ar-tracker

Add to the `ar-manager` service in `docker-compose.yml`:

```yaml
environment:
  - AR_TRACKER_URL=http://ar-tracker:8030
  - AR_TRACKER_AUTH_TOKEN=${AR_TRACKER_AUTH_TOKEN:-}
```

For local development where ar-manager runs outside Docker:

```bash
export AR_TRACKER_URL=http://localhost:8030
export AR_TRACKER_AUTH_TOKEN=<your-token>
```

---

## MCP Tools

ar-manager exposes 14 MCP tools for the tracker. All follow the `tracker_*` naming
convention and mirror the REST API:

| Tool | Scope | Description |
|------|-------|-------------|
| `tracker_list_projects` | read | List all projects |
| `tracker_create_project` | write | Create a project |
| `tracker_update_project` | write | Rename a project |
| `tracker_delete_project` | write | Delete a project (tasks are kept, FK set to NULL) |
| `tracker_list_releases` | read | List releases (optional `project_id` filter) |
| `tracker_create_release` | write | Create a release |
| `tracker_update_release` | write | Update a release |
| `tracker_delete_release` | write | Delete a release (tasks kept, FK set to NULL) |
| `tracker_create_task` | write | Create a task |
| `tracker_get_task` | read | Fetch a single task |
| `tracker_list_tasks` | read | List tasks with filtering and pagination |
| `tracker_update_task` | write | Update a task's fields |
| `tracker_delete_task` | write | Permanently delete a task |
| `tracker_search_tasks` | read | Full-text search over titles and descriptions |

### Workspace scoping

Tasks that have a `workstream_id` are subject to workspace scope enforcement.
Scoped tokens may only create, update, or delete tasks attached to workstreams
within their scope. Read operations (`tracker_list_tasks` with a `workstream_id`
filter) are also scope-checked.

Projects and releases are globally visible — no scope restriction.

---

## REST API

The full API is versioned under `/v1/`. Health check is at `/api/health`.

```
GET  /api/health

GET    /v1/projects                     List all projects
POST   /v1/projects                     Create a project
GET    /v1/projects/{id}                Get a project
PUT    /v1/projects/{id}                Update a project
DELETE /v1/projects/{id}                Delete a project

GET    /v1/releases                     List releases (?project_id=)
POST   /v1/releases                     Create a release
GET    /v1/releases/{id}                Get a release
PUT    /v1/releases/{id}                Update a release
DELETE /v1/releases/{id}                Delete a release

GET    /v1/tasks                        List tasks (filter + paginate)
POST   /v1/tasks                        Create a task
GET    /v1/tasks/{id}                   Get a task
PUT    /v1/tasks/{id}                   Update a task
DELETE /v1/tasks/{id}                   Delete a task

GET    /v1/projects/{id}/tasks          Tasks for a project
GET    /v1/releases/{id}/tasks          Tasks for a release
GET    /v1/workstreams/{id}/tasks       Tasks for a workstream

GET    /v1/search/tasks?q=...           Full-text search
POST   /v1/import                       Bulk import (idempotent upsert)
```

---

## Jira CSV Migration

### Export from Jira

1. Navigate to your Jira project → Issues
2. Click **Export** → **Export Excel CSV (all fields)**
3. Save the file (e.g., `jira_export.csv`)

### Expected CSV columns

The script reads these columns (all others are silently ignored):

| Column | Maps to |
|--------|---------|
| `Issue Key` | Task ID (deterministic UUID via `uuid.uuid5`) |
| `Summary` | `task.title` |
| `Description` | `task.description` (raw text, no markup conversion) |
| `Project Name` | `project.name` |
| `Fix Version/s` | `release.name` (first value used; comma-separated) |
| `Status` | `task.status` (see mapping below) |
| `Created` | `task.created_at` |
| `Updated` | `task.updated_at` |

### Jira status mapping

| Jira status | Tracker status |
|-------------|----------------|
| Open, To Do, In Progress, In Review, Blocked, Reopened | `open` |
| Done, Closed, Resolved, Won't Do, Cancelled | `closed` |

### Run the migration

```bash
python tools/tracker/migrate_jira.py \
    --csv jira_export.csv \
    --tracker-url http://localhost:8030 \
    --tracker-token <your-token>
```

**Dry run** (prints what would happen, no requests made):

```bash
python tools/tracker/migrate_jira.py \
    --csv jira_export.csv \
    --tracker-url http://localhost:8030 \
    --dry-run
```

**With workstream mapping**:

```bash
# workstreams.json maps Jira issue-key prefixes or summary keywords to workstream IDs
cat workstreams.json
{"RINGS-": "ws-abc123", "flowtree": "ws-def456"}

python tools/tracker/migrate_jira.py \
    --csv jira_export.csv \
    --tracker-url http://localhost:8030 \
    --tracker-token <token> \
    --workstream-map workstreams.json
```

Tasks that do not match any mapping entry are imported with `workstream_id=null`
and a warning is printed.

### Re-running is safe

Task IDs are derived deterministically from Jira issue keys using `uuid.uuid5`,
so running the script multiple times will upsert existing records rather than
creating duplicates.

---

## Running Tests

```bash
# Tracker service tests
cd tools/tracker/tests
pip install starlette uvicorn httpx
python -m pytest . -v

# ar-manager MCP tool tests (includes tracker tools)
cd tools/mcp/manager
python -m pytest test_server.py -v -k "Tracker"

# Java MCP discovery test
mvn test -pl flowtree -Dtest=McpToolDiscoveryTest
```
