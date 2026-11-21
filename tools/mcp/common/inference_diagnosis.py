"""Classification of inference-backend failures, shared across backends.

When synthesis fails, "it did not work" is not enough for an operator to act
on: a refused TCP connection, a rejected credential and an empty account each
need a different fix, and the raw ``<urlopen error [Errno 111] Connection
refused>`` string says none of that. This module turns a transport exception —
or an HTTP error response — into one of a fixed set of :data:`CLASSIFICATIONS`,
so every backend reports a failure the same way and a caller can quote a
reason it understands.

It is a sibling of ``health.py``: pure, backend-agnostic mechanics that the
concrete backends in ``inference.py`` compose with their own, type-specific
knowledge (which endpoint they contact, which environment variable or secret
file governs them). Nothing here reaches back into ``inference.py``, so it can
be imported by it without a cycle.

A classification value NEVER carries a credential. The provider message
extracted from an HTTP error body is the provider's own prose ("Insufficient
credits", "invalid key"); the key, and the path that bears it, are not part of
any value this module returns.
"""

import errno
import json
import socket
import urllib.error
import urllib.parse
import urllib.request
from typing import Callable, Optional

# ---------------------------------------------------------------------------
# The fixed classification set
# ---------------------------------------------------------------------------

#: No backend is configured or reachable at all (the passthrough fallback, or a
#: backend that cannot even be constructed).
NOT_CONFIGURED = "not_configured"

#: The selected backend needs a credential and none is configured.
CREDENTIAL_MISSING = "credential_missing"

#: The provider rejected the credential (HTTP 401/403).
CREDENTIAL_REJECTED = "credential_rejected"

#: The account has no remaining quota or credit (HTTP 402/429).
QUOTA_EXHAUSTED = "quota_exhausted"

#: The host refused the TCP connection — nothing is listening there.
CONNECTION_REFUSED = "connection_refused"

#: The host name did not resolve.
DNS_FAILURE = "dns_failure"

#: The request timed out before a response.
TIMEOUT = "timeout"

#: A response arrived but could not be used (unparseable, or empty).
MALFORMED_RESPONSE = "malformed_response"

#: A transport failure that is real but none of the above (e.g. a reset, an
#: unreachable network). Kept distinct so a classification is never guessed.
UNREACHABLE = "unreachable"

#: Every value :func:`classify_transport_error` can return, for validation and
#: documentation.
CLASSIFICATIONS = (
    NOT_CONFIGURED,
    CREDENTIAL_MISSING,
    CREDENTIAL_REJECTED,
    QUOTA_EXHAUSTED,
    CONNECTION_REFUSED,
    DNS_FAILURE,
    TIMEOUT,
    MALFORMED_RESPONSE,
    UNREACHABLE,
)


def http_error_message(error: urllib.error.HTTPError) -> str:
    """Extract a provider's error message from an HTTP error response.

    OpenAI-compatible servers report failures as
    ``{"error": {"message": ...}}``. That message (for example "insufficient
    credits") is far more useful to whoever reads a degraded consult than the
    bare status line. Reads the error body at most once.

    Args:
        error: The HTTP error raised by ``urlopen``.

    Returns:
        The provider's message when present, otherwise the status reason.
    """
    try:
        payload = json.loads(error.read())
        message = payload.get("error", {}).get("message")
        if message:
            return str(message)
    except (OSError, ValueError, AttributeError):
        pass
    return str(error.reason)


def classify_http_status(status: int) -> str:
    """Classify an HTTP status from an inference provider.

    Args:
        status: The HTTP status code.

    Returns:
        :data:`CREDENTIAL_REJECTED` for 401/403, :data:`QUOTA_EXHAUSTED` for
        402/429, :data:`MALFORMED_RESPONSE` otherwise.
    """
    if status in (401, 403):
        return CREDENTIAL_REJECTED
    if status in (402, 429):
        return QUOTA_EXHAUSTED
    return MALFORMED_RESPONSE


def classify_transport_error(exc: BaseException) -> tuple:
    """Classify a transport exception raised while contacting a backend.

    Reads the body of an :class:`~urllib.error.HTTPError` once (for its
    provider message), so the caller must not have read it already.

    Args:
        exc: The exception caught around the request — an ``HTTPError``, a
            ``URLError``, or a bare ``OSError`` / ``TimeoutError`` /
            ``ValueError``.

    Returns:
        ``(classification, http_status, detail)``. ``http_status`` is the HTTP
        code for an ``HTTPError`` and ``None`` otherwise; ``detail`` is a
        human-readable, secret-free description.
    """
    if isinstance(exc, urllib.error.HTTPError):
        status = exc.code
        return classify_http_status(status), status, http_error_message(exc)
    if isinstance(exc, urllib.error.URLError):
        return _classify_reason(exc.reason) + (str(exc.reason),)
    return _classify_reason(exc) + (str(exc),)


def _classify_reason(reason) -> tuple:
    """Classify the underlying reason of a transport failure.

    Args:
        reason: The ``URLError.reason`` object, or a bare transport exception.

    Returns:
        ``(classification, None)`` — the status is always ``None`` here because
        no HTTP response was received.
    """
    if isinstance(reason, socket.gaierror):
        return DNS_FAILURE, None
    if isinstance(reason, (TimeoutError, socket.timeout)):
        return TIMEOUT, None
    if isinstance(reason, ConnectionRefusedError) or \
            getattr(reason, "errno", None) == errno.ECONNREFUSED:
        return CONNECTION_REFUSED, None
    if isinstance(reason, ValueError):
        # A JSON body that would not parse: a response arrived but is unusable.
        return MALFORMED_RESPONSE, None
    return UNREACHABLE, None


def endpoint_of(url: str) -> str:
    """Reduce a backend URL to the ``scheme://host[:port]`` it contacts.

    The path (which for some providers bears a token) and any ``user:pass@``
    credentials in the authority are dropped, so the result is safe to report:
    it names where the request went without naming any secret.

    Args:
        url: The backend's base or completions URL.

    Returns:
        ``scheme://host`` (with ``:port`` when the URL names one), or an empty
        string when ``url`` has no scheme and host (e.g. a local, hostless
        backend).
    """
    parts = urllib.parse.urlsplit(url)
    host = parts.hostname or ""
    if not parts.scheme or not host:
        return ""
    netloc = f"{host}:{parts.port}" if parts.port else host
    return f"{parts.scheme}://{netloc}"


def probe_http_get(url: str, timeout: float = 5.0,
                   accept: Optional[Callable[[dict], Optional[tuple]]] = None,
                   headers: Optional[dict] = None) -> dict:
    """Probe a backend with a ``GET`` and classify the outcome.

    This is the liveness check behind the health tool and the auto-detection
    scan: it performs one request and reports whether the backend answered,
    classifying any failure with the same taxonomy a real request would.

    Args:
        url: The URL to GET (a ``/health``, ``/api/tags`` or ``/key`` endpoint).
        timeout: Seconds to wait before treating the backend as timed out.
        accept: Optional predicate run on a parsed 2xx body. It returns
            ``None`` to accept the backend, or ``(classification, detail)`` to
            reject it despite the 200 — used for OpenRouter's "key is valid but
            has no credit" case, which answers 200 yet means the backend cannot
            serve a request.
        headers: Optional request headers, such as an ``Authorization`` bearer
            for a provider whose liveness endpoint is authenticated. Never
            logged or returned.

    Returns:
        ``{available, classification, http_status, detail}``. ``classification``
        and ``http_status`` are ``None`` when ``available`` is ``True``.
    """
    try:
        req = urllib.request.Request(url, method="GET", headers=headers or {})
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read()
        data = json.loads(body) if body else {}
    except (urllib.error.URLError, OSError, TimeoutError, ValueError) as e:
        classification, status, detail = classify_transport_error(e)
        return {"available": False, "classification": classification,
                "http_status": status, "detail": detail}

    # A 2xx with a syntactically valid but non-object body (an array, a string,
    # a number) cannot carry the fields a backend-specific ``accept`` predicate
    # inspects. Substituting ``{}`` here would hide that: an OpenRouter ``/key``
    # response of ``[]`` has no ``limit_remaining``, so the predicate would pass
    # and the health tool would call a broken provider available. Report it as a
    # malformed response instead.
    if not isinstance(data, dict):
        return {"available": False, "classification": MALFORMED_RESPONSE,
                "http_status": None,
                "detail": "provider returned a non-object JSON response"}

    if accept is not None:
        rejection = accept(data)
        if rejection is not None:
            classification, detail = rejection
            return {"available": False, "classification": classification,
                    "http_status": None, "detail": detail}
    return {"available": True, "classification": None,
            "http_status": None, "detail": ""}


#: The detail reported when the inference module itself cannot be imported, so
#: no backend object exists to diagnose the failure.
NOT_CONFIGURED_DETAIL = "no inference backend could be constructed"

#: The operator hint for that case — there is no backend-specific guidance to
#: give, so it names the general levers.
NOT_CONFIGURED_HINT = (
    "Confirm the inference dependencies are importable in this image, then set "
    "AR_CONSULTANT_BACKEND or start a local model "
    "(see tools/mcp/consultant/README.md).")


def not_configured_cause(detail: str = NOT_CONFIGURED_DETAIL) -> dict:
    """The structured ``cause`` for when no backend object exists at all.

    A backend normally builds its own ``cause`` from its identity (see
    ``InferenceBackend._cause``). When backend construction fails outright —
    the inference module cannot be imported — there is no instance to ask, yet
    a degraded consult or recall must still carry the same classification,
    endpoint and operator-hint fields every other degraded path does. This
    mirrors that shape with a :data:`NOT_CONFIGURED` classification.

    Args:
        detail: A secret-free description of the failure.

    Returns:
        A ``cause`` dict matching ``InferenceBackend._cause``'s keys.
    """
    return {
        "backend": None,
        "backend_selection": "no inference backend is configured",
        "endpoint": "",
        "classification": NOT_CONFIGURED,
        "http_status": None,
        "detail": detail,
        "operator_hint": NOT_CONFIGURED_HINT,
    }


def not_configured_diagnosis(detail: str = NOT_CONFIGURED_DETAIL) -> dict:
    """The structured probe diagnosis for the no-backend-object case.

    The health tool reports a backend's :meth:`probe_diagnosis`; when no
    backend could be constructed it has nothing to probe, so this gives it the
    same-shaped diagnosis (an ``available``/``selection`` pair in place of the
    ``cause``'s ``backend_selection``) rather than dropping to a bare error.

    Args:
        detail: A secret-free description of the failure.

    Returns:
        A diagnosis dict matching ``InferenceBackend._diagnosis``'s keys.
    """
    cause = not_configured_cause(detail)
    return {
        "available": False,
        "backend": cause["backend"],
        "endpoint": cause["endpoint"],
        "selection": cause["backend_selection"],
        "classification": cause["classification"],
        "http_status": cause["http_status"],
        "detail": cause["detail"],
        "operator_hint": cause["operator_hint"],
    }
