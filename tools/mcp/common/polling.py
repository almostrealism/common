"""
Shared blocking-poll helper for AR MCP status tools.

A status-check tool (``get_validation_status``, ``get_run_status``) normally
returns immediately with whatever state the run is in. That forces an agent
waiting on a long run to poll in a loop. This helper lets such a tool
optionally block server-side until the run reaches a terminal state, so the
agent can wait for completion with a single tool call instead of many.

The wait is always bounded: once ``timeout_seconds`` elapses the latest status
is returned even if the run is still in progress, so the caller never hangs.
"""

import asyncio
from typing import Awaitable, Callable, Iterable, Optional, Union

# How often to re-check the run while blocking, in seconds.
DEFAULT_POLL_INTERVAL_SECONDS = 3.0

# Upper bound on a single blocking status call before it returns the latest
# (possibly still-running) status. Keeps a blocking call from waiting forever.
DEFAULT_BLOCK_TIMEOUT_SECONDS = 600.0

# Largest block timeout a caller may request, in seconds.
MAX_BLOCK_TIMEOUT_SECONDS = 3600.0


async def block_until_terminal(
    fetch_status: Callable[[], Optional[dict]],
    terminal_states: Iterable[str],
    timeout_seconds: float = DEFAULT_BLOCK_TIMEOUT_SECONDS,
    poll_interval_seconds: float = DEFAULT_POLL_INTERVAL_SECONDS,
) -> Optional[dict]:
    """Re-invoke ``fetch_status`` until the run reaches a terminal state.

    Returns the most recent status dict. Returns immediately, without sleeping,
    when ``fetch_status`` yields ``None`` (run not found) or the run is already
    in one of ``terminal_states``. Stops and returns the latest status once
    ``timeout_seconds`` elapses, even if the run is still in progress.

    :param fetch_status: callable returning the current status dict, or ``None``
        if the run is unknown. Called on the event loop thread, so it should be
        a cheap, non-blocking lookup.
    :param terminal_states: status values that mean the run has finished.
    :param timeout_seconds: maximum total time to wait before returning.
    :param poll_interval_seconds: delay between status checks while waiting.
    """
    terminal = set(terminal_states)
    deadline = asyncio.get_event_loop().time() + max(0.0, timeout_seconds)
    interval = max(0.0, poll_interval_seconds)

    status = fetch_status()
    while status is not None and status.get("status") not in terminal:
        remaining = deadline - asyncio.get_event_loop().time()
        if remaining <= 0:
            break
        await asyncio.sleep(min(interval, remaining))
        status = fetch_status()
    return status


def resolve_block_timeout(requested: Union[int, float, None]) -> float:
    """Clamp a caller-supplied block timeout into the allowed range.

    ``None`` (or a non-positive value) falls back to
    :data:`DEFAULT_BLOCK_TIMEOUT_SECONDS`; anything larger than
    :data:`MAX_BLOCK_TIMEOUT_SECONDS` is capped at that ceiling.
    """
    if requested is None or requested <= 0:
        return DEFAULT_BLOCK_TIMEOUT_SECONDS
    return min(float(requested), MAX_BLOCK_TIMEOUT_SECONDS)
