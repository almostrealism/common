"""Workspace-throughput retrospective tools for the AR Manager MCP server.

These answer the owner's "how much happened in the last N hours" question in a
single call, wrapping the controller's ``/api/throughput/window`` endpoint
(served by ``ThroughputQueryHandler``). Kept in their own ``*_tools.py`` module,
beside ``server.py`` rather than inside it, so the near-cap server file does not
grow and so the ``*_tools`` suffix enrolls them in the tool surface that
``McpToolDiscovery.locateManagerSources`` scans.

Like the other read tools, this routes through the controller
(``server._controller_get``), reaches shared helpers through ``server`` so the
test suite's patches still apply, and enforces the read scope gate.
"""

import server
from server import mcp


@mcp.tool()
def throughput_window(
    preset: str = "",
    start: str = "",
    end: str = "",
    workstream_id: str = "",
) -> dict:
    """Summarise everything that happened in a time window — the retrospective.

    Answers "how much happened in the last 1h / 3h / 6h / 24h" (or any custom
    window) in one call, instead of dozens of per-job reads. Pass either a
    ``preset`` or an explicit ``start``/``end`` pair.

    The response reports, over the window: jobs by outcome (submitted, started,
    finished, success, failed, degraded, cancelled, blocked, lost, running,
    unresponsive); total spend with per-runner, per-model and per-kind
    breakdowns; agentic turns; agent wall-clock time as the overlap of each
    job's run with the window (so parallel work and boundary-straddling jobs are
    counted correctly); a per-node busy-time breakdown; and token totals with a
    ``coverage`` label — token capture is recent, so a window whose finished jobs
    predate it honestly reports ``"none"`` or ``"partial:n/m"`` rather than a
    misleadingly small number.

    Args:
        preset: One of ``"1h"``, ``"3h"``, ``"6h"``, ``"24h"``, ``"7d"``,
            resolved to a window ending now. Supply this or ``start``/``end``,
            not both.
        start: Inclusive window start as an ISO-8601 instant
            (e.g. ``"2026-11-28T00:00:00Z"``). Requires ``end``.
        end: Exclusive window end as an ISO-8601 instant. Requires ``start``.
        workstream_id: Restrict the retrospective to one workstream. Empty
            (the default) covers every workstream.

    Returns:
        dict with the window's statistics on success, or ``ok=False`` with an
        error message when neither a preset nor a start/end pair was supplied or
        the controller rejected the request.
    """
    server._require_scope("read")
    err = server._check_short_strings(
        preset=preset, start=start, end=end, workstream_id=workstream_id,
    )
    if err:
        return err
    server._audit("throughput_window", preset=preset, start=start, end=end,
                  workstream_id=workstream_id)

    if not preset and not (start and end):
        return {
            "ok": False,
            "error": "Supply 'preset' (1h/3h/6h/24h/7d) or both 'start' and 'end'",
        }

    params = {}
    if preset:
        params["preset"] = preset
    else:
        params["from"] = start
        params["to"] = end
    if workstream_id:
        params["workstream"] = workstream_id

    from urllib.parse import urlencode
    return server._controller_get("/api/throughput/window?" + urlencode(params))
