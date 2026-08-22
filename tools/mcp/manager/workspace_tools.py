"""
Workspace configuration and secret-rendering tools for the AR Manager
MCP server.

Split out of ``server.py`` for length. The tools are unchanged; only their
address is. See ``tracker_tools`` for the conventions this module follows —
the ``_tools`` suffix that makes it visible to tool discovery, and reaching
anything defined in ``server`` through the module rather than by import, so
the suite's patches still apply. Helpers and constants stay in ``server.py``.
"""

from urllib.parse import quote

import server
import tool_capabilities
import workspace_map
from server import mcp


@mcp.tool()
def workspace_update_config(
    workspace_id: str = "",
    default_phase_config: str = "",
    phase_configs: str = "",
    name: str = "",
    default_channel: str = "",
    new_id: str = "",
    slack_team_id: str = server._WORKSPACE_UNSET,
    slack_workspace_id: str = "",
    # Removed legacy config parameters — see server._reject_removed_config_params.
    # Untyped so they stay out of the declared tool schema while still being
    # captured here for a clear rejection error.
    default_runner="",
    runners="",
) -> dict:
    """Update workspace-level configuration on a workspace entry.

    A workspace is the operator's organisational unit. Its ``id`` is
    operator-chosen and independent of any Slack team ID; when a Slack
    connection is configured the team ID lives on the ``slackTeamId``
    field. This tool can rename a workspace, retarget its Slack
    connection, and update non-credential operational fields.

    Only the fields you supply are written; an empty string leaves the
    corresponding field unchanged (except ``slack_team_id``, where an
    explicit empty string clears the Slack connection — see below).
    Changes are persisted back to the workstreams YAML so they survive a
    controller restart.

    For security, the following workspace fields are **NOT** settable via
    this tool and must be edited in the YAML directly:

    * ``tokensFile``, ``botToken``, ``appToken`` — Slack credentials.
    * ``githubOrgs`` — controls which GitHub orgs the workspace can
      issue tokens for.
    * ``channelOwnerUserId`` / ``channelOwnerUserIds`` — administrative
      auto-invite ownership.

    Args:
        workspace_id: Operator-chosen workspace identifier (e.g.
            ``"almostrealism"``) of the workspace to update. For
            workspaces migrated from a legacy ``slackWorkspaces:`` YAML
            entry this is the Slack team ID until the workspace is
            renamed via ``new_id``. Required.
        default_phase_config: New workspace-level default configuration as a
            JSON object with optional ``runner`` / ``model`` / ``effort`` /
            ``provider`` keys. Pass ``'{}'`` to clear the stored default.
            Empty string leaves it unchanged. Applied to workstreams in this
            workspace when neither the workstream nor the per-job override
            sets a value. Use ``agent_options`` to discover valid runner
            names. Example::

                '{"runner": "opencode", "model": "qwen3-coder:exacto",
                  "effort": "medium", "provider": "openrouter"}'

        phase_configs: New workspace-level per-phase overrides as a JSON
            object whose keys are phase wire names and whose values are
            ``{runner, model, effort, provider}`` objects (all keys optional).
            Pass ``'{}'`` to clear all per-phase overrides. Set a phase value
            to ``null`` (e.g. ``'{"review": null}'``) to clear just that
            phase's override. Empty string leaves the per-phase map unchanged.
            Each named phase overrides ``default_phase_config`` field-by-field.
        default_runner: REMOVED. The legacy ``default_runner`` parameter is no
            longer accepted; passing it fails with a 400-style error. Use
            ``default_phase_config='{"runner": "..."}'``.
        runners: REMOVED. The legacy ``runners`` map is no longer accepted.
            Use ``phase_configs`` (per-phase) or ``default_phase_config``.
        name: New human-readable workspace label (used in logs and
            diagnostics). Low-risk operational field.
        default_channel: New fallback Slack channel ID for messages
            published in workstreams that have no channel of their own
            resolved. Low-risk operational field.
        new_id: Rename the workspace to this new operator-chosen ID.
            Every workstream that referenced the old ID is rewritten to
            the new ID atomically. Use this to migrate a workspace from
            its initial Slack-team-ID-as-ID form to a friendlier name
            (e.g. ``workspace_update_config(workspace_id="T0123456789",
            new_id="almostrealism")``). Empty string leaves the ID
            unchanged.
        slack_team_id: Set or clear the Slack team ID this workspace
            routes messages to. Pass a non-empty value to (server.re)bind the
            workspace to that Slack team; pass an explicit empty string
            (``""``) to clear the Slack connection so channel/notifier
            operations skip cleanly. Omit the argument entirely to leave
            the existing value unchanged.
        slack_workspace_id: Not a parameter — rejected with a pointer to
            ``workspace_id``. Workspace identity is the operator's, not
            Slack's; the Slack-side identifier is ``slack_team_id``.
            Accepted for backward compatibility with older callers.

    Returns:
        dict with ``ok=True`` and the updated workspace fields, or
        ``ok=False`` with an error.
    """
    server._require_scope("write")
    # Resolve the workspace identifier, accepting the legacy alias.
    if slack_workspace_id:
        return {
            "ok": False,
            "error": "slack_workspace_id is not a parameter; use workspace_id "
                     "instead. Workspace identity is not Slack's — Slack is an "
                     "optional integration, not the source of truth. The "
                     "Slack-side identifier is slack_team_id.",
        }
    if not workspace_id:
        return {
            "ok": False,
            "error": "workspace_id is required",
            "next_steps": [
                "Pass workspace_id (the operator-chosen workspace ID)",
            ],
        }
    err = server._reject_removed_config_params(
        default_runner=default_runner, runners=runners)
    if err:
        return err
    slack_team_id_provided = slack_team_id != server._WORKSPACE_UNSET
    if not slack_team_id_provided:
        slack_team_id = ""
    err = server._check_short_strings(
        workspace_id=workspace_id,
        name=name,
        default_channel=default_channel,
        new_id=new_id,
        slack_team_id=slack_team_id,
    )
    if err:
        return err
    parsed_default_phase_config, default_pc_err = server._parse_default_phase_config_json(default_phase_config)
    if default_pc_err:
        return default_pc_err
    parsed_phase_configs, phase_configs_err = server._parse_phase_configs_json(phase_configs)
    if phase_configs_err:
        return phase_configs_err
    server._audit("workspace_update_config", workspace_id=workspace_id)

    payload = {}
    if name:
        payload["name"] = name
    if default_channel:
        payload["defaultChannel"] = default_channel
    if new_id and new_id != workspace_id:
        payload["newId"] = new_id
    if slack_team_id_provided:
        # Empty string clears; non-empty (server.re)binds. Either case is a write.
        payload["slackTeamId"] = slack_team_id
    # Use `is not None` so that an empty-dict clear signal ({}) is forwarded.
    if parsed_default_phase_config is not None:
        payload["defaultPhaseConfig"] = parsed_default_phase_config
    if parsed_phase_configs is not None:
        payload["phaseConfigs"] = parsed_phase_configs

    if not payload:
        return {
            "ok": False,
            "error": "No fields to update. Provide at least one field.",
            "next_steps": [
                "Specify fields to update: default_phase_config, "
                "phase_configs, name, default_channel, "
                "new_id, or slack_team_id",
            ],
        }

    result = server._controller_post(
        f"/api/workspaces/{quote(workspace_id, safe='')}/config",
        payload,
    )

    if result.get("ok"):
        result["next_steps"] = [
            "Use workstream_list to verify workstreams now reflect the "
            "updated workspace defaults",
        ]
    else:
        result.setdefault("next_steps", [
            "Use workstream_list to confirm the workspace_id is correct",
        ])

    return result

@mcp.tool()
def workspace_secret_list_names(
    workstream_id: str,
) -> dict:
    """List the names of secrets accessible to the calling workstream's workspace.

    Returns only names — no payload values. Useful for an agent to discover
    what secrets are available before calling workspace_secret_render_file.

    Args:
        workstream_id: The workstream whose workspace's secrets to list.

    Returns:
        dict with ok=True and names list, or ok=False with error.
    """
    server._require_scope("read")
    server._require_workstream_in_scope(workstream_id)
    server._audit("workspace_secret_list_names", workstream_id=workstream_id)

    if not server.SHARED_SECRET:
        return {"ok": False, "error": "Shared secret not configured on ar-manager"}

    # The controller's workstream-scoped endpoints require a Bearer token in
    # the armt_tmp_ family. server.SHARED_SECRET (admin) is rejected here, so mint a
    # short-lived workstream token using the same shared secret.
    temp_token = server._mint_temp_token(workstream_id)
    if temp_token is None:
        return {"ok": False, "error": "Unable to mint workstream token"}

    path = f"/api/secrets?workstream_id={quote(workstream_id, safe='')}"
    resp = server._controller_get(path, auth_token=temp_token)
    if resp.get("ok") is False:
        return {"ok": False, "error": resp.get("error", "controller error")}
    return {"ok": True, "names": resp.get("names", [])}

@mcp.tool()
def workspace_secret_render_file(
    workstream_id: str,
    secret_name: str,
    template: str,
    output_path: str,
    mode: str = "0600",
) -> dict:
    """Fetch a workspace secret and render it into a file using a template.

    The agent supplies a template with {{key}} placeholders. The secret
    payload is fetched from the controller (the agent never sees the raw
    values), all placeholders are substituted, and the result is written
    to output_path with the specified permissions. The rendered content is
    never returned to the agent.

    Template placeholders use {{key}} syntax (double curly braces). Every
    {{key}} in the template must exist in the secret payload — unresolved
    placeholders cause an error and no file is written. Extra keys in the
    payload that are not referenced in the template are silently ignored.

    Example usage for AWS credentials:

        template = \"\"\"[default]
    aws_access_key_id = {{access_key_id}}
    aws_secret_access_key = {{secret_access_key}}
    region = {{region}}
    \"\"\"
        workspace_secret_render_file(
            workstream_id="ws-abc",
            secret_name="aws-prod",
            template=template,
            output_path="~/.aws/credentials",
        )

    After this call the agent can run AWS CLI commands without ever having
    seen the credential values.

    Args:
        workstream_id: The workstream whose workspace owns the secret.
        secret_name: Name of the secret to fetch.
        template: Template string with {{key}} placeholders.
        output_path: Destination file path (~ is expanded).
        mode: Octal file permissions string, e.g. "0600" (default).

    Returns:
        dict with ok=True and output_path on success, or ok=False with
        error. The rendered content is never included in the response.
    """
    server._require_scope("read")
    server._require_workstream_in_scope(workstream_id)
    # Deliberately omit template from audit log — it may contain partial secrets
    # or structural hints. Log only identifying metadata.
    server._audit(
        "workspace_secret_render_file",
        workstream_id=workstream_id,
        secret_name=secret_name,
        output_path=output_path,
        mode=mode,
    )

    if not server.SHARED_SECRET:
        return {"ok": False, "error": "Shared secret not configured on ar-manager"}

    # Validate mode before doing any I/O. int(s, 8) accepts negative numbers
    # and silently parses values outside the POSIX permission range, so check
    # the string shape and the resulting value explicitly.
    if not isinstance(mode, str) or not mode:
        return {"ok": False, "error": "mode must be a non-empty octal string"}
    mode_str = mode[1:] if mode.startswith("0") and len(mode) > 1 else mode
    if not mode_str or any(c not in "01234567" for c in mode_str):
        return {
            "ok": False,
            "error": f"mode must be octal digits 0-7 (got {mode!r})",
        }
    try:
        file_mode = int(mode, 8)
    except ValueError:
        return {"ok": False, "error": f"Invalid octal mode: {mode!r}"}
    if file_mode < 0 or file_mode > 0o777:
        return {
            "ok": False,
            "error": f"mode out of range — must be 0-0777 (got {mode!r})",
        }

    # The controller's retrieve endpoint requires a workstream-scoped temp
    # token; the admin shared secret is rejected. Mint a short-lived token.
    temp_token = server._mint_temp_token(workstream_id)
    if temp_token is None:
        return {"ok": False, "error": "Unable to mint workstream token"}

    # Fetch secret payload from controller
    path = (f"/api/secrets/{quote(secret_name, safe='')}"
            f"?workstream_id={quote(workstream_id, safe='')}")
    resp = server._controller_get(path, auth_token=temp_token)
    if resp.get("ok") is False:
        return {"ok": False, "error": resp.get("error", "controller error")}

    payload = resp.get("payload", {})

    # Strict placeholder resolution — every {{key}} must be present in payload
    placeholders = server.re.findall(r"\{\{(\w+)\}\}", template)
    missing = [p for p in placeholders if p not in payload]
    if missing:
        return {
            "ok": False,
            "error": (
                f"Template references unknown keys: {missing}. "
                f"Available keys: {sorted(payload.keys())}"
            ),
        }

    rendered = template
    for key in placeholders:
        rendered = rendered.replace(f"{{{{{key}}}}}", payload[key])

    # Atomic write: write the rendered content to a sibling temp file, fsync
    # it, set its permissions, then server.os.replace() onto the destination. This
    # avoids leaving a partial / empty credentials file on failure and avoids
    # races where another reader could see the file mid-write.
    expanded = server.os.path.expanduser(output_path)
    parent = server.os.path.dirname(expanded) or "."
    server.os.makedirs(parent, exist_ok=True)
    rendered_bytes = rendered.encode("utf-8")
    tmp_fd, tmp_path = server.tempfile.mkstemp(
        prefix=server.os.path.basename(expanded) + ".",
        suffix=".tmp",
        dir=parent,
    )
    try:
        with server.os.fdopen(tmp_fd, "wb") as tmp_fh:
            tmp_fh.write(rendered_bytes)
            tmp_fh.flush()
            server.os.fsync(tmp_fh.fileno())
        server.os.chmod(tmp_path, file_mode)
        server.os.replace(tmp_path, expanded)
    except Exception:
        # Clean up the orphan temp file on failure; never let it linger with
        # rendered secret content on disk.
        try:
            server.os.remove(tmp_path)
        except OSError:
            pass
        raise

    server.audit_log.info(
        "tool=workspace_secret_render_file secret_name=%s workstream_id=%s "
        "output_path=%s result=OK",
        secret_name, workstream_id, expanded,
    )
    return {"ok": True, "output_path": expanded}

@mcp.tool()
def workstream_introspect(workstream_id: str = "") -> dict:
    """Report which ar-manager tools an agent on this workstream can invoke.

    Enabling an orchestrator takes two independent grants, and setting one
    without the other produces a denial that names neither. This shows both:

    * ``controller`` — the ``dispatchCapable`` flag the server checks. This is
      the real gate, and the only one an opencode session has, because that
      harness filters by server rather than by tool.
    * ``harness`` — the ``--allowedTools`` list a Claude Code session is
      launched with. Claude Code filters per tool, so a tool absent here is
      denied even when the controller would permit it.

    When a tool is denied, compare the two blocks: a tool the controller allows
    but the harness omits is a harness-side gap, and the reverse is a missing
    ``dispatch_capable``.

    Args:
        workstream_id: The workstream to report on. Defaults to the workstream
            bound to the calling token.

    Returns:
        Dictionary with ``controller``, ``harness`` and ``next_steps``.
    """
    server._require_scope("read")
    err = server._check_short_strings(workstream_id=workstream_id)
    if err:
        return err

    effective_id = workstream_id or server._get_token_workstream_id()
    if not effective_id:
        return {
            "ok": False,
            "error": "No workstream to introspect.",
            "next_steps": [
                "Pass workstream_id explicitly",
                "Use workstream_list to find valid workstream IDs",
            ],
        }
    server._audit("workstream_introspect", workstream_id=effective_id)

    ws = server._find_workstream(effective_id)
    if ws is None:
        return {
            "ok": False,
            "error": f"Workstream '{effective_id}' not found",
            "next_steps": ["Use workstream_list to find valid workstream IDs"],
        }

    # Read the live dispatch set rather than the workstream record: it is what
    # the enforcement path consults, so a stale record cannot make this report
    # disagree with the decision it is explaining.
    dispatch_capable = effective_id in workspace_map._get_dispatch_capable_ids()
    granted = tool_capabilities.allowed_tools(dispatch_capable)
    withheld = [t for t in tool_capabilities.EXCLUDED_TOOLS if t not in granted]

    result = {
        "ok": True,
        "workstream_id": effective_id,
        "controller": {
            "dispatch_capable": dispatch_capable,
            "gates": {
                name: dispatch_capable
                for name in tool_capabilities.DISPATCH_TOOLS
            },
        },
        "harness": {
            "claude_code": {
                "filters": "per tool",
                "allowlist_csv": tool_capabilities.allowlist_csv(dispatch_capable),
                "tools_granted": granted,
                "tools_withheld": withheld,
            },
            "opencode": {
                "filters": "per server",
                "note": "This harness cannot filter individual tools, so the "
                        "controller block above is the whole answer for an "
                        "opencode session.",
            },
        },
    }

    if dispatch_capable:
        result["next_steps"] = [
            "This workstream is dispatch-capable; the orchestration tools are "
            "granted on both sides.",
        ]
    else:
        result["next_steps"] = [
            "To grant orchestration: workstream_update_config("
            f"workstream_id='{effective_id}', dispatch_capable=True)",
            "The grant takes effect once the dispatch cache refreshes, and "
            "applies to agent sessions started after that.",
        ]
    return result
