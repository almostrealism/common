---
status: proposed
created: 2026-11-18
closed:
tracker: []
---

# `github_update_pr`: let an agent edit a pull request's title and description

**Branch:** `feature/github-update-pr`
**Release:** the release under development (the version in the root `pom.xml`), project "Common"
**Estimated complexity:** Small to medium. Most of the work is one Python tool. The rest is
one gap in the controller proxy and the usual edits that register a tool in several places.

---

## Motivation

An agent can open a pull request with `github_create_pr`, but it cannot change the pull
request afterwards. On a workstream that runs for several rounds, the title and description
are written when the first round opens the pull request. After that they describe the first
round, not the branch. Later rounds change the scope, fix review findings, or split work into
follow-up tasks, and the description falls behind. Sometimes it is wrong from the start, for
example when it was taken from a truncated task prompt. The only fixes now are a person editing
it by hand, or an agent with a shell and `gh` credentials running `gh pr edit`. Coding agents
are not meant to have those credentials: GitHub access goes through ar-manager and the
controller proxy.

The controller already rewrites a companion pull request's title and body in one case
(`GitHubProxyHandler`, the refresh that runs when a pull request is reused; it sends a `PATCH`
through `apiPatch`). That rewrite is controller-internal and comes from the commit message.
There is no way for an agent to ask for one.

**Intent:** add one ar-manager MCP tool, `github_update_pr`, that changes an existing pull
request's **title, description, or both, and nothing else**. Grant it to coding agents like the
other `github_*` tools.

## Current state (verified on this branch)

- **GitHub tools live in `tools/mcp/manager/github_tools.py`.** Each one is an `@mcp.tool()`
  that calls `server._require_scope("github")`. When `org`+`repo` are passed it also calls
  `server._require_org_in_scope(org)`. It resolves the repository with
  `server._resolve_github_repo(...)`, records the call with `server._audit(...)`, and sends the
  request with `server._github_request(method, path, payload)`. `server.py` imports each tool by
  name from `github_tools` (the `from github_tools import (...)` block).
- **The pull request is found by branch in one shared helper.** It is `server._find_pr_by_branch`,
  plus `_find_open_pr_by_branch`. `github_request_copilot_review` already accepts an optional
  `pr_number` and looks up the open pull request for the workstream or branch when it is
  omitted. It also requires an explicit `branch` or `pr_number` under direct `org`/`repo`
  addressing.
- **Archived workstreams are read-only for new artifacts.** `github_create_pr` refuses through
  `server._archived_workstream_error` and `server._archived_branch_error`. The review-interaction
  tools (`github_pr_reply` and the others) have no guard, so a frozen round's pull request can
  still be reviewed.
- **The controller proxy cannot forward `PATCH`. This is the blocker.** GitHub's "update a pull
  request" endpoint is `PATCH /repos/{owner}/{repo}/pulls/{number}`. The proxy has two gaps:
  - `FlowTreeApiEndpoint` sends `/api/github/proxy` to `handleGitHubProxy` only for `GET`,
    `POST` and `PUT`. The endpoint's javadoc table also lists only those three.
  - `GitHubProxyHandler.handle` maps any method other than `POST`/`PUT` to `GET`, and reads a
    body only for `POST`/`PUT`. It sends the request with `HttpURLConnection`, which rejects
    `PATCH`. The handler says so itself in the javadoc of its private `apiPatch`, which uses
    `java.net.http.HttpClient` instead.
  - `FlowTreeApiEndpoint.readBody` handles NanoHTTPD's `POST` (`postData`) and `PUT`
    (`content` temp file) body shapes. Implementation must check which shape NanoHTTPD uses for
    a `PATCH` body.
  - On the Python side, `github_api._github_proxy_request` builds a `urllib.request.Request` with
    whatever `method` it is given, so ar-manager can already send a `PATCH`. Its docstring lists
    "GET, POST, PUT".

  If nothing changes, a `PATCH` from ar-manager is either refused by the endpoint or, once routed,
  silently turned into a `GET`. A `GET` returns the pull request unchanged with HTTP 200, so the
  tool would report success without editing anything. The proxy change is required, not
  optional. The tests in this plan must be able to catch that false success.
- **The grant is declared in three lists, and parity tests keep them in sync:**
  - `McpConfigBuilder.AR_MANAGER_TOOL_NAMES` (Java, `flowtree/runtime`), which builds the
    `--allowedTools` CSV. `McpConfigBuilderTest` fails when a registered server tool is in neither
    the granted nor the excluded set.
  - `tool_capabilities.GRANTED_TOOLS` (Python). `McpToolClassificationParityTest` fails when it
    and the Java set disagree.
  - `McpToolDiscoveryTest.managerAllExpectedToolsAreRegisteredInServerPy` and the matching
    expected-tool list in `tools/mcp/manager/test_server.py`.
- **The private-link hook already covers the new name.** `.claude/hooks/lib/private_url_check.py`
  matches write tools with
  `^mcp__.*ar-manager__github_(?:create_pr|pr_reply|.*comment.*|.*update.*|.*edit.*)$` and scans
  every string in `tool_input`. A title or body containing a claude.ai link, a session id or a
  lab host is therefore blocked before it reaches GitHub, with no hook change. The hook is under
  the CI-file lock and must not be edited on this branch.

## Scope

In scope:

1. A new `github_update_pr` tool in `tools/mcp/manager/github_tools.py`.
2. `PATCH` support in the controller's `/api/github/proxy` (`FlowTreeApiEndpoint` routing,
   `GitHubProxyHandler.handle`, body reading).
3. Granting the tool to coding agents in every list that declares the grant, plus the parity and
   discovery tests that check those lists.
4. Documentation: the tool tables in `tools/mcp/manager/README.md` and `tools/mcp/README.md`, the
   tool list in `flowtree/runtime/docs/claude-code-job.md`, the comment block in
   `workstreams-example.yaml`, and the GitHub section of the agent prompt in
   `InstructionPromptBuilder`. That section lists the PR tools agents may use; the new tool and
   one sentence on when to use it go there.

Out of scope, and **deliberately not exposed by the tool**:

- Every other field of the GitHub update endpoint: `state` (closing or reopening), `base`
  (retargeting), `maintainer_can_modify`, and draft/ready status. Labels, assignees, reviewers,
  milestones and merging are out too. The tool's contract is "title and/or description". A
  caller cannot reach any other field because the payload is built only from those two
  parameters. Nothing from the caller is passed through.
- Editing comments or review bodies. Those are other endpoints, and `github_pr_reply` already
  covers replies.
- Any change to the controller's own pull-request refresh in `GitHubProxyHandler`. Its private
  `apiPatch` stays as it is. Step 2 below may reuse it.

## Approach

### 1. The tool (`tools/mcp/manager/github_tools.py`)

Signature, following the conventions of the neighbouring tools (`workstream_id`, `branch`, `org`,
`repo` with the same meanings and docstrings):

```python
@mcp.tool()
def github_update_pr(
    pr_number: int = 0,
    title: Optional[str] = None,
    body: Optional[str] = None,
    workstream_id: str = "",
    branch: str = "",
    org: str = "",
    repo: str = "",
) -> dict:
```

Behaviour:

- **Something must be changed.** If `title` and `body` are both `None`, return `ok=False` with
  a `next_steps` hint and make no request.
- **The payload contains only what was supplied.** `{"title": ...}`, `{"body": ...}` or both.
  `None` means "leave unchanged". The default is `None` rather than `""` so that `body=""`
  can clear a description on purpose. An empty or whitespace-only `title` is refused locally
  (GitHub requires a title), so the caller gets a clear error instead of a 422.
- **Resolving the pull request works like `github_request_copilot_review`.** An explicit
  `pr_number` is used as given. Otherwise the open pull request for the workstream's or the
  given branch is looked up through `server._find_open_pr_by_branch`. Direct `org`/`repo`
  addressing without `pr_number` requires `branch`. The "pr_number or look it up" block is
  currently written out inside `github_request_copilot_review`. Move it into one shared resolver
  in `server.py`, next to `_find_pr_by_branch`, and call it from both tools. Do not copy it a
  second time.
- **Scope and audit follow the other tools:** `_require_scope("github")`, then
  `_require_org_in_scope(org)` under direct addressing, then
  `_audit("github_update_pr", pr_number=..., fields=[...])`. Record *which* fields changed,
  not their contents, because a description can be long.
- **The request is** `server._github_request("PATCH", f"/repos/{owner}/{repo}/pulls/{n}", payload)`.
- **Check that the edit happened.** GitHub returns the updated pull request. Report `ok=True`
  only when the returned `title`/`body` equal what was sent. Otherwise return `ok=False` and say
  the edit did not take effect. This turns the silent "PATCH became GET" failure described above
  into an error, so it cannot come back unnoticed. Return `number`, `url`, `title`, and which
  fields were updated.
- **Archived workstreams.** Recommendation: refuse with the same
  `_archived_workstream_error("update a pull request", ...)` guard as `github_create_pr`. An
  archived round is read-only, and rewriting its pull request's description changes the frozen
  record. Direct `org`/`repo` addressing has no workstream and is not guarded, as in
  `github_create_pr`. See open question 1.

`server.py`'s `from github_tools import (...)` block gains `github_update_pr`. Update
`github_api._github_request`'s docstring to list `PATCH`.

### 2. `PATCH` through the controller proxy (`flowtree/runtime`)

- `FlowTreeApiEndpoint`: route `Method.PATCH` on `/api/github/proxy` to `handleGitHubProxy`, and
  add the `PATCH` row to the javadoc endpoint table.
- `GitHubProxyHandler.handle`: forward `PATCH` as `PATCH` with its body. `HttpURLConnection`
  cannot send it, so send the `PATCH` with `HttpClient`, the way `apiPatch` already does. Wrap
  the response in the same `{"status", "link", "body"}` envelope as the other methods. Extract
  one private method that does the `HttpClient` `PATCH`, and have both the proxy path and the
  refresh path call it. Two copies of the header block would be a duplication finding. Also stop
  mapping every unknown method to `GET`: an unsupported method gets an error response. A
  caller that asks for a write and silently gets a read is the defect this plan is closing.
- `readBody`: handle a `PATCH` body. Check whether NanoHTTPD stores it inline or as a temp
  file, and test the answer rather than assuming it.

### 3. Grant it to coding agents

Add `"github_update_pr"` next to `"github_create_pr"` in:

- `McpConfigBuilder.AR_MANAGER_TOOL_NAMES`
- `tool_capabilities.GRANTED_TOOLS`
- the expected-tool lists in `McpToolDiscoveryTest` and `tools/mcp/manager/test_server.py`

Do not add it to `EXCLUDED_AR_MANAGER_TOOLS`/`EXCLUDED_TOOLS`. It is an ordinary write to the
caller's own repository, of the same kind as `github_create_pr` and `github_pr_reply`, and has
none of the admin or orchestration character of the excluded set. The existing
`McpToolDiscoveryTest` signature checks for `github_create_pr` are the model for a new check that
`github_update_pr` declares `pr_number`, `title`, `body`, `workstream_id` and `branch`.

### Placement notes

- The tool belongs in `github_tools.py` with the other GitHub tools. It does not belong in
  `project_tools.py` or `workstream_tools.py`: it acts on a pull request, not on a workstream.
- The shared "find the pull request number" resolver belongs in `server.py` next to
  `_find_pr_by_branch`. That file already holds the helpers the GitHub tools share, and the tests
  patch them there by name.
- `PATCH` support belongs in the generic proxy, not in a pull-request-specific controller
  endpoint. The proxy is the one route ar-manager uses for GitHub. A `PATCH`-capable proxy is
  also what the next tool that needs to edit something on GitHub will use.

## Tests

Python (`tools/mcp/manager/test_github_tools.py`, using the existing patch seams on
`server._github_request`, `server._resolve_github_repo` and `server._find_open_pr_by_branch`):

- Only the title is sent; only the body is sent; both are sent. The `PATCH` payload has exactly
  the keys that were supplied, and never `state`, `base` or any other key.
- With neither `title` nor `body`, no request is made.
- An empty title is refused; `body=""` is sent as an empty body.
- Without `pr_number`, the open pull request for the branch is looked up. No open pull request
  gives a clear error. Direct `org`/`repo` without `pr_number` or `branch` is refused before
  resolution.
- A response whose title or body differs from what was sent (the "PATCH answered as GET" case)
  gives `ok=False`.
- The archived-workstream guard refuses, as in the `test_create_pr_rejected_for_archived_*` tests.
  Direct addressing is not guarded.
- `github_request_copilot_review`'s existing tests still pass on the shared resolver.

Java (`flowtree/runtime`):

- `GitHubProxyHandlerTest`: a `PATCH` is forwarded as `PATCH` with its body, and an unsupported
  method is refused instead of downgraded to `GET`. If the handler has no seam that lets this be
  tested without the network, add one. Do not leave the path untested.
- `McpConfigBuilderTest`, `McpToolClassificationParityTest` and `McpToolDiscoveryTest` pass with
  the new tool in the granted set.

Run them as bounded selections (named classes and Python node ids), never a module's whole suite.

## Success criteria

- An agent session can call `mcp__ar-manager__github_update_pr` without a permission denial,
  under both the Claude Code and the opencode harness, because both read the same allowlist.
- Calling it with a title and/or body changes exactly those fields on the pull request, and the
  tool reports `ok=True` only after the response confirms the change.
- No combination of arguments changes the pull request's state, base, draft status or any other
  field.
- A title or body containing a private link is blocked by the existing private-link hook before
  any request is made.
- `/api/github/proxy` forwards `PATCH` and no longer turns an unrecognised method into a `GET`.
- The parity and discovery tests, and the new Python and Java tests, pass. The build validator is
  clean.

## Dependencies

- None outside this repository. All the changed files are in `tools/mcp/manager` and
  `flowtree/runtime`. No Maven module or `pom.xml` changes. `java.net.http` is already used by
  `GitHubProxyHandler`.
- **Deployment order:** the controller has to be redeployed with `PATCH` proxy support before the
  ar-manager tool works. Until then, the response check in step 1 makes the tool fail with an
  error rather than claim success.

## Open questions

1. **Archived workstreams.** Should editing a pull request's title or description be refused when
   the workstream is archived, as `github_create_pr` is? Or is it review-interaction like
   `github_pr_reply`, and allowed? This plan recommends refusing, because the description is part
   of the round's record. The other choice costs one line.
2. **Which pull requests may an agent edit?** "Any agent" with the same scope as the other
   `github_*` tools means any pull request in a repository its token's org scope reaches, not
   only its own workstream's pull request. `github_pr_reply` already has that reach. Restricting
   edits to the workstream's own pull request would be a new kind of check that no existing
   GitHub tool makes. This plan keeps the existing scope model. If the owner wants the narrower
   rule, it should be a separate decision that applies to all the GitHub write tools.
3. **Prompt guidance.** Should the agent prompt (`InstructionPromptBuilder`) encourage updating
   the description at the end of a round that changed the branch's scope, or only list the tool?
   This plan lists the tool with one sentence on when to use it, and leaves stronger guidance to
   the owner.
