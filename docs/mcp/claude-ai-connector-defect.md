# claude.ai connector silently drops a fully-successful OAuth flow after token exchange — ChatGPT connects to the identical endpoint without issue

## TL;DR

A spec-compliant remote MCP server completes the **entire** OAuth 2.1 + PKCE handshake with claude.ai — including a clean `200` on `POST /oauth/token` — and then claude.ai **never sends a single authenticated request**. It silently fails *after* receiving a valid access token, before the first MCP `initialize` ever leaves Anthropic's backend.

**The same server, same URL, same OAuth flow, works flawlessly with ChatGPT's MCP connector.** We have verified our server, our TLS, our edge (Cloudflare), and our OAuth implementation are all correct end-to-end — including by replaying claude.ai's exact post-token request shape ourselves and getting a valid response. The only component that fails is claude.ai.

**We are migrating our MCP workloads to ChatGPT because of this.** It connects on the first try, every time, against the exact endpoint claude.ai cannot use. We would prefer to keep using both, but claude.ai's connector has been unusable for this server for weeks while ChatGPT has never failed once.

---

## Environment

- **Client:** claude.ai web connector (remote MCP / "custom connector")
- **Server:** custom remote MCP server, FastMCP streamable-HTTP transport, stateless
- **Public endpoint:** `https://mcp.almostrealism.ai/` (Cloudflare Tunnel → origin)
- **Transport:** streamable HTTP at root path `/`, `text/event-stream`
- **Auth:** OAuth 2.1, RFC 7591 Dynamic Client Registration, PKCE (S256), RFC 8414 + RFC 9728 discovery
- **MCP protocol version advertised:** `2025-06-18`
- **Support reference for the captured failure:** `ofid_6557475d91a783de`

---

## What claude.ai actually did (server access log — the flow behind `ofid_6557475d91a783de`)

Every request below was issued by claude.ai and **every one succeeded** on our side:

| Time (UTC) | Request | Status |
|---|---|---|
| 17:45:58.328 | `POST /` | `401 Unauthorized` (probe → auth challenge w/ `WWW-Authenticate` + `resource_metadata`) |
| 17:45:58.663 | `GET /.well-known/oauth-protected-resource` | `200` |
| 17:45:58.939 | `GET /.well-known/oauth-authorization-server` | `200` |
| 17:45:59.283 | `POST /oauth/register` | `201 Created` (client_id `ZAs1Kuz9_t45BUBLq2RIECSzL5i_N86V`) |
| 17:45:59.639 | `GET /oauth/authorize?...&resource=https%3A%2F%2Fmcp.almostrealism.ai%2F` | `200` |
| 17:46:09.157 | `POST /oauth/authorize` (user approves) | `302 Found` → `redirect_uri` with `code` + `state` |
| 17:46:09.852 | `POST /oauth/token` | **`200 OK`** |

The `redirect_uri` was `https://claude.ai/api/mcp/auth_callback`. PKCE `code_challenge_method=S256` validated against the stored challenge. The token response body was fully RFC 6749 §5.1 compliant:

```json
{
  "access_token": "<redacted>",
  "token_type": "Bearer",
  "expires_in": 31536000,
  "scope": "read write pipeline memory"
}
```

`scope` echoes exactly what claude.ai requested in the authorize call (`read write pipeline memory`).

### Then: complete silence

After `POST /oauth/token → 200` at `17:46:09`, **claude.ai issued zero further requests.** No authenticated `POST /`, no MCP `initialize`, nothing — not at the origin, and not even an inbound connection at the Cloudflare edge. The connector reports failure to the user (message below) while our server never sees another packet from it.

This is reproducible on every attempt. A prior attempt at `17:25:52–17:26:01 UTC` produced the identical trace and the identical silence after a `200` token exchange. Note that the two attempts registered **different** client IDs (`zYfBIFNq…` vs. `ZAs1Kuz9…`) — claude.ai performs a fresh dynamic registration each time, so this is not a stale-credential or client-cache problem.

---

## Proof the failure is *not* on our side

1. **The endpoint serves authenticated MCP correctly.** We replayed claude.ai's exact next step — the authenticated `initialize` — through the public edge, using the same headers the connector uses:

   ```
   POST https://mcp.almostrealism.ai/
   Authorization: Bearer <valid token>
   Accept: application/json, text/event-stream
   MCP-Protocol-Version: 2025-06-18
   Content-Type: application/json

   {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"diagnostic","version":"1.0"}}}
   ```

   Response:

   ```
   HTTP/2 200
   content-type: text/event-stream
   server: cloudflare

   event: message
   data: {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18","capabilities":{...},"serverInfo":{"name":"ar-manager","version":"..."}}}
   ```

2. **It traverses the same path claude.ai uses.** That request egressed the *same Cloudflare IP* through which claude.ai's own OAuth calls succeeded, and reached our origin. The edge is demonstrably forwarding claude.ai's MCP POSTs — claude.ai's initial probe `POST /` is exactly how it received the `401` that kicked off the OAuth flow.

3. **ChatGPT connects to this identical URL with this identical OAuth flow and works perfectly** — discovery, registration, authorize, token exchange, and then it actually sends `initialize` and uses the tools. claude.ai stops one step short: it gets the token and never makes the call.

There is no server bug to fix here. We have eliminated TLS, DNS, the edge, the OAuth handshake, the token-response shape, the transport path, and the SSE framing as causes — each is verified working against a real MCP client (ChatGPT) and against a direct replay.

---

## The failure message claude.ai shows

> Authorization with the MCP server failed. You can check your credentials and permissions. If this persists, share this reference with support: `ofid_6557475d91a783de`

Note the irony of this message: it tells the user to "check your credentials and permissions" for an authorization that **succeeded** — your own backend issued `POST /oauth/token` and received a `200` with a valid bearer token (see the access-log table above). The credentials are valid; claude.ai simply never uses them. The reference `ofid_6557475d91a783de` corresponds to the `17:45:58–17:46:09 UTC` flow on `2026-05-26` documented above; your team should be able to trace it on the backend.

---

## Expected vs. actual

- **Expected:** After a `200` token exchange, claude.ai sends `POST /` with `Authorization: Bearer <token>` and an `initialize` request, then connects.
- **Actual:** claude.ai obtains a valid token, sends nothing further, and reports a connection failure to the user. The failure occurs entirely inside Anthropic's backend.

---

## What we need

1. Confirmation of what claude.ai's backend does between receiving the `200` token response and issuing `initialize`, and where that step fails.
2. Any server-side response requirement claude.ai enforces that is **not** part of RFC 6749 / RFC 8414 / RFC 9728 / the MCP authorization spec — if there is an undocumented expectation, please document it.
3. A backend trace of `ofid_6557475d91a783de` against the `17:45:58–17:46:09 UTC` (2026-05-26) token exchange above.

We are happy to run any diagnostic you ask for. But to be direct: this has cost us weeks, ChatGPT has never once failed against this server, and our workloads are moving there. We are filing this because we would rather not have to.
