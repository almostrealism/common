"""Cached liveness tracking for services an MCP server depends on.

Every AR MCP server fronts at least one thing that can be down independently
of it — a llama.cpp server, Ollama, the ar-memory HTTP service. Each needs
the same treatment: probe cheaply, remember the answer briefly so a status
call is not a network round trip, and re-probe often enough that the answer
still describes the present.

Caching a probe for the life of the process, which is what these clients
used to do, produces the worst possible failure: the health flag reports the
state at first call forever. A dependency that dies still reads as up (so
status output contradicts what requests actually do), and one that recovers
stays marked down until every consumer restarts.
"""

import logging
import os
import time
from typing import Callable, Optional

log = logging.getLogger(__name__)

# How long a probe result is trusted before the dependency is re-probed.
DEFAULT_HEALTH_TTL_SECONDS = 30.0


def health_ttl() -> float:
    """Seconds a probe result stays valid.

    Override with ``AR_INFERENCE_HEALTH_TTL``. A value of 0 forces a probe
    on every read, which is what tests want.
    """
    raw = os.environ.get("AR_INFERENCE_HEALTH_TTL")
    if not raw:
        return DEFAULT_HEALTH_TTL_SECONDS
    try:
        return max(0.0, float(raw))
    except ValueError:
        log.warning(
            "Ignoring non-numeric AR_INFERENCE_HEALTH_TTL=%r; using %.0fs",
            raw, DEFAULT_HEALTH_TTL_SECONDS,
        )
        return DEFAULT_HEALTH_TTL_SECONDS


class HealthCache:
    """A dependency's liveness, re-probed once the cached answer goes stale.

    Args:
        probe: Returns True when the dependency is reachable. Called at most
            once per TTL.

    def __init__(self, probe: Callable[[], bool]):
        self._probe = probe
        self._healthy: Optional[bool] = None
        self._checked_at: float = 0.0

    @property
    def healthy(self) -> bool:
        """Whether the dependency is reachable, re-probing when stale."""
        now = time.monotonic()
        if (
            self._healthy is not None
            and (now - self._checked_at) < health_ttl()
        ):
            return self._healthy
        self._healthy = self._probe()
        self._checked_at = now
        return self._healthy

    def invalidate(self) -> None:
        """Force the next :attr:`healthy` read to re-probe.

        Called after a request fails, since a failure just observed is
        stronger evidence than a probe that succeeded some seconds ago.
        """
        self._healthy = None
        self._checked_at = 0.0
