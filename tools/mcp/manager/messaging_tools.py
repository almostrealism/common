"""
Messaging tools for the AR Manager MCP server.

Split out of ``server.py`` for length. The tools are unchanged; only their
address is. See ``tracker_tools`` for the conventions this module follows —
the ``_tools`` suffix that makes it visible to tool discovery, and reaching
anything defined in ``server`` through the module rather than by import, so
the suite's patches still apply. Helpers and constants stay in ``server.py``.
"""

import server
from server import mcp


@mcp.tool()
def send_message(
    text: str,
    workstream_id: str = "",
    job_id: str = "",
    activity: str = "",
) -> dict:
    """Send a message for archival and optional notification.

    Messages are stored in the memory database by the controller and
    optionally forwarded to a notification channel.  Use this tool to
    report status updates, results, or errors back to the user who
    initiated this task.

    ``workstream_id`` and ``job_id`` are both optional. In a job session
    (Claude Code or opencode launched by the controller) the in-flight
    request's HMAC temp token already binds the call to a specific
    workstream and job, and both are derived from the bearer
    automatically — so a call with just ``{text, activity}`` posts to the
    correct workstream thread. Explicit values, when supplied, override
    the token-derived ones (the override path is preserved for
    operator/admin callers that hold a static bearer with no
    workstream binding).

    Args:
        text: The message text to send.
        workstream_id: Workstream to send the message to.  Defaults to
            the workstream resolved from the in-flight request's HMAC
            temp token.  Only required when no resolvable token context
            exists (e.g. a static-token admin call).
        job_id: Job to thread the message under.  Defaults to the job
            resolved from the in-flight request's HMAC temp token.  When
            absent the message lands at the top of the workstream's
            channel rather than inside a job thread.
        activity: Optional tag identifying the phase or activity this
            message belongs to (e.g. ``"deduplication"``,
            ``"organizational_placement"``,
            ``"maven_dependency_protection"``).  Defaults to empty
            (primary work).  When the environment variable
            ``AR_AGENT_ACTIVITY`` is set and ``activity`` is not
            supplied, the env var value is used automatically so that
            correction-session agents do not need to pass it explicitly.

    Returns:
        Dictionary with ok=true on success or ok=false with error details.
    """
    server._require_scope("write")

    # Resolve (workstream, job) from explicit args first, then from the
    # in-flight HTTP request's bearer, then from the auth-middleware's
    # ContextVar/thread-local. Emit a structured diagnostic *before* the
    # routing decision so a production failure (e.g. opencode-driven
    # phase posting top-of-channel instead of in the job's thread) leaves
    # enough evidence in the controller log to pinpoint which source
    # supplied the empty job_id without further speculation. The
    # diagnostic does not echo any token body, only the four-way
    # provenance and the decode reason. See
    # :func:`server._decode_current_request_token_full` for the reason vocabulary.
    per_req_ws, per_req_job, per_req_label, per_req_reason = (
        server._decode_current_request_token_full())
    ctx_ws = server._request_workstream_id.get(None)
    ctx_job = server._request_job_id.get(None)
    tl_ws = getattr(server._thread_local, "workstream_id", None)
    tl_job = getattr(server._thread_local, "job_id", None)

    # Reuse the already-decoded per_req_ws/per_req_job and the already-read
    # ctx_* / tl_* values rather than calling _get_token_workstream_id() /
    # _get_token_job_id(), which would each invoke server._decode_current_request_token_full()
    # a second time. The resolution order is identical: explicit arg wins, then
    # per-request bearer, then ContextVar, then thread-local.
    effective_ws = workstream_id or per_req_ws or ctx_ws or tl_ws or ""
    effective_job = job_id or per_req_job or ctx_job or tl_job or ""
    effective_activity = (activity or server.os.environ.get("AR_AGENT_ACTIVITY", "")).strip()

    if effective_ws and not effective_job:
        # The exact production failure mode: a workstream is resolved
        # but the job_id binding has been lost, so the controller URL
        # falls back to the workstream-level /messages endpoint and
        # the message lands at the top of the Slack channel rather
        # than inside the job's thread. Surface every source we
        # examined so a single log line says which one failed.
        server.audit_log.warning(
            "send_message_missing_job_id "
            "explicit_workstream_id=%s explicit_job_id=%s "
            "per_request_workstream_id=%s per_request_job_id=%s "
            "per_request_label=%s per_request_decode_reason=%s "
            "contextvar_workstream_id=%s contextvar_job_id=%s "
            "thread_local_workstream_id=%s thread_local_job_id=%s "
            "effective_workstream_id=%s effective_job_id=%s "
            "activity=%s",
            workstream_id or "", job_id or "",
            per_req_ws or "", per_req_job or "",
            per_req_label or "", per_req_reason,
            ctx_ws or "", ctx_job or "",
            tl_ws or "", tl_job or "",
            effective_ws, effective_job,
            effective_activity or "")
    else:
        server.audit_log.info(
            "send_message_resolved "
            "explicit_workstream_id=%s explicit_job_id=%s "
            "per_request_workstream_id=%s per_request_job_id=%s "
            "per_request_decode_reason=%s "
            "contextvar_workstream_id=%s contextvar_job_id=%s "
            "thread_local_workstream_id=%s thread_local_job_id=%s "
            "effective_workstream_id=%s effective_job_id=%s "
            "activity=%s",
            workstream_id or "", job_id or "",
            per_req_ws or "", per_req_job or "",
            per_req_reason,
            ctx_ws or "", ctx_job or "",
            tl_ws or "", tl_job or "",
            effective_ws, effective_job,
            effective_activity or "")

    if not effective_ws:
        return {
            "ok": False,
            "error": (
                "workstream_id could not be resolved. Pass workstream_id"
                " explicitly, or call from a job session whose HMAC"
                " temp token resolves to a registered workstream."
            ),
            "next_steps": [
                "Use workstream_list to find the workstream ID and pass"
                " workstream_id=<id>",
                "If calling from a job session, verify the bearer token"
                " is an armt_tmp_ HMAC token (a static admin bearer"
                " carries no workstream binding)",
            ],
        }

    if not effective_job and not workstream_id and not job_id:
        # The caller asked for automatic job-thread routing (passed
        # neither workstream_id nor job_id explicitly) but every
        # resolution path returned an empty job_id. Two scenarios:
        #
        #   1. The caller is a static-token admin whose bearer carries
        #      no workstream/job binding at all — ``per_req_reason`` is
        #      ``not_temp_token`` / ``no_auth_header`` / ``non_bearer_scheme``
        #      and they did not pass an explicit workstream_id. In that
        #      case the system cannot tell where to thread, but it also
        #      has no expectation of threading. Fail loudly with a
        #      clear "pass workstream_id" instruction so the caller
        #      knows how to recover — silently posting to the channel
        #      top-level is the silent-degradation the prior
        #      ``send_message_missing_job_id`` warning logged but
        #      swallowed.
        #
        #   2. The caller is a job session whose temp token should have
        #      resolved a job (and the agent expects threading), but
        #      resolution failed — the FastMCP stateful-transport
        #      request-propagation hazard the per-request decoder
        #      exists to handle, or a token-issuance problem upstream.
        #      This is the production bug the loud-fail guards
        #      against: previously the tool would post at the channel
        #      top-level and return success, leaving the agent to
        #      assume the message threaded. Returning an explicit
        #      ``ok=false`` with named next-steps gives the agent a
        #      recovery path (``workstream_id`` + ``job_id`` explicit)
        #      and surfaces the failure to the operator via the
        #      ``send_message_unthreaded`` audit line.
        #
        # A caller who genuinely wants workstream top-level posting
        # must pass ``workstream_id`` explicitly; the default-empty
        # ``workstream_id`` is the "I expect auto-resolution" signal.
        server.audit_log.error(
            "send_message_unthreaded "
            "explicit_workstream_id=%s explicit_job_id=%s "
            "per_request_workstream_id=%s per_request_job_id=%s "
            "per_request_label=%s per_request_decode_reason=%s "
            "contextvar_workstream_id=%s contextvar_job_id=%s "
            "thread_local_workstream_id=%s thread_local_job_id=%s "
            "effective_workstream_id=%s effective_job_id=%s "
            "activity=%s",
            workstream_id or "", job_id or "",
            per_req_ws or "", per_req_job or "",
            per_req_label or "", per_req_reason,
            ctx_ws or "", ctx_job or "",
            tl_ws or "", tl_job or "",
            effective_ws, effective_job,
            effective_activity or "")
        return {
            "ok": False,
            "error": (
                "send_message could not resolve a job_id. The tool"
                " advertises job_id as optional because it auto-resolves"
                " from the in-flight request's HMAC temp token, but the"
                " resolution failed and the call did not supply explicit"
                " workstream_id/job_id. Posting at the workstream top"
                " level silently would be deceptive; the message has"
                " NOT been sent. Pass workstream_id AND job_id"
                " explicitly (e.g. workstream_id=<id>, job_id=<id>),"
                " or fix the bearer so the per-request decode can"
                " resolve them."
            ),
            "per_request_decode_reason": per_req_reason,
            "next_steps": [
                "Pass workstream_id and job_id explicitly in the call",
                "If calling from a job session, verify the bearer is"
                " an armt_tmp_ HMAC temp token and that it is being"
                " sent on the request (the controller log line"
                " 'temp_token_request' is written by the auth"
                " middleware on every authenticated request — if it"
                " is absent for the failing call, the bearer is not"
                " reaching the server)",
                "If the bearer IS a temp token, the per-request"
                " decode may have hit a transport-level issue. Pass"
                " workstream_id and job_id explicitly as a safe"
                " fallback until the upstream transport is fixed.",
            ],
        }

    if effective_activity:
        err = server._check_length(effective_activity, "activity", server.MAX_SHORT_STRING_LEN)
        if err:
            return err

    server._require_workstream_in_scope(effective_ws)
    server._audit("send_message", workstream_id=effective_ws, job_id=effective_job,
           activity=effective_activity, text=text[:80])

    err = server._check_length(text, "text", server.MAX_CONTENT_LEN)
    if err:
        return err

    # Build the controller path
    path = f"/api/workstreams/{server.quote(effective_ws, safe='')}"
    if effective_job:
        path += f"/jobs/{server.quote(effective_job, safe='')}"
    path += "/messages"

    body: dict = {"text": text}
    if effective_activity:
        body["activity"] = effective_activity
    return server._controller_post(path, body)
