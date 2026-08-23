"""Which ar-manager tools an agent session can actually invoke.

Enabling an orchestrator workstream takes two independent grants, and setting
only one of them leaves an operator stuck with a denial that names neither:

* **Controller-side.** ``dispatchCapable`` on the workstream, checked by
  ``workspace_map._require_dispatch_capable``. This is the real gate, and the
  only one the opencode harness has, because that harness filters by server
  rather than by tool.
* **Harness-side.** The ``--allowedTools`` CSV the Claude Code harness is
  launched with, built by ``McpConfigBuilder.buildAllowedTools``. Claude Code
  filters per tool, so a tool missing here is denied even when the controller
  would allow it.

The classification below mirrors the sets in that Java class. Two languages
cannot share the definition, so the risk is drift, and the answer to drift is
not care — it is a test. ``McpToolClassificationParityTest`` reads both this
file and the Java one and fails when they disagree, which is why the sets here
are written as plain literals a parser can find rather than assembled at
runtime.
"""

# Granted to coding agents by default. Mirrors
# McpConfigBuilder.AR_MANAGER_TOOL_NAMES.
GRANTED_TOOLS = (
    "controller_health",
    "agent_options",
    "send_message",
    "memory_recall",
    "memory_namespaces",
    "consult",
    "memory_store",
    "workstream_context",
    "workstream_list",
    "workstream_get_status",
    "workstream_get_job",
    "workstream_submit_task",
    "github_pr_find",
    "github_pr_review_comments",
    "github_pr_conversation",
    "github_pr_reply",
    "github_list_open_prs",
    "github_create_pr",
    "github_request_copilot_review",
    "github_read_file",
    "github_pr_check_status",
    "github_list_workflow_runs",
    "github_workflow_run_status",
    "project_read_plan",
    "tracker_get_task",
    "tracker_list_tasks",
    "tracker_search_tasks",
    "tracker_project_summary",
    "tracker_list_projects",
    "tracker_list_releases",
)

# Withheld from coding agents. Mirrors
# McpConfigBuilder.EXCLUDED_AR_MANAGER_TOOLS.
EXCLUDED_TOOLS = (
    "controller_update_config",
    "workstream_register",
    "workstream_update_config",
    "workspace_update_config",
    "workstream_archive",
    "workstream_unarchive",
    "workstream_archive_many",
    "workstream_unarchive_many",
    "workstream_delete",
    "project_create_branch",
    "project_verify_branch",
    "project_commit_plan",
    "tracker_create_project",
    "tracker_update_project",
    "tracker_delete_project",
    "tracker_create_release",
    "tracker_update_release",
    "tracker_delete_release",
    "tracker_create_task",
    "tracker_update_task",
    "tracker_delete_task",
    "workspace_secret_list_names",
    "workspace_secret_render_file",
    "workstream_introspect",
)

# Restored to a dispatch-capable workstream's allowlist. Mirrors
# McpConfigBuilder.DISPATCH_AR_MANAGER_TOOLS. Appearing here and in
# EXCLUDED_TOOLS is not a contradiction: the exclusion is the default and this
# is the per-workstream override.
DISPATCH_TOOLS = (
    "workstream_register",
    "workstream_update_config",
)

TOOL_PREFIX = "mcp__ar-manager__"


def allowed_tools(dispatch_capable: bool) -> list:
    """Return the ar-manager tools a Claude Code session may invoke.

    Args:
        dispatch_capable: Whether the workstream carries the controller-side
            dispatch grant.

    Returns:
        Tool names, without the MCP prefix, in classification order.
    """
    tools = list(GRANTED_TOOLS)
    if dispatch_capable:
        for name in DISPATCH_TOOLS:
            if name not in tools:
                tools.append(name)
    return tools


def allowlist_csv(dispatch_capable: bool) -> str:
    """Return the ``--allowedTools`` fragment for the ar-manager server.

    The same string ``McpConfigBuilder.buildAllowedTools`` contributes, so an
    operator can compare it against what a launched agent actually received.

    Args:
        dispatch_capable: Whether the workstream carries the dispatch grant.

    Returns:
        A comma-separated list of prefixed tool names.
    """
    return ",".join(TOOL_PREFIX + name for name in allowed_tools(dispatch_capable))
