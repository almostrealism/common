# ar-tracker

Lightweight self-hosted ticket tracking service. Replaces Jira for internal work
organization. Projects, releases, and tasks — nothing more.

The service is a Python/Starlette REST API backed by SQLite, deployed alongside
`ar-memory` and `ar-manager` in the FlowTree controller stack.

---

## Quick Start

### Start via docker-compose

```bash
cd flowtree/runtime/controller
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

ar-manager exposes 15 MCP tools for the tracker. All follow the `tracker_*` naming
convention and mirror the REST API. Every agent sees the same tools; what a call
may do depends on who makes it (see [Who may do what](#who-may-do-what)).

| Tool | Scope | Description |
|------|-------|-------------|
| `tracker_list_projects` | read | List your workspace's projects |
| `tracker_create_project` | write, people only | Create a project, optionally owned by a workspace |
| `tracker_update_project` | write, people only | Rename a project or assign its workspace |
| `tracker_delete_project` | write, people only | Delete a project (tasks are kept, FK set to NULL) |
| `tracker_list_releases` | read | List your workspace's releases (optional `project_id` filter) |
| `tracker_create_release` | write, people and planners | Create a release |
| `tracker_update_release` | write, people only | Update a release |
| `tracker_delete_release` | write, people only | Delete a release (tasks kept, FK set to NULL) |
| `tracker_create_task` | write, everyone | Create a task; an agent without the `planner` role files it into the backlog |
| `tracker_get_task` | read | Fetch one task of your workspace by ID (full record) |
| `tracker_list_tasks` | read | List your workspace's tasks with filtering, pagination, and optional headlines projection |
| `tracker_update_task` | write | Update a task's fields, including its `stage` (mark it `ready` to queue it for planning) |
| `tracker_delete_task` | write, people only | Permanently delete a task |
| `tracker_search_tasks` | read | Full-text search over your workspace's tasks; supports headlines projection |
| `tracker_project_summary` | read | Aggregate task counts for a project (no row fetch) |

### Workspaces

A project is owned by at most one workspace (its `workspace_id`), and its
releases and tasks belong to that workspace. The tracker reports the workspace
on every project, release and task it returns, and its list and search
endpoints accept `workspace_id=<id>[,<id>...]` to return only what those
workspaces own.

A caller limited to some workspaces — every agent job, and any person whose
token is scoped — sees only their projects, releases and tasks. A task with no
project, or whose project no workspace owns, is visible only to callers that
are not limited. A project that existed before workspaces were introduced is
owned by none until a person assigns it with
`tracker_update_project(project_id, workspace_id=...)`.

### Readiness, provenance and dependencies

Every task carries three fields that decide whether an agent may pick it up:

- **`stage`** — `backlog` (the default, and every task that existed before
  these fields), `ready` (may be claimed by a planning round), or `declined`
  (its plan was rejected; out of the queue until a person makes it `ready`).
- **`source`** — `person` (the default), `goals:<document>` for a task an
  agent derived from goal documents, or `follow-up:<workstream>/<job>` for a
  task an agent job filed. An agent never records a task as written by a
  person; when it gives no source, the server records its job.
- **`blocked_by`** — ids of tasks that must close first.

A task is **claimable** when it is open, `ready`, not linked to a workstream,
and not blocked by an open task. That definition lives in one place
(`_CLAIMABLE` in `store.py`) and is used by both `/v1/claimable` and
`/v1/claim`, so asking "is there work?" and taking it never disagree. CI
claims through the FlowTree controller (`POST /api/tracker/claim`) after it
registers the planning round's workstream; the claim links the task to that
workstream, which is what lets the round's agent edit it.

A claimed task is **closed out** when its workstream's branch finishes. The
FlowTree controller checks the branch's pull requests every few minutes
(`TaskCloseOutReconciler`): when one merged, the task is closed; when one was
closed without merging and none is open, the task becomes `declined` and is
unlinked, so making it `ready` again puts it back in the queue. Only tasks that
are open, `ready` and linked change (`_AWAITING_CLOSE_OUT` in `store.py`), so a
task a person kept in `backlog`, or moved while the branch was in review, is
left as they left it.

### Who may do what

ar-manager decides, from the caller's token, what each tracker call may do:

- **Anyone may read** what their workspaces own.
- **Anyone may create a task.** An agent should file work it notices outside
  what it was asked to do instead of widening its branch. An agent job whose
  workstream does not hold the `planner` role gives only the title,
  description, project and release; the tracker files the task in stage
  `backlog`, at the default priority, unlinked, with a `source` of
  `follow-up:<workstream>/<job>` taken from the job's token, and at most
  `MAX_FOLLOW_UP_TASKS_PER_JOB` (in `store.py`) per job, so a looping agent
  cannot flood the tracker.
- **A `planner`** — an agent job whose workstream holds the role in its
  `trackerCapabilities` setting (`workstream_register` /
  `workstream_update_config`, `tracker_capabilities="planner"`) — may set
  every field of a new task, edit any task in its workspace, and create
  releases. Goal decomposition runs as a planner. The retired `steward` role
  is read as `planner`.
- **Any other agent job** may edit only the tasks linked to its own
  workstream: the task its branch was given. It may not change their project,
  workstream link or provenance.
- **Only people** delete, and create, change or delete projects, and change or
  delete releases. ar-manager refuses those tools to an agent job's token even
  when a harness offers them.

### Context-efficiency: fetching a single task

When you already know the task ID, use `tracker_get_task` instead of
listing and filtering:

```
tracker_get_task(task_id="<uuid>")
# → {"ok": true, "task": {...all fields...}}
```

### Context-efficiency: headlines projection

When scanning a large backlog, pass `fields="headlines"` to `tracker_list_tasks`
or `tracker_search_tasks`. This omits the (potentially large) description field
and returns only: `id`, `title`, `priority`, `status`, `project_id`,
`release_id`, `workstream_id`, `created_at`, `updated_at`.

```
tracker_list_tasks(project_id="<uuid>", fields="headlines", limit=200)
tracker_search_tasks(query="auth", fields="headlines")
```

Use `tracker_get_task` to fetch the full record for any task of interest.

### Context-efficiency: project summary

To answer "what's the shape of project X?" without fetching all rows, use
`tracker_project_summary`:

```
tracker_project_summary(project_id="<uuid>")
# → {
#     "ok": true,
#     "summary": {
#       "project_id": "<uuid>",
#       "total_tasks": 214,
#       "by_status": {"open": 130, "closed": 84},
#       "by_priority": {"-2": 3, "-1": 20, "0": 120, "1": 55, "2": 16},
#       "by_release": [
#         {"release_id": "<uuid>", "release_name": "0.39",
#          "task_count": 42, "open_count": 28},
#         ...
#         {"release_id": null, "release_name": null,
#          "task_count": 10, "open_count": 7}
#       ],
#       "by_workstream": [
#         {"workstream_id": "ws-abc", "task_count": 30, "open_count": 20},
#         {"workstream_id": null, "task_count": 100, "open_count": 60}
#       ]
#     }
#   }
```

A caller limited to some workspaces may summarise only a project they own. The
`by_release` list always includes a `null` entry for tasks not assigned to any
release.

### Where the rules live

The rules above are enforced by **ar-manager's MCP layer**, not by the
ar-tracker REST API. The tracker service accepts a single shared bearer token
(`AR_TRACKER_AUTH_TOKEN`) and trusts its caller; what it holds itself are the
invariants of the data — the workspace filter, the backlog/unlinked/capped
filing of an agent's task, and the claim.

---

## REST API

The full API is versioned under `/v1/`. Health check is at `/api/health`.

```
GET  /api/health

GET    /v1/projects                     List projects (?workspace_id=<id>[,<id>...])
POST   /v1/projects                     Create a project ({"name", "workspace_id"})
GET    /v1/projects/{id}                Get a project
PUT    /v1/projects/{id}                Update a project ({"name", "workspace_id"}, each
                                        optional; workspace_id null owns it by none)
DELETE /v1/projects/{id}                Delete a project
GET    /v1/projects/{id}/summary        Aggregate task counts for a project

GET    /v1/releases                     List releases (?project_id=&workspace_id=)
POST   /v1/releases                     Create a release
GET    /v1/releases/lookup              Find a release by name (?project=&release=)
POST   /v1/releases/ensure              Atomically get or create a release by name
                                        ({"project", "release"}; never creates the project)
GET    /v1/releases/{id}                Get a release
PUT    /v1/releases/{id}                Update a release
DELETE /v1/releases/{id}                Delete a release

GET    /v1/tasks                        List tasks (filter + paginate; ?fields=headlines;
                                        ?stage=; ?workspace_id=<id>[,<id>...])
POST   /v1/tasks                        Create a task
GET    /v1/tasks/{id}                   Get a task
PUT    /v1/tasks/{id}                   Update a task
DELETE /v1/tasks/{id}                   Delete a task

GET    /v1/projects/{id}/tasks          Tasks for a project
GET    /v1/releases/{id}/tasks          Tasks for a release
GET    /v1/workstreams/{id}/tasks       Tasks for a workstream

GET    /v1/search/tasks?q=...           Full-text search (?fields=headlines; ?workspace_id=)

GET    /v1/claimable?project=&release=  Count the claimable tasks of a release
POST   /v1/claim                        Claim the next one for a workstream
                                        ({"project", "release", "workstream_id"})
GET    /v1/close-out                    Workstreams with a claimed task awaiting close-out
POST   /v1/close-out                    Apply a branch outcome to its claimed tasks
                                        ({"workstream_id", "outcome": "merged"|"closed"})
POST   /v1/follow-up-tasks              File an agent's task into a project's
                                        backlog (always unlinked, priority 0, capped per
                                        job by its "follow-up:<ws>/<job>" source)
POST   /v1/import                       Bulk import (idempotent upsert; all-or-nothing)
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
| `Priority` | `task.priority` (see mapping below) |
| `Created` | `task.created_at` |
| `Updated` | `task.updated_at` |

### Jira status mapping

The tracker is intentionally binary: `open` or `closed`. Active and queued
work maps to `open`; terminal states map to `closed`. Unknown statuses fall
back to `open` with a one-shot warning per distinct value.

| Jira status | Tracker status |
|-------------|----------------|
| Open, To Do, Backlog, Selected for Development, Ready, Ready for Development, Ready for Review, In Progress, In Review, In Test, In QA, Testing, QA, Verified, Acceptance, In Acceptance Testing, Awaiting Approval, Approved, Pending, Waiting, Blocked, On Hold, Reopened | `open` |
| Done, Closed, Resolved, Released, Deployed, Won't Do, Won't Fix, Cancelled, Duplicate, Invalid, Rejected | `closed` |

### Jira priority mapping

`task.priority` is a signed integer in the closed range `[-2, 2]`, defaulting
to `0` (Medium). Unknown or empty priorities map to `0`.

| Jira priority | Tracker priority | Label   |
|---------------|------------------|---------|
| Highest, Blocker, Critical | `2`  | Highest |
| High, Major   | `1`              | High    |
| Medium        | `0`              | Medium  |
| Low, Minor    | `-1`             | Low     |
| Lowest, Trivial | `-2`           | Lowest  |

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

**CSV without a Project Name column**:

```bash
python tools/tracker/migrate_jira.py \
    --csv rings_export.csv \
    --tracker-url http://localhost:8030 \
    --tracker-token <token> \
    --project-name "Rings"
```

`--project-name` fills in a default for rows that lack a `Project Name`
value in the CSV. Per-row CSV values always take precedence.

**Filtering out Epics, Sub-tasks, and other container issue types**:

```bash
python tools/tracker/migrate_jira.py \
    --csv jira_export.csv \
    --tracker-url http://localhost:8030 \
    --tracker-token <token> \
    --issue-type Story
```

`--issue-type` accepts one or a comma-separated list of Jira `Issue Type`
values (case-insensitive). Rows with any other type are skipped. Omit the
flag to import every row regardless of type.

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
pip install starlette uvicorn httpx pytest
python -m pytest . -v

# ar-manager MCP tool tests (includes tracker tools)
cd tools/mcp/manager
python -m pytest test_server.py -v -k "Tracker"

# Java MCP discovery test
mvn test -pl flowtree/runtime -Dtest=McpToolDiscoveryTest
```
