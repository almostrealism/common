"""Incident recording and owner alerting for blocked private-link writes.

When :func:`private_link_detector.check_and_block` refuses a GitHub write, the
block must be *reported* — the owner's requirement is that "being reported" is
true, not rhetorical. This module performs the three reporting side effects for
the ar-manager layer:

* it appends a redacted incident record to a durable, queryable JSONL log;
* it alerts the owner out of band (``send_alert`` to ``michael`` via the
  controller ``/api/alerts`` endpoint, which also forwards to the notification
  channel), rate-limited so a retry storm cannot spam the owner;
* it logs a redacted line to stderr.

Nothing here ever receives or writes the raw matched text: the caller passes a
record built from :func:`private_link_detector.check_and_block`, which contains
only the route, a redaction-safe target description, and the matched pattern
names. The incident log is keyed so prior blocks are queryable with
:func:`query_incidents`.
"""

import json
import os
import sys
import threading
import time
from typing import List, Optional

import private_link_detector

# The alert is deliberately intrusive and costs money per message, so it is
# rate-limited here in addition to the controller's own per-caller limit.
_ALERT_MIN_INTERVAL_SECONDS = 600
_last_alert_at = 0.0
_alert_lock = threading.Lock()

_DEFAULT_LOG = os.path.join(
    os.path.expanduser("~"), ".flowtree", "incidents", "private-link-incidents.jsonl")


def _incident_log_path() -> str:
    """Return the incident log path, honouring ``AR_PRIVATE_LINK_INCIDENT_LOG``."""
    return os.environ.get("AR_PRIVATE_LINK_INCIDENT_LOG", _DEFAULT_LOG)


def _append_incident(record: dict) -> None:
    """Append a redacted incident record to the queryable JSONL log."""
    path = _incident_log_path()
    try:
        directory = os.path.dirname(path)
        if directory:
            os.makedirs(directory, exist_ok=True)
        with open(path, "a", encoding="utf-8") as handle:
            handle.write(json.dumps(record) + "\n")
    except OSError as exc:
        print(f"ar-manager: could not record private-link incident: {exc}",
              file=sys.stderr)


def _should_alert() -> bool:
    """Return whether enough time has passed since the last delivered alert to send another."""
    with _alert_lock:
        return time.monotonic() - _last_alert_at >= _ALERT_MIN_INTERVAL_SECONDS


def _mark_alert_delivered() -> None:
    """Start the rate-limit window; called only once the controller accepted an alert."""
    global _last_alert_at
    with _alert_lock:
        _last_alert_at = time.monotonic()


def guard(server, route: str, target: str, **texts) -> Optional[dict]:
    """Refuse a GitHub write that carries a private Claude session link.

    This is the single entry point the ar-manager ``github_*`` write tools call
    before forwarding anything to the controller proxy. It scans every supplied
    field, and on a match records the incident, alerts the owner, and returns
    the owner's block dict for the tool to return to the agent unchanged.

    Args:
        server: The ar-manager server module.
        route: The write route being guarded (the tool name).
        target: A description of the write target. It may embed
            caller-controlled names (``owner/repo``), so it is redacted before
            it is returned, logged, or recorded.
        **texts: Named fields to scan (e.g. ``title=...``, ``body=...``). A
            ``None`` value is skipped.

    Returns:
        ``None`` when nothing matched (the caller proceeds), or the block dict.
    """
    return private_link_detector.check_and_block(
        route, target, texts,
        recorder=lambda rec: record(
            server, rec["route"], rec["target"], rec["matched_patterns"]))


def record(server, route: str, target: str, matched_patterns: List[str],
           job_id: str = "", workstream_id: str = "") -> dict:
    """Record a blocked private-link write as an incident and alert the owner.

    Args:
        server: The ar-manager server module, used for its controller client,
            token context, and audit log.
        route: The write route that was refused (the tool name).
        target: A description of the write target; redacted here before it
            is written anywhere.
        matched_patterns: The pattern names that matched (no raw text).
        job_id: The job the block occurred under, if known.
        workstream_id: The workstream the block occurred under, if known.

    Returns:
        The redacted incident record that was written.
    """
    target = private_link_detector.redact(target)
    resolved_ws = workstream_id or _safe(lambda: server._get_token_workstream_id()) or ""
    caller = _safe(lambda: server._get_token_label()) or ""
    incident = {
        "timestamp": _utc_now(),
        "type": "private_link_block",
        "route": route,
        "target": target,
        "matched_patterns": matched_patterns,
        "job_id": job_id,
        "workstream_id": resolved_ws,
        "caller": caller,
    }
    _append_incident(incident)

    print(f"ar-manager: BLOCKED private-link write route={route} target={target} "
          f"matched={','.join(matched_patterns)} workstream={resolved_ws}",
          file=sys.stderr)

    _safe(lambda: server._audit(
        "private_link_block", route=route, target=target,
        matched=",".join(matched_patterns), workstream=resolved_ws))

    _alert_owner(server, incident)
    return incident


def _alert_owner(server, incident: dict) -> None:
    """Alert the owner out of band about a blocked private-link write, rate-limited."""
    if not _should_alert():
        return
    text = ("Blocked a private Claude session link from reaching GitHub. "
            f"route={incident['route']} target={incident['target']} "
            f"workstream={incident['workstream_id']}. "
            "The link was redacted; see the private-link incident log.")
    try:
        result = server._controller_post("/api/alerts", {
            "text": text[:1000],
            "recipients": ["michael"],
            "severity": "ERROR",
            "caller": incident.get("caller") or "private-link-guard",
        })
    except Exception as exc:  # noqa: BLE001 - alerting must never break the block
        result = {"ok": False, "error": str(exc)}
    # The controller client reports failure as an ok=False dict rather than
    # raising; only an accepted alert starts the window, so a failure retries.
    if isinstance(result, dict) and result.get("ok") is False:
        error = private_link_detector.redact(str(result.get("error", "")))
        print(f"ar-manager: could not alert owner of private-link incident: {error}",
              file=sys.stderr)
        _append_incident({"timestamp": _utc_now(), "type": "private_link_alert_failure",
                          "route": incident["route"], "error": error})
        return
    _mark_alert_delivered()


def query_incidents(limit: int = 50) -> List[dict]:
    """Return the most recent recorded private-link incidents, newest first.

    Args:
        limit: The maximum number of incidents to return.

    Returns:
        A list of redacted incident records; empty when none have been
        recorded. Alert-failure entries in the same log are not incidents and
        are omitted.
    """
    path = _incident_log_path()
    records: List[dict] = []
    try:
        with open(path, "r", encoding="utf-8") as handle:
            for line in handle:
                line = line.strip()
                if line:
                    try:
                        entry = json.loads(line)
                    except ValueError:
                        continue
                    if isinstance(entry, dict) and entry.get("type") == "private_link_block":
                        records.append(entry)
    except OSError:
        return []
    records.reverse()
    return records[:limit]


def _utc_now() -> str:
    """Return the current UTC time as an ISO-8601 string."""
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


def _safe(thunk):
    """Call ``thunk`` and return its result, or ``None`` if it raised.

    Reporting must never break the block it describes, so every server
    interaction here is best-effort.
    """
    try:
        return thunk()
    except Exception:  # noqa: BLE001 - best-effort reporting
        return None
