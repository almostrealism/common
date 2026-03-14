"""OAuth 2.1 Authorization Server middleware for AR Manager MCP.

Implements the minimum OAuth 2.1 profile required by the MCP specification
for remote server authentication:

- RFC 8414: Authorization Server Metadata Discovery
- RFC 7591: Dynamic Client Registration
- RFC 7636: PKCE (S256 only, mandatory per OAuth 2.1)
- Authorization Code grant type

Security model:
    - Users authenticate by entering a pre-configured bearer token on the
      authorization page. The token is validated with constant-time comparison.
    - PKCE is mandatory with S256 only (``plain`` is rejected per OAuth 2.1).
    - Authorization codes are cryptographically random, single-use, and expire
      after 60 seconds.
    - Redirect URIs are validated against the client's registered URIs to
      prevent open-redirect attacks.
    - The authorization page uses strict Content-Security-Policy headers and
      all dynamic values are HTML-escaped to prevent XSS.
    - Expired codes and stale client registrations are garbage-collected
      periodically.

Usage:
    Wrap the ASGI application with this middleware *outside*
    ``BearerAuthMiddleware`` so that OAuth endpoints are accessible without
    an existing bearer token::

        app = BearerAuthMiddleware(app, tokens)
        app = OAuthMiddleware(app, tokens)
"""

import base64
import hashlib
import hmac
import html
import json
import secrets
import threading
import time
from typing import Optional
from urllib.parse import parse_qs, urlencode, urlparse


# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

_CODE_LIFETIME_SECONDS = 60
_MAX_CLIENTS = 500
_MAX_PENDING_CODES = 2000
_CLIENT_STALE_SECONDS = 86400 * 30  # 30 days
_GC_INTERVAL_SECONDS = 300  # run GC at most every 5 minutes


# ---------------------------------------------------------------------------
# Authorization page HTML
# ---------------------------------------------------------------------------

_AUTHORIZE_PAGE = """\
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Authorize &mdash; AR Manager</title>
<style>
  *, *::before, *::after {{ box-sizing: border-box; }}
  body {{
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto,
                 Helvetica, Arial, sans-serif;
    background: #0d1117; color: #c9d1d9;
    display: flex; justify-content: center; align-items: center;
    min-height: 100vh; margin: 0; padding: 1rem;
  }}
  .card {{
    background: #161b22; border: 1px solid #30363d; border-radius: 12px;
    padding: 2rem; max-width: 420px; width: 100%;
  }}
  h1 {{ font-size: 1.25rem; margin: 0 0 0.25rem; color: #e6edf3; }}
  .client-name {{ color: #58a6ff; font-weight: 600; }}
  p {{ font-size: 0.9rem; line-height: 1.5; margin: 0.75rem 0; }}
  .scopes {{ color: #8b949e; font-size: 0.85rem; margin-bottom: 1.25rem; }}
  label {{ display: block; font-size: 0.85rem; margin-bottom: 0.35rem;
           color: #8b949e; }}
  input[type="password"] {{
    width: 100%; padding: 0.6rem 0.75rem; font-size: 1rem;
    background: #0d1117; color: #c9d1d9; border: 1px solid #30363d;
    border-radius: 6px; outline: none;
  }}
  input[type="password"]:focus {{ border-color: #58a6ff; }}
  .error {{ color: #f85149; font-size: 0.85rem; margin-top: 0.5rem; }}
  .buttons {{ display: flex; gap: 0.75rem; margin-top: 1.25rem; }}
  button {{
    flex: 1; padding: 0.6rem; font-size: 0.95rem; border: none;
    border-radius: 6px; cursor: pointer; font-weight: 500;
  }}
  .btn-approve {{ background: #238636; color: #fff; }}
  .btn-approve:hover {{ background: #2ea043; }}
  .btn-deny {{ background: #21262d; color: #c9d1d9; border: 1px solid #30363d; }}
  .btn-deny:hover {{ background: #30363d; }}
</style>
</head>
<body>
<div class="card">
  <h1>Authorize <span class="client-name">{client_name}</span></h1>
  <p>This application is requesting access to AR Manager.</p>
  <div class="scopes">Permissions: workstream management, task submission,
    pipeline control, memory access</div>
  {error_html}
  <form method="POST" autocomplete="off">
    <input type="hidden" name="response_type" value="{response_type}">
    <input type="hidden" name="client_id" value="{client_id}">
    <input type="hidden" name="redirect_uri" value="{redirect_uri}">
    <input type="hidden" name="state" value="{state}">
    <input type="hidden" name="code_challenge" value="{code_challenge}">
    <input type="hidden" name="code_challenge_method"
           value="{code_challenge_method}">
    <input type="hidden" name="scope" value="{scope}">
    <label for="token">Access Token</label>
    <input type="password" id="token" name="token"
           placeholder="armt_&hellip;" required>
    <div class="buttons">
      <button type="submit" name="action" value="deny" class="btn-deny">
        Deny</button>
      <button type="submit" name="action" value="approve" class="btn-approve">
        Authorize</button>
    </div>
  </form>
</div>
</body>
</html>
"""


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _s256(verifier: str) -> str:
    """Compute S256 PKCE code challenge from a code verifier."""
    digest = hashlib.sha256(verifier.encode("ascii")).digest()
    return base64.urlsafe_b64encode(digest).rstrip(b"=").decode("ascii")


async def _read_body(receive) -> bytes:
    """Read the full ASGI request body."""
    body = b""
    while True:
        message = await receive()
        body += message.get("body", b"")
        if not message.get("more_body", False):
            break
    return body


async def _json_response(send, status: int, data: dict,
                         extra_headers: Optional[list] = None):
    """Send a JSON HTTP response."""
    payload = json.dumps(data).encode("utf-8")
    headers = [
        [b"content-type", b"application/json"],
        [b"cache-control", b"no-store"],
        [b"pragma", b"no-cache"],
    ]
    if extra_headers:
        headers.extend(extra_headers)
    await send({
        "type": "http.response.start",
        "status": status,
        "headers": headers,
    })
    await send({"type": "http.response.body", "body": payload})


async def _html_response(send, status: int, body: str):
    """Send an HTML response with strict security headers."""
    csp = (
        "default-src 'none'; style-src 'unsafe-inline'; "
        "frame-ancestors 'none'"
    )
    await send({
        "type": "http.response.start",
        "status": status,
        "headers": [
            [b"content-type", b"text/html; charset=utf-8"],
            [b"cache-control", b"no-store"],
            [b"pragma", b"no-cache"],
            [b"content-security-policy", csp.encode("utf-8")],
            [b"x-content-type-options", b"nosniff"],
            [b"x-frame-options", b"DENY"],
            [b"referrer-policy", b"no-referrer"],
        ],
    })
    await send({"type": "http.response.body", "body": body.encode("utf-8")})


async def _redirect(send, location: str, status: int = 302):
    """Send an HTTP redirect."""
    await send({
        "type": "http.response.start",
        "status": status,
        "headers": [
            [b"location", location.encode("utf-8")],
            [b"cache-control", b"no-store"],
        ],
    })
    await send({"type": "http.response.body", "body": b""})


def _error_redirect(redirect_uri: str, error: str, state: str,
                    description: str = "") -> str:
    """Build an OAuth error redirect URL."""
    params = {"error": error, "state": state}
    if description:
        params["error_description"] = description
    sep = "&" if "?" in redirect_uri else "?"
    return redirect_uri + sep + urlencode(params)


# ---------------------------------------------------------------------------
# OAuthMiddleware
# ---------------------------------------------------------------------------

class OAuthMiddleware:
    """ASGI middleware implementing an OAuth 2.1 Authorization Server.

    Intercepts OAuth-related paths and passes all other requests through
    to the wrapped application unchanged.
    """

    OAUTH_PATHS = frozenset({
        "/.well-known/oauth-authorization-server",
        "/.well-known/oauth-protected-resource",
        "/oauth/register",
        "/oauth/authorize",
        "/oauth/token",
    })

    def __init__(self, app, tokens: list, issuer_url: Optional[str] = None):
        """
        Args:
            app: The wrapped ASGI application.
            tokens: Token definitions (same format as BearerAuthMiddleware).
                Each entry has ``value``, ``scopes``, and ``label`` keys.
            issuer_url: Public base URL of this server (e.g.
                ``https://myhost.tail1234.ts.net``). If not set, derived
                from the ``Host`` header of incoming requests.
        """
        self.app = app
        self.issuer_url = issuer_url.rstrip("/") if issuer_url else None

        # Build token lookup for validating credentials on the auth page
        self._bearer_tokens: list[tuple[str, list, str]] = []
        for t in tokens:
            value = t.get("value", "")
            scopes = t.get("scopes", [])
            label = t.get("label", "unlabeled")
            if value:
                self._bearer_tokens.append((value, scopes, label))

        # In-memory stores (acceptable for single-instance deployment)
        self._clients: dict[str, dict] = {}
        self._codes: dict[str, dict] = {}
        self._lock = threading.Lock()
        self._last_gc = 0.0

    # -- Routing -------------------------------------------------------------

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        path = scope.get("path", "")
        if path not in self.OAUTH_PATHS:
            await self.app(scope, receive, send)
            return

        method = scope.get("method", "GET")
        self._maybe_gc()

        if path == "/.well-known/oauth-authorization-server" and method == "GET":
            await self._handle_metadata(scope, send)
        elif path == "/.well-known/oauth-protected-resource" and method == "GET":
            await self._handle_protected_resource(scope, send)
        elif path == "/oauth/register" and method == "POST":
            await self._handle_register(receive, send)
        elif path == "/oauth/authorize":
            if method == "GET":
                await self._handle_authorize_get(scope, send)
            elif method == "POST":
                await self._handle_authorize_post(scope, receive, send)
            else:
                await _json_response(send, 405, {"error": "method_not_allowed"})
        elif path == "/oauth/token" and method == "POST":
            await self._handle_token(receive, send)
        else:
            await _json_response(send, 405, {"error": "method_not_allowed"})

    # -- Garbage collection --------------------------------------------------

    def _maybe_gc(self):
        """Periodically purge expired codes and stale clients."""
        now = time.time()
        if now - self._last_gc < _GC_INTERVAL_SECONDS:
            return
        with self._lock:
            self._last_gc = now
            # Expired authorization codes
            expired_codes = [
                k for k, v in self._codes.items()
                if v["expires_at"] < now
            ]
            for k in expired_codes:
                del self._codes[k]
            # Stale client registrations
            cutoff = now - _CLIENT_STALE_SECONDS
            stale_clients = [
                k for k, v in self._clients.items()
                if v.get("last_used", v["created_at"]) < cutoff
            ]
            for k in stale_clients:
                del self._clients[k]

    # -- Issuer URL ----------------------------------------------------------

    def _get_issuer(self, scope) -> str:
        """Return the issuer URL, deriving from the request if not configured."""
        if self.issuer_url:
            return self.issuer_url
        headers = dict(scope.get("headers", []))
        host = headers.get(b"host", b"localhost").decode("utf-8")
        # Assume HTTPS for any non-localhost host (Tailscale Funnel provides TLS)
        scheme = "http" if host.startswith("localhost") or host.startswith("127.") else "https"
        forwarded_proto = headers.get(b"x-forwarded-proto", b"").decode("utf-8")
        if forwarded_proto:
            scheme = forwarded_proto
        return f"{scheme}://{host}"

    # -- RFC 8414: Metadata --------------------------------------------------

    async def _handle_metadata(self, scope, send):
        """Serve OAuth 2.1 Authorization Server Metadata."""
        issuer = self._get_issuer(scope)
        metadata = {
            "issuer": issuer,
            "authorization_endpoint": f"{issuer}/oauth/authorize",
            "token_endpoint": f"{issuer}/oauth/token",
            "registration_endpoint": f"{issuer}/oauth/register",
            "response_types_supported": ["code"],
            "grant_types_supported": ["authorization_code"],
            "token_endpoint_auth_methods_supported": ["none"],
            "code_challenge_methods_supported": ["S256"],
            "scopes_supported": ["read", "write", "pipeline", "memory"],
        }
        await _json_response(send, 200, metadata)

    # -- RFC 9728: Protected Resource Metadata ---------------------------------

    async def _handle_protected_resource(self, scope, send):
        """Serve OAuth Protected Resource Metadata (RFC 9728).

        Tells the client which authorization server protects this resource.
        """
        issuer = self._get_issuer(scope)
        metadata = {
            "resource": issuer,
            "authorization_servers": [issuer],
            "bearer_methods_supported": ["header"],
            "scopes_supported": ["read", "write", "pipeline", "memory"],
        }
        await _json_response(send, 200, metadata)

    # -- RFC 7591: Dynamic Client Registration --------------------------------

    async def _handle_register(self, receive, send):
        """Register a new OAuth client."""
        body = await _read_body(receive)
        try:
            data = json.loads(body)
        except (json.JSONDecodeError, UnicodeDecodeError):
            await _json_response(send, 400, {
                "error": "invalid_client_metadata",
                "error_description": "Request body must be valid JSON.",
            })
            return

        redirect_uris = data.get("redirect_uris")
        if not redirect_uris or not isinstance(redirect_uris, list):
            await _json_response(send, 400, {
                "error": "invalid_client_metadata",
                "error_description": "redirect_uris is required and must be a list.",
            })
            return

        # Validate each redirect URI
        for uri in redirect_uris:
            if not isinstance(uri, str):
                await _json_response(send, 400, {
                    "error": "invalid_client_metadata",
                    "error_description": "Each redirect_uri must be a string.",
                })
                return
            parsed = urlparse(uri)
            if not parsed.scheme or not parsed.netloc:
                await _json_response(send, 400, {
                    "error": "invalid_client_metadata",
                    "error_description": f"Invalid redirect_uri: {uri}",
                })
                return

        client_id = secrets.token_urlsafe(24)
        client_name = data.get("client_name", "Unknown Client")
        if not isinstance(client_name, str):
            client_name = "Unknown Client"
        # Truncate to prevent abuse
        client_name = client_name[:128]

        with self._lock:
            # Evict oldest if at capacity
            if len(self._clients) >= _MAX_CLIENTS:
                oldest = min(self._clients,
                             key=lambda k: self._clients[k]["created_at"])
                del self._clients[oldest]
            self._clients[client_id] = {
                "redirect_uris": redirect_uris,
                "client_name": client_name,
                "created_at": time.time(),
                "last_used": time.time(),
            }

        await _json_response(send, 201, {
            "client_id": client_id,
            "client_name": client_name,
            "redirect_uris": redirect_uris,
            "grant_types": ["authorization_code"],
            "response_types": ["code"],
            "token_endpoint_auth_method": "none",
        })

    # -- Authorization Endpoint -----------------------------------------------

    def _parse_query(self, scope) -> dict:
        """Parse query string from ASGI scope into a dict of single values."""
        qs = scope.get("query_string", b"").decode("utf-8")
        parsed = parse_qs(qs, keep_blank_values=True)
        return {k: v[0] for k, v in parsed.items()}

    def _validate_authorize_params(self, params: dict) -> Optional[str]:
        """Validate authorization request parameters.

        Returns an error message string, or None if valid.
        """
        if params.get("response_type") != "code":
            return "response_type must be 'code'"
        if not params.get("client_id"):
            return "client_id is required"
        if not params.get("redirect_uri"):
            return "redirect_uri is required"
        if not params.get("code_challenge"):
            return "code_challenge is required (PKCE mandatory)"
        if params.get("code_challenge_method") != "S256":
            return "code_challenge_method must be 'S256'"
        return None

    def _validate_client_redirect(self, client_id: str,
                                  redirect_uri: str) -> Optional[str]:
        """Validate client_id exists and redirect_uri is registered.

        Returns an error message, or None if valid.
        """
        with self._lock:
            client = self._clients.get(client_id)
        if not client:
            return "Unknown client_id"
        if redirect_uri not in client["redirect_uris"]:
            return "redirect_uri does not match client registration"
        return None

    def _render_auth_page(self, params: dict, error: str = "") -> str:
        """Render the authorization HTML page with escaped values."""
        with self._lock:
            client = self._clients.get(params.get("client_id", ""), {})
        client_name = client.get("client_name", "Unknown Client")

        error_html = ""
        if error:
            error_html = f'<div class="error">{html.escape(error)}</div>'

        return _AUTHORIZE_PAGE.format(
            client_name=html.escape(client_name),
            response_type=html.escape(params.get("response_type", "")),
            client_id=html.escape(params.get("client_id", "")),
            redirect_uri=html.escape(params.get("redirect_uri", "")),
            state=html.escape(params.get("state", "")),
            code_challenge=html.escape(params.get("code_challenge", "")),
            code_challenge_method=html.escape(
                params.get("code_challenge_method", "")),
            scope=html.escape(params.get("scope", "")),
            error_html=error_html,
        )

    async def _handle_authorize_get(self, scope, send):
        """Show the authorization form."""
        params = self._parse_query(scope)

        # Validate parameters before showing the form
        error = self._validate_authorize_params(params)
        if error:
            await _html_response(send, 400, self._render_error_page(error))
            return

        error = self._validate_client_redirect(
            params["client_id"], params["redirect_uri"])
        if error:
            # Do NOT redirect — redirect_uri is untrusted at this point
            await _html_response(send, 400, self._render_error_page(error))
            return

        await _html_response(send, 200, self._render_auth_page(params))

    async def _handle_authorize_post(self, scope, receive, send):
        """Process the authorization form submission."""
        body = await _read_body(receive)
        form = parse_qs(body.decode("utf-8"), keep_blank_values=True)
        params = {k: v[0] for k, v in form.items()}

        # Re-validate all parameters (they come from hidden form fields and
        # could have been tampered with)
        error = self._validate_authorize_params(params)
        if error:
            await _html_response(send, 400, self._render_error_page(error))
            return

        client_id = params["client_id"]
        redirect_uri = params["redirect_uri"]
        state = params.get("state", "")

        error = self._validate_client_redirect(client_id, redirect_uri)
        if error:
            await _html_response(send, 400, self._render_error_page(error))
            return

        # Handle deny
        if params.get("action") == "deny":
            await _redirect(send, _error_redirect(
                redirect_uri, "access_denied", state,
                "The user denied the authorization request."))
            return

        # Validate the bearer token
        token_input = params.get("token", "")
        matched_scopes = None
        matched_label = None
        matched_value = None
        for stored_value, scopes, label in self._bearer_tokens:
            if hmac.compare_digest(
                token_input.encode("utf-8"),
                stored_value.encode("utf-8"),
            ):
                matched_scopes = scopes
                matched_label = label
                matched_value = stored_value
                break

        if matched_scopes is None:
            # Show the form again with an error
            await _html_response(
                send, 200,
                self._render_auth_page(params, error="Invalid access token."))
            return

        # Generate authorization code
        code = secrets.token_urlsafe(32)
        with self._lock:
            # Enforce code limit
            if len(self._codes) >= _MAX_PENDING_CODES:
                # Evict oldest expired or nearest-to-expiry
                oldest = min(self._codes,
                             key=lambda k: self._codes[k]["expires_at"])
                del self._codes[oldest]

            self._codes[code] = {
                "client_id": client_id,
                "redirect_uri": redirect_uri,
                "code_challenge": params["code_challenge"],
                "scopes": matched_scopes,
                "label": matched_label,
                "token_value": matched_value,
                "expires_at": time.time() + _CODE_LIFETIME_SECONDS,
            }

            # Touch client last_used
            if client_id in self._clients:
                self._clients[client_id]["last_used"] = time.time()

        # Redirect with authorization code
        redirect_params = {"code": code}
        if state:
            redirect_params["state"] = state
        sep = "&" if "?" in redirect_uri else "?"
        await _redirect(send, redirect_uri + sep + urlencode(redirect_params))

    # -- Token Endpoint -------------------------------------------------------

    async def _handle_token(self, receive, send):
        """Exchange an authorization code for an access token."""
        body = await _read_body(receive)
        form = parse_qs(body.decode("utf-8"), keep_blank_values=True)
        params = {k: v[0] for k, v in form.items()}

        # Validate grant_type
        if params.get("grant_type") != "authorization_code":
            await _json_response(send, 400, {
                "error": "unsupported_grant_type",
                "error_description": "Only 'authorization_code' is supported.",
            })
            return

        code = params.get("code", "")
        client_id = params.get("client_id", "")
        redirect_uri = params.get("redirect_uri", "")
        code_verifier = params.get("code_verifier", "")

        if not code or not client_id or not redirect_uri or not code_verifier:
            await _json_response(send, 400, {
                "error": "invalid_request",
                "error_description": "Missing required parameter: code, "
                                     "client_id, redirect_uri, code_verifier.",
            })
            return

        # Consume the code (single-use: remove before validation to prevent
        # replay even if validation fails partway through)
        with self._lock:
            code_entry = self._codes.pop(code, None)

        if code_entry is None:
            await _json_response(send, 400, {
                "error": "invalid_grant",
                "error_description": "Authorization code is invalid or expired.",
            })
            return

        # Validate expiry
        if code_entry["expires_at"] < time.time():
            await _json_response(send, 400, {
                "error": "invalid_grant",
                "error_description": "Authorization code has expired.",
            })
            return

        # Validate client_id
        if not hmac.compare_digest(client_id, code_entry["client_id"]):
            await _json_response(send, 400, {
                "error": "invalid_grant",
                "error_description": "client_id does not match.",
            })
            return

        # Validate redirect_uri
        if not hmac.compare_digest(redirect_uri, code_entry["redirect_uri"]):
            await _json_response(send, 400, {
                "error": "invalid_grant",
                "error_description": "redirect_uri does not match.",
            })
            return

        # Validate PKCE: S256(code_verifier) must equal code_challenge
        computed_challenge = _s256(code_verifier)
        if not hmac.compare_digest(
            computed_challenge, code_entry["code_challenge"]
        ):
            await _json_response(send, 400, {
                "error": "invalid_grant",
                "error_description": "PKCE code_verifier validation failed.",
            })
            return

        # Issue the bearer token as the access token
        scopes = code_entry["scopes"]
        await _json_response(send, 200, {
            "access_token": code_entry["token_value"],
            "token_type": "bearer",
            "scope": " ".join(scopes),
        })

    # -- Error page -----------------------------------------------------------

    @staticmethod
    def _render_error_page(message: str) -> str:
        """Render a standalone error page (used when we cannot redirect)."""
        escaped = html.escape(message)
        return f"""\
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Error &mdash; AR Manager</title>
<style>
  body {{
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto,
                 Helvetica, Arial, sans-serif;
    background: #0d1117; color: #c9d1d9;
    display: flex; justify-content: center; align-items: center;
    min-height: 100vh; margin: 0; padding: 1rem;
  }}
  .card {{
    background: #161b22; border: 1px solid #30363d; border-radius: 12px;
    padding: 2rem; max-width: 420px; width: 100%; text-align: center;
  }}
  h1 {{ font-size: 1.25rem; color: #f85149; margin: 0 0 1rem; }}
  p {{ font-size: 0.9rem; line-height: 1.5; }}
</style>
</head>
<body>
<div class="card">
  <h1>Authorization Error</h1>
  <p>{escaped}</p>
</div>
</body>
</html>
"""
