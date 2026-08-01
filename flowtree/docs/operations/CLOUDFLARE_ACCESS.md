# Exposing the flowtree controller via Cloudflare Tunnel + Access

How to make a locally-running flowtree controller reachable by a remote caller over a **public
Cloudflare hostname**, with **Cloudflare Access service-token** auth at the edge.

The controller HTTP API has no authentication of its own. Rather than add auth to flowtree,
Cloudflare Access fronts the hostname: only requests carrying a valid service token
(`CF-Access-Client-Id` / `CF-Access-Client-Secret`) reach the controller; everything else is
blocked. The same pattern works for any caller — a hostname plus a service token, with no per-caller
network sidecar.

## Assumed already set up

- The flowtree controller is running locally with its HTTP API on `127.0.0.1:7780`
  (`FlowTreeApiEndpoint.DEFAULT_PORT`).
- `cloudflared` is installed and authenticated (`cloudflared tunnel login`).
- A Cloudflare account with a zone (your domain) and **Zero Trust** enabled.
- A hostname to use, referred to below as `flowtree.<your-domain>`.

## 1. Create the tunnel and route the hostname (CLI)

```bash
cloudflared tunnel create flowtree-controller          # prints a TUNNEL_UUID + writes a creds JSON
cloudflared tunnel route dns flowtree-controller flowtree.<your-domain>
```

## 2. Tunnel config — `~/.cloudflared/config.yml`

```yaml
tunnel: <TUNNEL_UUID>
credentials-file: /home/<user>/.cloudflared/<TUNNEL_UUID>.json
ingress:
  - hostname: flowtree.<your-domain>
    service: http://127.0.0.1:7780
  - service: http_status:404
```

## 3. Run the tunnel (CLI)

```bash
cloudflared tunnel run flowtree-controller
# (production: `sudo cloudflared service install` to run it as a managed service)
```

After this the hostname resolves but is **public** — do step 4 before sending anything real to it.

## 4. Protect the hostname with Cloudflare Access (Zero Trust dashboard)

Dashboard → **Zero Trust → Access → Applications → Add an application → Self-hosted**:

- **Application domain:** `flowtree.<your-domain>` (the whole host).
- **Policy:** Action = **Service Auth**; Include = **Service Token** (select the token from step 5,
  or "Any Access Service Token"). Add **no** user-allow policy — with only a Service Auth policy,
  any request lacking a valid service token is rejected, so the controller is not open to the
  internet.

> Automation alternative: the Cloudflare API / Terraform `cloudflare_access_application` +
> `cloudflare_access_policy` (+ `cloudflare_access_service_token`) resources create the same thing
> if the agent has a Cloudflare API token.

## 5. Create the service token (Zero Trust dashboard)

**Zero Trust → Access → Service Auth → Service Tokens → Create**. Copy:

- **Client ID** (ends in `.access`)
- **Client Secret** (shown once — store it now)

Ensure the application policy from step 4 includes this token.

## 6. Verify

```bash
# Authorized — should reach flowtree's health endpoint:
curl -sS https://flowtree.<your-domain>/api/health \
  -H "CF-Access-Client-Id: <CLIENT_ID>" \
  -H "CF-Access-Client-Secret: <CLIENT_SECRET>"

# Unauthorized — should NOT reach flowtree: Cloudflare returns an Access challenge (HTTP 302/403),
# proving the controller is not publicly exposed:
curl -sS -o /dev/null -w "%{http_code}\n" https://flowtree.<your-domain>/api/health
```

## 7. Configure the caller

Give the calling service three things:

- the hostname, `https://flowtree.<your-domain>`, as its controller base URL, and
- the service-token **Client ID** and **Client Secret**.

The caller must send the Client ID/Secret as the `CF-Access-Client-Id` / `CF-Access-Client-Secret`
headers on every request to the controller; Cloudflare Access then admits it.

## Notes

- The flowtree API itself stays unauthenticated; Access does the auth at the edge.
- The tunnel is **outbound** from the local machine — no inbound firewall/NAT changes.
- Keep the Client Secret secret on the caller's side. Rotate by issuing a new service token and
  updating the caller's configured values.
- To bring another caller into the ecosystem, repeat with a new hostname + service token; no
  per-caller sidecar is required.
