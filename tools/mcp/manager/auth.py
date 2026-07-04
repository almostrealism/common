"""
Bearer-token authentication, request scoping, and ASGI middleware.

This module owns everything that touches a request's security context:

* Per-request :class:`contextvars.ContextVar` storage for scopes, the
  token label, workspace scopes, workstream ID, and job ID.
* HMAC temp-token validation and minting (``armt_tmp_`` tokens signed
  with ``SHARED_SECRET`` from :mod:`config`).
* ``_require_scope`` / ``_audit`` / ``_check_length`` / ``_check_short_strings``
  — the four guards called at the top of every MCP tool handler.
* ``BearerAuthMiddleware`` — ASGI middleware that validates Bearer tokens
  and populates the per-request scope context before the tool handler runs.
* ``RateLimitMiddleware`` — sliding-window per-client rate limiter.
* ``HealthMiddleware`` — serves ``/_health`` without authentication.

Circular-import notes
---------------------
``_decode_current_request_token_full`` needs ``mcp.get_context()`` which is
defined in ``server.py`` (``mcp`` is the FastMCP instance).  The import is
deferred into the function body with ``import server as _s`` to break the
circular dependency — by the time any MCP tool handler runs, ``server`` is
already in ``sys.modules`` so the deferred import costs essentially nothing.

``BearerAuthMiddleware.__call__`` similarly defers its import of
``_workspace_for_workstream`` / ``_is_multi_workspace_mode`` from
:mod:`workspace_map` because that module imports :mod:`auth` itself.

Extracted from server.py to keep individual modules manageable.
"""

import base64
import contextvars
import hmac
import json
import logging
import os
import sys
import threading
import time
from typing import Optional

from config import (
    SHARED_SECRET,
    TOKEN_FILE,
    RATE_LIMIT,
    MAX_SHORT_STRING_LEN,
    audit_log,
)

# ---------------------------------------------------------------------------
# Per-request scope storage
# ---------------------------------------------------------------------------
# Primary: contextvars (works with asyncio).
# Fallback: threading.local (works if contextvars don't propagate into
# FastMCP's sync-tool thread pool).

_request_scopes: contextvars.ContextVar[Optional[list]] = contextvars.ContextVar(
    "_request_scopes", default=None
)
_request_token_label: contextvars.ContextVar[Optional[str]] = contextvars.ContextVar(
    "_request_token_label", default=None
)
_thread_local = threading.local()


def _get_scopes() -> Optional[list]:
    """Return the scopes for the current request."""
    scopes = _request_scopes.get(None)
    if scopes is not None:
        return scopes
    return getattr(_thread_local, "scopes", None)


def _get_token_label() -> str:
    """Return the label of the token used for the current request."""
    label = _request_token_label.get(None)
    if label is not None:
        return label
    return getattr(_thread_local, "token_label", "anonymous")


def _set_scopes(scopes: list, label: str = "anonymous") -> None:
    """Store scopes and token label for the current request."""
    _request_scopes.set(scopes)
    _request_token_label.set(label)
    _thread_local.scopes = scopes
    _thread_local.token_label = label


_request_workstream_id: contextvars.ContextVar[Optional[str]] = contextvars.ContextVar(
    "_request_workstream_id", default=None
)
_request_job_id: contextvars.ContextVar[Optional[str]] = contextvars.ContextVar(
    "_request_job_id", default=None
)

# Per-request workspace scope. None (or empty list) means the caller is
# unscoped — it may see and act on every workstream in every Slack workspace.
# A non-empty list of workspace IDs restricts the caller to those workspaces.
_request_workspace_scopes: contextvars.ContextVar[Optional[list]] = contextvars.ContextVar(
    "_request_workspace_scopes", default=None
)


def _get_workspace_scopes() -> Optional[list]:
    """Return the list of workspace IDs this request is allowed to touch,
    or None if unscoped (allowed everywhere)."""
    ws = _request_workspace_scopes.get(None)
    if ws is not None:
        return ws
    return getattr(_thread_local, "workspace_scopes", None)


def _set_workspace_scopes(workspace_scopes: Optional[list]) -> None:
    """Store the workspace scope list for the current request. Pass None to
    mark the caller as unscoped (superadmin)."""
    _request_workspace_scopes.set(workspace_scopes)
    _thread_local.workspace_scopes = workspace_scopes


def _is_workspace_allowed(workspace_id: Optional[str]) -> bool:
    """Return True if the current request's workspace scope permits
    operating on the given workspace ID.

    Unscoped callers always pass. Scoped callers only pass when their
    list contains the workspace ID. A workstream with no resolvable
    workspace ID (single-workspace mode or unregistered) is permitted
    only for unscoped callers, since we cannot verify its assignment.
    """
    scopes = _get_workspace_scopes()
    if not scopes:
        return True
    if workspace_id is None or workspace_id == "":
        return False
    return workspace_id in scopes


def _require_workspace(workspace_id: Optional[str]) -> None:
    """Raise PermissionError if the current request is not scoped to the
    given workspace. No-op for unscoped tokens."""
    if not _is_workspace_allowed(workspace_id):
        raise PermissionError(
            "Token is not scoped to workspace "
            + (workspace_id if workspace_id else "<unknown>")
        )


# ---------------------------------------------------------------------------
# HMAC temp-token validation and minting
# ---------------------------------------------------------------------------

def _validate_temp_token(token_value: str) -> Optional[tuple]:
    """Validate an HMAC temporary token.

    Token format: ``armt_tmp_{base64url(hmac)}:{base64url(payload)}``
    Payload format: ``{workstream_id}:{job_id}:{expiry_epoch}``

    Returns:
        ``(scopes, label, workstream_id, job_id)`` on success, or ``None``
        if the token is absent, invalid, or expired.
    """
    import sys as _sys
    _secret = getattr(_sys.modules.get('server'), 'SHARED_SECRET', None)
    if _secret is None:
        _secret = SHARED_SECRET
    if not _secret:
        return None
    if not token_value.startswith("armt_tmp_"):
        return None

    rest = token_value[len("armt_tmp_"):]
    parts = rest.split(":", 1)
    if len(parts) != 2:
        return None

    token_hmac_b64, payload_b64 = parts
    try:
        token_hmac = base64.urlsafe_b64decode(token_hmac_b64 + "==")
        payload = base64.urlsafe_b64decode(payload_b64 + "==").decode("utf-8")
    except Exception:
        return None

    expected_hmac = hmac.new(
        _secret.encode("utf-8"),
        payload.encode("utf-8"),
        "sha256",
    ).digest()
    if not hmac.compare_digest(token_hmac, expected_hmac):
        return None

    payload_parts = payload.split(":")
    if len(payload_parts) != 3:
        return None

    workstream_id, job_id, expiry_str = payload_parts
    try:
        expiry = int(expiry_str)
    except ValueError:
        return None

    if time.time() > expiry:
        return None

    scopes = ["read", "write", "submit", "github", "memory-read", "memory-write"]
    label = f"tmp:{workstream_id}/{job_id}"
    return scopes, label, workstream_id, job_id


def _mint_temp_token(workstream_id: str, job_id: str = "ar-manager",
                     ttl_seconds: int = 60) -> Optional[str]:
    """Mint an HMAC temporary token for an internal call to the controller.

    The controller's workstream-scoped endpoints (e.g.
    ``/api/secrets/{name}?workstream_id=...``) require a Bearer token in the
    ``armt_tmp_`` family rather than the raw shared secret. ar-manager already
    holds ``SHARED_SECRET`` (it uses it to validate inbound tokens), so it can
    sign a short-lived temp token for the workstream and pass it through.

    Returns:
        The signed token string, or ``None`` when ``SHARED_SECRET`` is unset.
    """
    import sys as _sys
    _secret = getattr(_sys.modules.get('server'), 'SHARED_SECRET', None)
    if _secret is None:
        _secret = SHARED_SECRET
    if not _secret:
        return None
    expiry = int(time.time()) + ttl_seconds
    payload = f"{workstream_id}:{job_id}:{expiry}"
    digest = hmac.new(
        _secret.encode("utf-8"),
        payload.encode("utf-8"),
        "sha256",
    ).digest()
    hmac_b64 = base64.urlsafe_b64encode(digest).rstrip(b"=").decode("ascii")
    payload_b64 = base64.urlsafe_b64encode(
        payload.encode("utf-8")).rstrip(b"=").decode("ascii")
    return f"armt_tmp_{hmac_b64}:{payload_b64}"


# ---------------------------------------------------------------------------
# Per-request token context (workstream / job binding)
# ---------------------------------------------------------------------------

def _set_token_context(workstream_id: str, job_id: str) -> None:
    """Store the workstream and job IDs bound to the current request's token."""
    _request_workstream_id.set(workstream_id)
    _request_job_id.set(job_id)
    _thread_local.workstream_id = workstream_id
    _thread_local.job_id = job_id


def _decode_current_request_token_full(
) -> tuple:
    """Decode the Bearer token of the in-flight MCP tool call's HTTP request
    and return diagnostic detail.

    Returns ``(workstream_id, job_id, label, reason)`` where:

    * ``workstream_id`` and ``job_id`` are the values from a valid HMAC
      temp token, or ``None`` when decoding does not succeed.
    * ``label`` is the audit label for the temp token (``tmp:ws/job``),
      or ``None`` on any failure path.
    * ``reason`` is a short identifier describing which path was taken —
      ``"ok"`` on success, otherwise one of ``"no_context"``,
      ``"no_request"``, ``"no_auth_header"``, ``"non_bearer_scheme"``,
      ``"not_temp_token"``, ``"ctx_fallback"``, ``"tl_fallback"``.

    Decoding the token directly from the current request's HTTP
    ``Authorization`` header is the primary path. A defensive fallback to
    the auth middleware's :class:`contextvars.ContextVar` and
    :mod:`threading` local is attempted when the per-request path is
    unavailable (``no_request`` / ``no_context``).

    The ``mcp`` instance is accessed via a deferred ``import server as _s``
    to avoid a circular import — :mod:`server` imports :mod:`auth`, so
    :mod:`auth` cannot import :mod:`server` at module load time. By the time
    any tool handler calls this function, ``server`` is already in
    ``sys.modules``.
    """
    # Primary path: read the bearer from the per-request HTTP request that
    # the FastMCP transport propagated into the dispatch-time RequestContext.
    try:
        import server as _s  # deferred to break circular import with server.py
        ctx = _s.mcp.get_context()
    except (LookupError, AttributeError):
        primary_reason = "no_context"
        request = None
    else:
        try:
            request_context = ctx.request_context
        except (LookupError, ValueError, AttributeError):
            primary_reason = "no_request"
            request = None
        else:
            if request_context is None:
                primary_reason = "no_request"
                request = None
            else:
                request = getattr(request_context, "request", None)
                if request is None:
                    primary_reason = "no_request"
                    request = None
                else:
                    primary_reason = None  # signal "proceed"

    if request is not None:
        try:
            auth_header = request.headers.get("authorization", "")
        except Exception:
            return None, None, None, "no_auth_header"
        if not auth_header:
            return None, None, None, "no_auth_header"
        # RFC 7235 declares the scheme name case-insensitive.
        if not (auth_header.startswith("Bearer ")
                or auth_header.startswith("bearer ")):
            return None, None, None, "non_bearer_scheme"
        token_value = auth_header[7:].strip()
        result = _validate_temp_token(token_value)
        if result is None:
            return None, None, None, "not_temp_token"
        _, label, ws_id, job_id = result
        return ws_id, job_id, label, "ok"

    # Per-request path unavailable. The auth middleware still wrote the
    # decoded (ws, job) into the ContextVar and thread-local for every
    # request carrying a valid temp token — consult those as a fallback.
    ctx_ws = _request_workstream_id.get(None)
    ctx_job = _request_job_id.get(None)
    if ctx_ws and ctx_job:
        return ctx_ws, ctx_job, f"tmp:{ctx_ws}/{ctx_job}", "ctx_fallback"
    tl_ws = getattr(_thread_local, "workstream_id", None)
    tl_job = getattr(_thread_local, "job_id", None)
    if tl_ws and tl_job:
        return tl_ws, tl_job, f"tmp:{tl_ws}/{tl_job}", "tl_fallback"
    return None, None, None, primary_reason


def _decode_current_request_token() -> tuple:
    """Backwards-compatible 2-tuple wrapper around
    :func:`_decode_current_request_token_full`. New callers that want the
    diagnostic ``reason`` should use the full variant directly."""
    ws_id, job_id, _, _ = _decode_current_request_token_full()
    return ws_id, job_id


def _get_token_workstream_id() -> Optional[str]:
    """Return the workstream ID bound to the current request's HMAC token,
    consulting the per-request decode first, then ContextVar/thread-local."""
    req_ws, _ = _decode_current_request_token()
    if req_ws:
        return req_ws
    ws = _request_workstream_id.get(None)
    if ws is not None:
        return ws
    return getattr(_thread_local, "workstream_id", None)


def _get_token_job_id() -> Optional[str]:
    """Return the job ID bound to the current request's HMAC token."""
    _, req_job = _decode_current_request_token()
    if req_job:
        return req_job
    jid = _request_job_id.get(None)
    if jid is not None:
        return jid
    return getattr(_thread_local, "job_id", None)


# ---------------------------------------------------------------------------
# Tool-handler guards
# ---------------------------------------------------------------------------

def _require_scope(scope: str) -> None:
    """Raise PermissionError if the current request does not have *scope*.

    ar-manager only ever serves authenticated requests, so absent scopes
    mean the request did not authenticate — fail closed.
    """
    scopes = _get_scopes()
    if scopes is None:
        raise PermissionError(
            "Unauthenticated request: ar-manager requires a valid token"
        )
    if scope not in scopes:
        raise PermissionError(
            f"Token does not have required scope: {scope}"
        )


def _audit(tool_name: str, **params) -> None:
    """Log an audit entry for a tool invocation."""
    label = _get_token_label()
    sanitized = {k: (v[:80] + "...") if isinstance(v, str) and len(v) > 80 else v
                 for k, v in params.items()}
    audit_log.info("tool=%s token=%s params=%s", tool_name, label, sanitized)


def _check_length(value: str, name: str, max_len: int) -> Optional[dict]:
    """Return an error dict if *value* exceeds *max_len*, else ``None``."""
    if len(value) > max_len:
        return {
            "ok": False,
            "error": f"'{name}' exceeds maximum length of {max_len:,} characters "
                     f"(got {len(value):,})",
        }
    return None


def _check_short_strings(**kwargs) -> Optional[dict]:
    """Validate that all provided string kwargs are within MAX_SHORT_STRING_LEN."""
    for name, value in kwargs.items():
        if isinstance(value, str) and len(value) > MAX_SHORT_STRING_LEN:
            return {
                "ok": False,
                "error": f"'{name}' exceeds maximum length of {MAX_SHORT_STRING_LEN:,} "
                         f"characters (got {len(value):,})",
            }
    return None


def _load_tokens() -> Optional[list]:
    """Load token definitions from env var or file.

    Returns:
        A list of token dicts, or ``None`` if no tokens are configured.
    """
    raw = os.environ.get("AR_MANAGER_TOKENS", "").strip()
    if raw:
        try:
            data = json.loads(raw)
            return data.get("tokens", [])
        except json.JSONDecodeError:
            print("ar-manager: WARNING: AR_MANAGER_TOKENS is not valid JSON",
                  file=sys.stderr)
            return None

    if os.path.isfile(TOKEN_FILE):
        try:
            with open(TOKEN_FILE) as f:
                data = json.load(f)
            tokens = data.get("tokens", [])
            print(f"ar-manager: Loaded {len(tokens)} token(s) from {TOKEN_FILE}",
                  file=sys.stderr)
            return tokens
        except (json.JSONDecodeError, OSError) as e:
            print(f"ar-manager: WARNING: Failed to load token file: {e}",
                  file=sys.stderr)
            return None

    return None


# ---------------------------------------------------------------------------
# ASGI middleware
# ---------------------------------------------------------------------------

class BearerAuthMiddleware:
    """ASGI middleware that validates Bearer tokens before passing requests
    to the wrapped application.

    Unauthenticated requests receive a 401 response. The validated token's
    scopes are stored via :func:`_set_scopes` for downstream tool handlers.
    """

    # Paths that bypass authentication (health checks, OAuth endpoints)
    AUTH_EXEMPT_PATHS = {
        "/_health",
        "/.well-known/oauth-authorization-server",
        "/.well-known/oauth-protected-resource",
        "/oauth/register",
        "/oauth/authorize",
        "/oauth/token",
    }

    def __init__(self, app, tokens: list, issuer_url: Optional[str] = None):
        self.app = app
        # Public issuer URL used to advertise the protected-resource metadata
        # location in the WWW-Authenticate challenge (RFC 9728). When None it
        # is derived per-request from the Host header.
        self.issuer_url = issuer_url.rstrip("/") if issuer_url else None
        # Build a lookup: token value -> (scopes, label, workspace_scopes).
        # workspace_scopes is None for unscoped (superadmin) tokens or a list
        # of Slack workspace IDs for narrower tokens. An empty list in the
        # config is normalised to None so callers can write either "no field"
        # or "workspaceScopes: []" to mean unscoped.
        self.token_entries = []
        for t in tokens:
            value = t.get("value", "")
            scopes = t.get("scopes", [])
            label = t.get("label", "unlabeled")
            ws_scopes_raw = t.get("workspaceScopes")
            if isinstance(ws_scopes_raw, list) and ws_scopes_raw:
                ws_scopes: Optional[list] = [str(w) for w in ws_scopes_raw]
            else:
                ws_scopes = None
            if value:
                self.token_entries.append((value, scopes, label, ws_scopes))

    def _www_authenticate(self, scope) -> bytes:
        """Build the ``WWW-Authenticate`` challenge for a 401 response.

        Advertises the protected-resource metadata location per RFC 9728 /
        the MCP authorization spec, so an MCP client that probes the transport
        endpoint without a token can discover the authorization server from
        the 401 alone.
        """
        from oauth import derive_issuer
        issuer = derive_issuer(scope, self.issuer_url)
        req_path = scope.get("path", "/")
        suffix = "" if req_path in ("", "/") else req_path
        prm = f"{issuer}/.well-known/oauth-protected-resource{suffix}"
        return (f'Bearer realm="ar-manager", '
                f'resource_metadata="{prm}"').encode("utf-8")

    @staticmethod
    def _transport_scope(scope):
        """Normalize an authenticated request's path to the transport root.

        The MCP streamable-HTTP transport is mounted at ``/``, but a connector
        may be configured with an arbitrary path. Rewriting the path here lets
        any such URL reach the single transport handler. OAuth, well-known, and
        health paths are handled by outer middleware and never reach this point.
        """
        if scope.get("path", "/") == "/":
            return scope
        rewritten = dict(scope)
        rewritten["path"] = "/"
        rewritten["raw_path"] = b"/"
        return rewritten

    async def __call__(self, scope, receive, send):
        if scope["type"] == "http":
            path = scope.get("path", "")
            if path in self.AUTH_EXEMPT_PATHS:
                await self.app(scope, receive, send)
                return

            headers = dict(scope.get("headers", []))
            auth = headers.get(b"authorization", b"").decode("utf-8", errors="replace")

            if auth.startswith("Bearer "):
                token_value = auth[7:].strip()
                # Timing-safe comparison: iterate all entries to prevent
                # timing side-channels that reveal token existence.
                matched_scopes = None
                matched_label = None
                matched_workspace_scopes = None
                matched = False
                for stored_value, scopes, label, ws_scopes in self.token_entries:
                    if hmac.compare_digest(
                        token_value.encode("utf-8"),
                        stored_value.encode("utf-8"),
                    ):
                        matched_scopes = scopes
                        matched_label = label
                        matched_workspace_scopes = ws_scopes
                        matched = True
                        break

                if matched:
                    _set_scopes(matched_scopes, matched_label)
                    _set_workspace_scopes(matched_workspace_scopes)
                    # Static tokens are not bound to a specific workstream or
                    # job. Clear any stale temp-token context from a previous
                    # request on this thread — otherwise _get_token_workstream_id()
                    # would fall back to that stale value and incorrectly
                    # identify this caller as an in-cluster agent.
                    _set_token_context("", "")
                    await self.app(self._transport_scope(scope), receive, send)
                    return

                # Try HMAC temporary token. Temp tokens are issued by the
                # controller for a specific (workstream, job) pair.
                temp_result = _validate_temp_token(token_value)
                if temp_result is not None:
                    scopes, label, ws_id, job_id = temp_result
                    # Deferred imports to avoid circular dependency:
                    # workspace_map imports auth, so auth cannot import
                    # workspace_map at module load time.
                    from workspace_map import (  # noqa: PLC0415
                        _workspace_for_workstream,
                        _is_multi_workspace_mode,
                    )
                    workspace_id = _workspace_for_workstream(ws_id)
                    if workspace_id is None and _is_multi_workspace_mode():
                        await send({
                            "type": "http.response.start",
                            "status": 401,
                            "headers": [
                                [b"content-type", b"application/json"],
                                [b"www-authenticate", self._www_authenticate(scope)],
                            ],
                        })
                        await send({
                            "type": "http.response.body",
                            "body": b'{"error":"Unauthorized: workspace for temp-token workstream could not be resolved"}',
                        })
                        return
                    _set_scopes(scopes, label)
                    _set_workspace_scopes([workspace_id] if workspace_id else None)
                    _set_token_context(ws_id, job_id)
                    audit_log.info(
                        "temp_token_request "
                        "method=%s path=%s "
                        "workstream_id=%s job_id=%s workspace_id=%s",
                        scope.get("method", ""),
                        scope.get("path", ""),
                        ws_id or "", job_id or "",
                        workspace_id or "")
                    await self.app(self._transport_scope(scope), receive, send)
                    return

            # Reject: no valid token
            await send({
                "type": "http.response.start",
                "status": 401,
                "headers": [
                    [b"content-type", b"application/json"],
                    [b"www-authenticate", self._www_authenticate(scope)],
                ],
            })
            await send({
                "type": "http.response.body",
                "body": b'{"error":"Unauthorized: valid Bearer token required"}',
            })
            return

        # Non-HTTP scopes (lifespan, websocket) pass through
        await self.app(scope, receive, send)


class RateLimitMiddleware:
    """ASGI middleware implementing a per-client sliding-window rate limiter.

    The client key is the raw Bearer token (before auth validation) or the
    source IP for unauthenticated requests. Applied before auth so that
    brute-force token guessing is also rate-limited.
    """

    def __init__(self, app, requests_per_minute: int = 60):
        self.app = app
        self.rpm = requests_per_minute
        self.window = 60.0  # seconds
        self._buckets: dict = {}
        self._lock = threading.Lock()

    def _client_key(self, scope) -> str:
        """Extract a rate-limit key from the ASGI scope."""
        headers = dict(scope.get("headers", []))
        auth = headers.get(b"authorization", b"").decode("utf-8", errors="replace")
        if auth.startswith("Bearer "):
            return f"token:{auth[7:].strip()[:16]}"
        client = scope.get("client")
        if client:
            return f"ip:{client[0]}"
        return "unknown"

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        key = self._client_key(scope)
        now = time.monotonic()
        with self._lock:
            timestamps = self._buckets.get(key, [])
            cutoff = now - self.window
            timestamps = [t for t in timestamps if t > cutoff]
            if len(timestamps) >= self.rpm:
                self._buckets[key] = timestamps
                retry_after = int(self.window - (now - timestamps[0])) + 1
                await send({
                    "type": "http.response.start",
                    "status": 429,
                    "headers": [
                        [b"content-type", b"application/json"],
                        [b"retry-after", str(retry_after).encode()],
                    ],
                })
                await send({
                    "type": "http.response.body",
                    "body": b'{"error":"Rate limit exceeded. Try again later."}',
                })
                return
            timestamps.append(now)
            self._buckets[key] = timestamps

        await self.app(scope, receive, send)


class HealthMiddleware:
    """ASGI middleware that handles ``/_health`` before the wrapped app.

    Returns HTTP 200 for ``/_health`` requests so Docker/load-balancer
    health checks work without authentication.
    """

    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] == "http" and scope.get("path") == "/_health":
            await send({
                "type": "http.response.start",
                "status": 200,
                "headers": [[b"content-type", b"application/json"]],
            })
            await send({
                "type": "http.response.body",
                "body": b'{"status":"ok"}',
            })
            return
        await self.app(scope, receive, send)
