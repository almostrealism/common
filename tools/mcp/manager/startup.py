"""Server startup for ar-manager.

Split out of ``server.py`` to keep that file under the Python line cap. The
routine here validates the transport and auth configuration, refuses to serve
an incomplete tool surface, assembles the middleware stack and runs uvicorn.

Imported at the very bottom of ``server.py``, after the tool modules, so that
by the time :func:`main` can be called every tool has registered. Serving
before that point is the failure this module's surface check now catches.
"""

import os
import sys

import tool_capabilities
from auth import (
    _load_tokens,
    BearerAuthMiddleware,
    HealthMiddleware,
    RateLimitMiddleware,
)
from config import RATE_LIMIT
from server import mcp


def _verify_tool_surface():
    """Exit unless every classified tool is registered.

    A startup path that runs before ``server.py``'s trailing tool-module
    imports would advertise a fraction of the tools and look healthy doing
    it: ``/_health`` answers 200 from a static handler that cannot see the
    registry.
    """
    missing = tool_capabilities.unregistered(mcp._tool_manager._tools)
    if missing:
        shown = ", ".join(missing[:10])
        if len(missing) > 10:
            shown += f", and {len(missing) - 10} more"
        print(
            f"ar-manager: FATAL: {len(missing)} tool(s) were never registered "
            f"({shown}). Refusing to serve a partial tool surface.",
            file=sys.stderr,
        )
        sys.exit(1)
    print(f"ar-manager: {len(mcp._tool_manager._tools)} tools registered",
          file=sys.stderr)


def main():
    """Start the authenticated HTTP server."""
    # Default to http: ar-manager only runs as an authenticated HTTP/SSE
    # server, so http is the sole sensible default. An explicit
    # MCP_TRANSPORT=stdio (or anything else) is still rejected below.
    transport = os.environ.get("MCP_TRANSPORT", "http")
    tokens = _load_tokens()

    # ar-manager runs ONLY as an authenticated HTTP/SSE server. Both the
    # stdio transport and the former tokenless ("no-auth") mode are refused:
    # a request with no bearer token is indistinguishable from any other, so
    # the job / workspace / permission context an ar-manager token carries
    # would be silently discarded. Refuse to start rather than serve in a
    # mode where that context can be lost.
    if transport not in ("http", "sse"):
        print(
            f"ar-manager: FATAL: unsupported MCP_TRANSPORT={transport!r}. "
            "ar-manager runs only as an authenticated HTTP server; set "
            "MCP_TRANSPORT=http (or sse). Point interactive MCP clients at "
            "the public HTTPS endpoint instead of launching this file over "
            "stdio.",
            file=sys.stderr,
        )
        sys.exit(1)

    if not tokens:
        print(
            "ar-manager: FATAL: no auth tokens configured. Set "
            "AR_MANAGER_TOKENS or AR_MANAGER_TOKEN_FILE (see the README); "
            "ar-manager refuses to serve without authentication.",
            file=sys.stderr,
        )
        sys.exit(1)

    print(f"ar-manager: Auth enabled with {len(tokens)} token(s)",
          file=sys.stderr)

    _verify_tool_surface()

    port = int(os.environ.get("MCP_PORT", "8010"))

    # Wrap the MCP app with auth + rate-limiting middleware.
    # Serve MCP at "/" — Claude mobile ignores the path component and always
    # sends requests to the root.
    mcp.settings.streamable_http_path = "/"
    # Run the streamable-HTTP transport STATELESS so a client is not
    # required to echo the ``mcp-session-id`` from ``initialize`` on
    # follow-up requests. The default stateful transport rejects any
    # follow-up that omits the session id with 400 "Missing session
    # ID"; OpenAI's MCP client (ChatGPT) does not resend the id, so
    # its first post-initialize call fails and the OpenAI gateway
    # surfaces a 502. Stateless mode is safe here: every tool call
    # decodes its bearer from the request's own Authorization header
    # (see BearerAuthMiddleware) rather than from session-bound
    # context, and the tools are independent request/response RPCs
    # with no server-initiated streaming, so no per-session state is
    # lost. It is also strictly more lenient for every other client.
    mcp.settings.stateless_http = True
    # Disable DNS rebinding protection — the server runs behind a
    # TLS-terminating reverse proxy (Tailscale Funnel) where the
    # Host header is the public DNS name, not localhost.
    from mcp.server.transport_security import TransportSecuritySettings
    mcp.settings.transport_security = TransportSecuritySettings(
        enable_dns_rebinding_protection=False,
    )
    try:
        app = mcp.streamable_http_app()
    except AttributeError:
        # CRITICAL: If streamable_http_app() is unavailable we cannot
        # apply auth middleware. Refuse to start rather than silently
        # running without authentication.
        print(
            "ar-manager: FATAL: Cannot apply auth middleware — "
            "streamable_http_app() not available in this MCP version. "
            "Upgrade the mcp package.",
            file=sys.stderr,
        )
        sys.exit(1)

    # Middleware order (outermost first):
    #   Health -> RateLimit -> OAuth -> BearerAuth -> app
    # OAuth sits outside BearerAuth so its endpoints (metadata,
    # registration, authorize, token) are accessible without an
    # existing bearer token.
    from oauth import OAuthMiddleware
    issuer_url = os.environ.get("AR_MANAGER_ISSUER_URL")
    oauth_state_file = os.environ.get("AR_MANAGER_OAUTH_STATE_FILE")
    app = BearerAuthMiddleware(app, tokens, issuer_url=issuer_url)
    app = OAuthMiddleware(app, tokens, issuer_url=issuer_url,
                          state_file=oauth_state_file)
    app = RateLimitMiddleware(app, requests_per_minute=RATE_LIMIT)
    app = HealthMiddleware(app)

    # Warn if binding publicly without TLS
    print(f"ar-manager: Starting with auth on port {port}", file=sys.stderr)
    print(
        "ar-manager: WARNING: Listening on 0.0.0.0 without TLS. "
        "Bearer tokens will be transmitted in cleartext. "
        "Use a TLS-terminating reverse proxy for public deployments.",
        file=sys.stderr,
    )

    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=port)
