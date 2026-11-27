"""
Messaging tools for the AR Manager MCP server.

Split out of ``server.py`` for length. The tools are unchanged; only their
address is. See ``tracker_tools`` for the conventions this module follows —
the ``_tools`` suffix that makes it visible to tool discovery, and reaching
anything defined in ``server`` through the module rather than by import, so
the suite's patches still apply. Helpers and constants stay in ``server.py``.
"""

import hashlib
import time
from urllib.parse import quote

import server
from server import mcp

# Longest a single ``await_message`` call will block, in seconds.
#
# The ceiling is not arbitrary. A blocking tool call produces no output
# on the agent subprocess's stdout, and the orchestrator's inactivity
# watchdog (``AgentRunner.DEFAULT_INACTIVITY_TIMEOUT_MILLIS``, 35
# minutes) destroys a process tree that has been silent that long. A
# wait that stayed under the watchdog only by luck would kill
# collaborating agents at random, so the cap keeps a comfortable margin
# and the tool returns rather than approaching it. Returning is what
# resets the watchdog: the agent calls again.
MAX_AWAIT_SECONDS = 1500

# Longest wait that fits under any MCP client's per-call timeout, in
# seconds.
#
# The server can block as long as it likes; whether the *caller* is
# still listening is decided by its MCP client, and an interactive
# session's client gives a single tool call on the order of a minute
# before it reports a transport error instead of the tool's result.
# A wait that outlives that is worse than a short one: the message
# still lands on the server, but the caller sees a timeout and cannot
# tell whether it waited too long or the tool failed. Job sessions are
# launched with ``MCP_TOOL_TIMEOUT`` raised to cover ``MAX_AWAIT_SECONDS``
# (see ``McpConfigBuilder.applyAgentEnvironment``), so they may ask for
# the cap explicitly; everyone else gets this by default and loops.
TRANSPORT_SAFE_AWAIT_SECONDS = 25

# Default block for a single ``await_message`` call, in seconds.
DEFAULT_AWAIT_SECONDS = TRANSPORT_SAFE_AWAIT_SECONDS

# Length of the message id derived when a caller does not supply one.
DERIVED_MESSAGE_ID_LENGTH = 16

# Longest single long-poll issued against the controller, in seconds.
# Kept short enough that no intermediary times the request out; a
# longer wait is composed from several of these.
CONTROLLER_POLL_SECONDS = 120

# Floor on the interval between successive controller polls, in seconds.
# The blocking belongs on the controller; this only bounds the damage when
# a controller answers a long poll immediately.
MINIMUM_POLL_INTERVAL_SECONDS = 1.0

# Messages a ``mailbox_peek`` returns when the caller does not say.
DEFAULT_PEEK_LIMIT = 20

# Most messages a single ``mailbox_peek`` returns, so one call cannot fill
# the caller's context with a week of conversation.
MAX_PEEK_LIMIT = 200


@mcp.tool()
def send_message(
    text: str,
    workstream_id: str = "",
    job_id: str = "",
    activity: str = "",
    message_id: str = "",
) -> dict:
    """Send a message for archival, notification, and delivery to peers.

    Messages are stored in the memory database by the controller and
    optionally forwarded to a notification channel.  Use this tool to
    report status updates, results, or errors back to the user who
    initiated this task.

    The message is also appended to the workstream's conversation, where
    any other agent session working the same workstream can read it with
    ``await_message``.  That is how two agents on different machines
    talk to each other: this tool is the only way to send, and
    ``await_message`` is the only way to receive.  Nothing extra is
    required to address a peer — a peer awaiting on this workstream sees
    every message posted to it, and a peer that is not listening loses
    nothing, because the conversation is durable and it can catch up.

    Sending is idempotent, so a call that timed out on your side can be
    repeated safely: the message usually landed, and the repeat is
    answered with the original ``seq`` and ``duplicate=true`` instead of
    being posted again.  Every message carries a ``message_id`` for this;
    when you do not supply one it is derived from the sender, activity
    and text, so a retry with the same text is recognised without you
    remembering anything.  To post the same text twice on purpose within
    a few minutes, pass a fresh ``message_id``.

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
        message_id: Identity of this message, used to recognise a retry.
            Defaults to a digest of the sender, activity and text.

    Returns:
        Dictionary with ok=true on success or ok=false with error details.
        A recognised retry carries ``duplicate=true`` and the ``seq`` of
        the message it repeated.
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
    # Archived workstreams are read-only: their conversation stays readable but
    # accepts no new messages. This POSTs to the workstream's own /messages
    # endpoint, appending to the archived round's history — a mutation of the
    # frozen record, not communication with an agent running on it (no agent
    # runs on an archived workstream, since submit is refused). Resolving an
    # archived workstream's ownership now succeeds, so this explicit refusal is
    # what keeps new history off an archived workstream; before it the scope
    # lookup failed closed for archived ids by accident. Unarchive it to post.
    archived_err = server._archived_workstream_error("send a message", effective_ws)
    if archived_err:
        return archived_err
    server._audit("send_message", workstream_id=effective_ws, job_id=effective_job,
           activity=effective_activity, text=text[:80])

    err = server._check_length(text, "text", server.MAX_CONTENT_LEN)
    if err:
        return err

    # Build the controller path
    path = f"/api/workstreams/{quote(effective_ws, safe='')}"
    if effective_job:
        path += f"/jobs/{quote(effective_job, safe='')}"
    path += "/messages"

    sender = _sender_identity(effective_job)
    body: dict = {
        "text": text,
        "sender": sender,
        "messageId": message_id or _derived_message_id(sender, effective_activity, text),
    }
    if effective_activity:
        body["activity"] = effective_activity
    return server._controller_post(path, body)


# The fixed set of block categories, mirroring io.flowtree.jobs.BlockReport.
BLOCK_CATEGORIES = (
    "guardrail",
    "missing_resource",
    "ambiguous_task",
    "out_of_scope",
    "already_done",
)


@mcp.tool()
def job_report_blocked(
    category: str,
    detail: str = "",
    job_id: str = "",
    workstream_id: str = "",
) -> dict:
    """Report that this job cannot proceed, ending it with a structured BLOCKED status.

    Call this instead of exiting silently or writing a free-text abandonment
    note when something genuinely prevents you from completing the task. The
    harness records the report against the job bound to your token, does not
    retry the job through enforcement, and ends it ``BLOCKED`` with the
    ``category`` and ``detail`` visible in ``workstream_get_job`` and the job's
    completion event. A ``guardrail`` claim is re-verified against the tool and
    permission denials actually recorded in the session; a claim it cannot
    corroborate is kept but marked unverified. Report a block only when it is
    real — it is not an escape hatch from difficult but doable work.

    Args:
        category: Why the job is blocked. One of: ``guardrail`` (a guardrail or
            hook denied an action you needed), ``missing_resource`` (a required
            library, model, credential, or file is absent), ``ambiguous_task``
            (the request needs a clarifying decision before it can be acted on),
            ``out_of_scope`` (the change does not belong on this branch), or
            ``already_done`` (the work already exists, typically on the base
            branch).
        detail: Free-text explanation of the specific block — name the rule,
            resource, ambiguity, or existing work. Defaults to empty.
        job_id: The job to report blocked. Defaults to empty, resolving to the
            job bound to the in-flight request's token.
        workstream_id: The owning workstream. Defaults to empty, resolving from
            the token like ``job_id``.

    Returns:
        dict with ok=True on success, or ok=False with error details (including
        an unknown category or a context with no resolvable job).
    """
    server._require_scope("write")

    if category not in BLOCK_CATEGORIES:
        return {
            "ok": False,
            "error": f"Unknown block category: {category!r}",
            "next_steps": [f"Use one of: {', '.join(BLOCK_CATEGORIES)}"],
        }

    per_req_ws, per_req_job, _label, _reason = (
        server._decode_current_request_token_full())
    effective_ws = (workstream_id or per_req_ws
                    or server._request_workstream_id.get(None)
                    or getattr(server._thread_local, "workstream_id", None) or "")
    effective_job = (job_id or per_req_job
                     or server._request_job_id.get(None)
                     or getattr(server._thread_local, "job_id", None) or "")

    server._audit("job_report_blocked", workstream_id=effective_ws,
                  job_id=effective_job, category=category, detail=detail[:200])

    if not effective_ws or not effective_job:
        return {
            "ok": False,
            "error": "A block can only be reported from a job session",
            "next_steps": [
                "Provide workstream_id and job_id, or call from a job session"
                " whose token resolves to a job",
            ],
        }

    err = server._check_length(detail, "detail", server.MAX_CONTENT_LEN)
    if err:
        return err

    path = (f"/api/workstreams/{quote(effective_ws, safe='')}"
            f"/jobs/{quote(effective_job, safe='')}/blocked")
    return server._controller_post(path, {"category": category, "detail": detail})


def _derived_message_id(sender: str, activity: str, text: str) -> str:
    """Derive a message identity from what the caller would repeat on a retry.

    A timed-out ``send_message`` is retried with the same text by the same
    sender, usually within the same activity; nothing else is stable
    across the two calls.  Hashing exactly those three gives the retry the
    identity the original carried, so the controller can recognise it
    without the caller having minted anything.

    Args:
        sender: The sender identity recorded on the message.
        activity: The activity tag, or an empty string.
        text: The message text.

    Returns:
        A short hex digest.
    """
    digest = hashlib.sha256(f"{sender}\n{activity}\n{text}".encode("utf-8"))
    return digest.hexdigest()[:DERIVED_MESSAGE_ID_LENGTH]


def _sender_identity(job_id: str) -> str:
    """Name the caller, so a reader can tell a peer's message from its own.

    A job identifies itself by its job id, which is the identity another
    agent addresses and the one the controller threads notifications
    under.  A caller with no job — an operator at an interactive session,
    or an automation holding a static bearer — identifies itself by its
    token label instead.

    Args:
        job_id: The resolved job id, or an empty string when there is
            none.

    Returns:
        The sender identity to record on the message.
    """
    if job_id:
        return f"job:{job_id}"
    return f"caller:{server._get_token_label() or 'unknown'}"


@mcp.tool()
def send_alert(
    text: str,
    recipients: str,
    severity: str = "INFO",
) -> dict:
    """Send an alert to named people, out of band from any chat channel.

    Use this to reach a person directly — currently by SMS — when
    something needs attention now and a message in a channel would not
    be seen in time.  This is deliberately intrusive and it costs money
    per message, so prefer ``send_message`` for ordinary status
    reporting and keep this for things a person actually needs to be
    interrupted for.

    Recipients are named by handle (``"michael"``, ``"mmurray"``), never
    by phone number.  The controller resolves each handle to a delivery
    provider at send time, so handles are stable even when the
    underlying number or channel changes.  A handle the controller does
    not know is reported back in ``unknown`` rather than failing the
    call, and the known handles are listed in ``known`` so a typo is
    easy to correct.

    The alert body is free-form text.  Include whatever context the
    reader needs to act — which repository, which branch, which job —
    directly in ``text``.  The SMS delivery layer wraps the text in the
    fixed, carrier-registered framing (a brand prefix and a
    STOP/HELP opt-out suffix) and normalizes it to a single GSM-7
    segment, truncating the text if the whole message would run long, so
    keep it to a sentence or two.

    Recipients who have replied STOP are suppressed: they are never sent
    to, and are reported back in ``optedOut`` (see below) rather than in
    ``delivered``.

    Rate limits apply at two levels: an account-wide ceiling shared by
    everyone, and a smaller per-caller budget.  Exceeding either returns
    ``ok=false`` rather than queuing the alert.

    Args:
        text: The alert body.  Free-form, self-contained, and short;
            at most ``MAX_ALERT_TEXT_LEN`` (1000) characters, the same
            limit the controller enforces.
        recipients: Comma-separated recipient handles (e.g.
            ``"michael"`` or ``"michael,mmurray"``).  At least one is
            required.
        severity: One of ``INFO``, ``WARNING``, or ``ERROR``.  Defaults
            to ``INFO``.  Unrecognized values are treated as ``INFO``.

    Returns:
        Dictionary with ``ok=true`` plus ``delivered``, ``unknown``,
        ``optedOut`` and ``known`` recipient lists, or ``ok=false`` with
        error details.  ``optedOut`` names the recipients that were
        skipped because they have unsubscribed by replying STOP; they are
        not in ``delivered`` and are not an error.
    """
    server._require_scope("write")

    names = [n.strip() for n in (recipients or "").split(",") if n.strip()]
    if not names:
        return {
            "ok": False,
            "error": "recipients is required; name at least one handle "
                     "(e.g. recipients=\"michael\")",
        }

    if not text or not text.strip():
        return {"ok": False, "error": "text is required"}

    # The controller rejects anything past this length, so check it here:
    # a caller learns immediately, rather than after a round trip that was
    # never going to succeed.
    err = server._check_length(text, "text", server.MAX_ALERT_TEXT_LEN)
    if err:
        return err

    server._audit("send_alert", recipients=",".join(names),
                  severity=severity, text=text[:80])

    # The caller identity is what the per-caller rate limit is keyed on.
    # The token label is already the identity the audit log records, so
    # a throttled caller can be traced to the same identity in both
    # places without correlating anything.
    return server._controller_post("/api/alerts", {
        "text": text,
        "recipients": names,
        "severity": severity or "INFO",
        "caller": server._get_token_label(),
    })


@mcp.tool()
def await_message(
    workstream_id: str = "",
    since: int = -1,
    timeout_seconds: int = DEFAULT_AWAIT_SECONDS,
    include_own: bool = False,
) -> dict:
    """Wait for a message from another agent working this workstream.

    This is the receiving half of ``send_message`` and the way two agent
    sessions on different machines collaborate.  A typical exchange: you
    submit a job with ``workstream_submit_task`` (targeting a particular
    machine with ``required_labels="hostname:<name>"`` when it matters),
    then call this tool and wait for that agent to report that it is
    ready.  You reply with ``send_message``; it is waiting in the same
    way and acts on what you sent.  Repeat until the work is done, then
    tell it to finish.

    The call BLOCKS until a message arrives or ``timeout_seconds``
    elapses, and it returns the moment a peer posts — there is no
    polling interval to wait out.  A timeout is not a failure: it
    returns ``ok=true`` with ``timed_out=true``, and you call again.
    Doing so is required rather than optional, because a session that
    produces no output for long enough is treated as hung and killed;
    returning from this tool is what proves the session is alive.  Never
    invent busy-work to stay awake, and never abandon a collaboration
    just because one wait came back empty.

    How long one call may block is decided by YOUR MCP client, not by
    this server.  From an interactive session a single tool call is
    allowed roughly a minute before the client reports a transport
    error, so the default wait is ``TRANSPORT_SAFE_AWAIT_SECONDS`` (25)
    and a long wait is a loop of short ones.  A job session launched by
    the controller has its tool timeout raised to cover the cap, and may
    pass ``timeout_seconds`` up to ``MAX_AWAIT_SECONDS`` (1500) to wait
    in one call.  If a wait comes back as a transport timeout rather than
    ``timed_out=true``, the value you passed was too long for your
    client — halve it.

    Pass ``next_since`` from the previous result back as ``since`` so
    that nothing is delivered twice and nothing is missed.  The default
    of ``-1`` starts from the present moment; pass ``0`` to replay the
    whole conversation, which is how a job submitted later picks up
    where an earlier one left off.

    Args:
        workstream_id: Workstream whose conversation to read.  Defaults
            to the workstream resolved from the in-flight request's HMAC
            temp token, so a job session can omit it.
        since: The ``next_since`` from your previous call.  ``-1`` (the
            default) waits for messages sent from now on; ``0`` replays
            the conversation from the beginning.
        timeout_seconds: How long to block.  Defaults to
            ``DEFAULT_AWAIT_SECONDS`` (25, which fits under any MCP
            client's per-call timeout) and is capped at
            ``MAX_AWAIT_SECONDS`` (1500); a larger value is clamped, and
            the response says so.
        include_own: When true, messages you sent are returned as well.
            Defaults to false, so you do not hear your own echo.

    Returns:
        Dictionary with ``ok``, ``messages`` (oldest first, each with
        ``seq``, ``createdAt``, ``sender``, and ``text``), ``next_since``
        to pass to the next call, and ``timed_out``.
    """
    server._require_scope("read")

    effective_ws, effective_job, unresolved = _conversation_reader(workstream_id)
    if unresolved:
        return unresolved

    budget = min(max(int(timeout_seconds), 0), MAX_AWAIT_SECONDS)
    clamped = int(timeout_seconds) > MAX_AWAIT_SECONDS
    exclude = "" if include_own else _sender_identity(effective_job)

    server._audit("await_message", workstream_id=effective_ws,
                  job_id=effective_job, since=since, timeout_seconds=budget)

    base = f"/api/workstreams/{quote(effective_ws, safe='')}/mailbox"
    if exclude:
        base += f"?exclude={quote(exclude, safe='')}&"
    else:
        base += "?"

    cursor = int(since)
    deadline = time.monotonic() + budget

    while True:
        wait = int(min(CONTROLLER_POLL_SECONDS, max(deadline - time.monotonic(), 0)))
        started = time.monotonic()
        result = server._controller_get(
            f"{base}since={cursor}&wait={wait}", timeout=wait + 15)

        if not result.get("ok"):
            return {
                "ok": False,
                "error": result.get("error", "Controller rejected the mailbox read"),
                "next_since": cursor,
            }

        cursor = result.get("nextSince", cursor)
        messages = result.get("messages", [])

        if messages:
            return {
                "ok": True,
                "messages": messages,
                "next_since": cursor,
                "timed_out": False,
                "clamped_to_max_await_seconds": clamped,
            }

        if time.monotonic() >= deadline:
            return {
                "ok": True,
                "messages": [],
                "next_since": cursor,
                "timed_out": True,
                "clamped_to_max_await_seconds": clamped,
                "hint": (
                    "No message arrived within the wait. This is normal."
                    " Call await_message again with since=<next_since> to"
                    " keep waiting, or stop waiting and proceed if the"
                    " collaboration no longer needs a reply."
                ),
            }

        # The wait is meant to happen on the controller. One that answers
        # without honouring it would otherwise be polled in a tight loop.
        idle = min(MINIMUM_POLL_INTERVAL_SECONDS, deadline - time.monotonic())
        if time.monotonic() - started < MINIMUM_POLL_INTERVAL_SECONDS and idle > 0:
            time.sleep(idle)


@mcp.tool()
def mailbox_peek(
    workstream_id: str = "",
    since: int = -1,
    limit: int = DEFAULT_PEEK_LIMIT,
) -> dict:
    """Show a workstream's conversation without waiting and without taking part in it.

    For debugging a stalled collaboration: a person or a supervising agent
    sees what the participants have said to each other — every sender's
    messages, including the caller's own — and what the conversation's
    latest ``seq`` is. Nothing is consumed. The controller keeps no
    per-reader position (each reader holds its own ``since``), so a peek
    changes nothing that ``await_message`` returns afterwards, to this
    caller or to anyone else. Unlike ``await_message`` this never blocks:
    it answers with whatever is in the mailbox now, possibly nothing.

    Messages are kept for seven days, so the oldest a peek can show is a
    week old.

    Args:
        workstream_id: Workstream whose conversation to read. Defaults to
            the workstream resolved from the in-flight request's HMAC temp
            token, so a job session can omit it.
        since: Show only messages after this ``seq``. ``-1`` (the default)
            shows the most recent ``limit`` messages; ``0`` or a positive
            ``seq`` shows the first ``limit`` messages after it.
        limit: Most messages to return. Defaults to ``DEFAULT_PEEK_LIMIT``
            (20) and is capped at ``MAX_PEEK_LIMIT`` (200).

    Returns:
        Dictionary with ``ok``, ``messages`` (oldest first, each with
        ``seq``, ``createdAt``, ``sender`` and ``text``), ``latest_seq``
        (the highest ``seq`` in the mailbox, ``0`` when it is empty) and
        ``truncated`` (true when more messages matched than ``limit``
        allowed), or ``ok=false`` with error details.
    """
    server._require_scope("read")

    effective_ws, effective_job, unresolved = _conversation_reader(workstream_id)
    if unresolved:
        return unresolved

    count = min(max(int(limit), 1), MAX_PEEK_LIMIT)
    server._audit("mailbox_peek", workstream_id=effective_ws,
                  job_id=effective_job, since=since, limit=count)

    after = max(int(since), 0)
    result = server._controller_get(
        f"/api/workstreams/{quote(effective_ws, safe='')}/mailbox"
        f"?since={after}&wait=0")
    if not result.get("ok"):
        return {
            "ok": False,
            "error": result.get("error", "Controller rejected the mailbox read"),
        }

    messages = result.get("messages", [])
    shown = messages[-count:] if int(since) < 0 else messages[:count]
    return {
        "ok": True,
        "messages": shown,
        "latest_seq": result.get("nextSince", after),
        "truncated": len(shown) < len(messages),
    }


def _conversation_reader(workstream_id: str):
    """Resolve whose conversation is being read, and by which job.

    The explicit workstream wins; otherwise both come from the in-flight
    request's token, then the auth middleware's context variable, then the
    thread-local, in that order. A resolved workstream is checked against
    the caller's scope before it is returned.

    Args:
        workstream_id: The workstream the caller named, or an empty string.

    Returns:
        A ``(workstream_id, job_id, error)`` triple. ``error`` is ``None``
        when a workstream was resolved, and otherwise the result the tool
        should return as-is.
    """
    per_req_ws, per_req_job, _label, per_req_reason = (
        server._decode_current_request_token_full())
    effective_ws = (workstream_id or per_req_ws
                    or server._request_workstream_id.get(None)
                    or getattr(server._thread_local, "workstream_id", None) or "")
    effective_job = (per_req_job or server._request_job_id.get(None)
                     or getattr(server._thread_local, "job_id", None) or "")

    if not effective_ws:
        return "", effective_job, {
            "ok": False,
            "error": (
                "workstream_id could not be resolved. Pass workstream_id"
                " explicitly, or call from a job session whose HMAC"
                " temp token resolves to a registered workstream."
            ),
            "per_request_decode_reason": per_req_reason,
            "next_steps": [
                "Use workstream_list to find the workstream ID and pass"
                " workstream_id=<id>",
                "If calling from a job session, verify the bearer token"
                " is an armt_tmp_ HMAC token (a static admin bearer"
                " carries no workstream binding)",
            ],
        }

    server._require_workstream_in_scope(effective_ws)
    return effective_ws, effective_job, None
