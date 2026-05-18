#!/usr/bin/env python3
"""
AR Secrets MCP Server

A small stdio MCP server that runs in the agent's container and renders
workspace secrets into local files. Unlike the workspace_secret_* tools on
the centralized ar-manager HTTP server, this server runs in the same
filesystem namespace as the agent — so the file it writes is the file the
agent then reads.

The server is intentionally narrow: it exposes only the two operations
needed to stage credentials before invoking a downstream CLI (AWS, gh,
mosaic-login, etc.). Secret payloads are fetched from the FlowTree
controller over HTTP and never returned over the MCP channel.

Configuration via environment variables:
    AR_CONTROLLER_URL   FlowTree controller base URL.
    AR_WORKSTREAM_ID    Workstream identifier; appended as the
                        ``workstream_id`` query parameter on every
                        controller call and must match the workstream
                        embedded in ``AR_MANAGER_TOKEN``.
    AR_MANAGER_TOKEN    HMAC ``armt_tmp_*`` bearer token. The same
                        token already issued for the job's ar-manager
                        HTTP session is reused here.

The server refuses to start when any of these is missing — running
without authentication context would either expose all secrets or fail
silently on every call.
"""

import base64
import json
import logging
import os
import re
import sys
import tempfile
from typing import Optional
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import Request, urlopen

from mcp.server.fastmcp import FastMCP


CONTROLLER_URL = os.environ.get("AR_CONTROLLER_URL", "").strip()
WORKSTREAM_ID = os.environ.get("AR_WORKSTREAM_ID", "").strip()
MANAGER_TOKEN = os.environ.get("AR_MANAGER_TOKEN", "").strip()

audit_log = logging.getLogger("ar-secrets.audit")
audit_log.setLevel(logging.INFO)
if not audit_log.handlers:
    _handler = logging.StreamHandler(sys.stderr)
    _handler.setFormatter(logging.Formatter("%(asctime)s %(message)s"))
    audit_log.addHandler(_handler)


def _token_workstream_id(token: str) -> Optional[str]:
    """Extract the workstream id embedded in an ``armt_tmp_*`` token payload.

    The token format is ``armt_tmp_{hmac_b64}:{payload_b64}`` where the
    payload is ``{workstream_id}:{job_id}:{expiry_epoch}``. This function
    decodes the payload without verifying the HMAC — verification happens
    on the controller. The result is used only for an early local sanity
    check that the configured workstream id matches the token.

    Args:
        token: The bearer token value.

    Returns:
        The workstream id, or ``None`` when the token shape is invalid.
    """
    if not token.startswith("armt_tmp_"):
        return None
    rest = token[len("armt_tmp_"):]
    parts = rest.split(":", 1)
    if len(parts) != 2:
        return None
    payload_b64 = parts[1]
    pad = (4 - len(payload_b64) % 4) % 4
    try:
        payload = base64.urlsafe_b64decode(payload_b64 + "=" * pad).decode("utf-8")
    except Exception:
        return None
    payload_parts = payload.split(":")
    if len(payload_parts) != 3:
        return None
    return payload_parts[0]


def _validate_startup_config() -> Optional[str]:
    """Return an error string when required environment is missing or
    inconsistent, otherwise ``None``.
    """
    if not CONTROLLER_URL:
        return "AR_CONTROLLER_URL is not set"
    if not WORKSTREAM_ID:
        return "AR_WORKSTREAM_ID is not set"
    if not MANAGER_TOKEN:
        return "AR_MANAGER_TOKEN is not set"
    token_ws = _token_workstream_id(MANAGER_TOKEN)
    if token_ws is not None and token_ws != WORKSTREAM_ID:
        return (
            f"AR_MANAGER_TOKEN workstream {token_ws!r} does not match "
            f"AR_WORKSTREAM_ID {WORKSTREAM_ID!r}"
        )
    return None


_startup_error = _validate_startup_config()

print(f"ar-secrets: AR_CONTROLLER_URL={CONTROLLER_URL or '<unset>'}", file=sys.stderr)
print(f"ar-secrets: AR_WORKSTREAM_ID={WORKSTREAM_ID or '<unset>'}", file=sys.stderr)
print(
    "ar-secrets: AR_MANAGER_TOKEN=" + ("<set>" if MANAGER_TOKEN else "<unset>"),
    file=sys.stderr,
)
if _startup_error:
    print(f"ar-secrets: WARNING: {_startup_error}", file=sys.stderr)


def _controller_get(path: str, timeout: int = 10) -> dict:
    """GET a JSON resource from the FlowTree controller using the
    configured bearer token.

    Args:
        path: URL path to fetch (e.g. ``/api/secrets/foo?workstream_id=...``).
        timeout: Per-request timeout in seconds.

    Returns:
        The parsed JSON response on success, or a ``{"ok": false, ...}``
        error envelope when the call fails.
    """
    url = CONTROLLER_URL.rstrip("/") + path
    req = Request(url, headers={
        "Accept": "application/json",
        "Authorization": f"Bearer {MANAGER_TOKEN}",
    })
    try:
        with urlopen(req, timeout=timeout) as resp:
            body = resp.read().decode("utf-8")
            return json.loads(body) if body else {}
    except HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")
        try:
            return json.loads(body)
        except json.JSONDecodeError:
            return {"ok": False, "error": f"Controller returned HTTP {e.code}"}
    except URLError as e:
        return {"ok": False, "error": f"Controller unreachable: {e.reason}"}


mcp = FastMCP("ar-secrets")


@mcp.tool()
def secret_list_names() -> dict:
    """List the names of secrets accessible to this workstream's workspace.

    Returns only names — payload values are never disclosed. Use this to
    discover available secrets before calling :func:`secret_render_file`.

    Returns:
        dict with ``ok=True`` and a ``names`` list, or ``ok=False`` with
        an ``error`` string describing the failure.
    """
    if _startup_error:
        return {"ok": False, "error": _startup_error}

    audit_log.info(
        "tool=secret_list_names workstream_id=%s",
        WORKSTREAM_ID,
    )

    path = f"/api/secrets?workstream_id={quote(WORKSTREAM_ID, safe='')}"
    resp = _controller_get(path)
    if resp.get("ok") is False:
        return {"ok": False, "error": resp.get("error", "controller error")}
    return {"ok": True, "names": resp.get("names", [])}


@mcp.tool()
def secret_render_file(
    secret_name: str,
    template: str,
    output_path: str,
    mode: str = "0600",
) -> dict:
    """Fetch a workspace secret and render it into a file.

    The agent supplies a template with ``{{key}}`` placeholders. The secret
    payload is fetched from the controller, the placeholders are substituted
    locally, and the rendered content is written to ``output_path`` in the
    agent's filesystem. The rendered content is never included in the
    response.

    Every ``{{key}}`` referenced in the template must exist in the secret
    payload — a missing key aborts the operation and no file is written.
    Extra keys present in the payload but not referenced by the template
    are silently ignored, so a single secret may carry more keys than any
    one template needs.

    The write is atomic: a temporary sibling file is fsync'd and renamed
    over ``output_path``, so a concurrent reader never observes a partial
    file.

    Args:
        secret_name: Name of the secret to fetch.
        template: Template string with ``{{key}}`` placeholders.
        output_path: Destination file path. ``~`` is expanded.
        mode: Octal file permissions string (default ``"0600"``).

    Returns:
        dict with ``ok=True`` and the resolved ``output_path`` on success,
        or ``ok=False`` with an ``error`` string. The rendered content is
        never returned.
    """
    if _startup_error:
        return {"ok": False, "error": _startup_error}

    audit_log.info(
        "tool=secret_render_file workstream_id=%s secret_name=%s output_path=%s mode=%s",
        WORKSTREAM_ID, secret_name, output_path, mode,
    )

    if not isinstance(mode, str) or not mode:
        return {"ok": False, "error": "mode must be a non-empty octal string"}
    mode_digits = mode[1:] if mode.startswith("0") and len(mode) > 1 else mode
    if not mode_digits or any(c not in "01234567" for c in mode_digits):
        return {"ok": False, "error": f"mode must be octal digits 0-7 (got {mode!r})"}
    try:
        file_mode = int(mode, 8)
    except ValueError:
        return {"ok": False, "error": f"Invalid octal mode: {mode!r}"}
    if file_mode < 0 or file_mode > 0o777:
        return {"ok": False, "error": f"mode out of range — must be 0-0777 (got {mode!r})"}

    path = (
        f"/api/secrets/{quote(secret_name, safe='')}"
        f"?workstream_id={quote(WORKSTREAM_ID, safe='')}"
    )
    resp = _controller_get(path)
    if resp.get("ok") is False:
        return {"ok": False, "error": resp.get("error", "controller error")}

    payload = resp.get("payload", {})
    if not isinstance(payload, dict):
        return {"ok": False, "error": "Controller returned malformed payload"}

    placeholders = re.findall(r"\{\{(\w+)\}\}", template)
    missing = [p for p in placeholders if p not in payload]
    if missing:
        return {
            "ok": False,
            "error": (
                f"Template references unknown keys: {missing}. "
                f"Available keys: {sorted(payload.keys())}"
            ),
        }

    rendered = template
    for key in placeholders:
        rendered = rendered.replace(f"{{{{{key}}}}}", str(payload[key]))

    expanded = os.path.expanduser(output_path)
    parent = os.path.dirname(expanded) or "."
    os.makedirs(parent, exist_ok=True)
    rendered_bytes = rendered.encode("utf-8")
    tmp_fd, tmp_path = tempfile.mkstemp(
        prefix=os.path.basename(expanded) + ".",
        suffix=".tmp",
        dir=parent,
    )
    try:
        with os.fdopen(tmp_fd, "wb") as tmp_fh:
            tmp_fh.write(rendered_bytes)
            tmp_fh.flush()
            os.fsync(tmp_fh.fileno())
        os.chmod(tmp_path, file_mode)
        os.replace(tmp_path, expanded)
    except Exception:
        try:
            os.remove(tmp_path)
        except OSError:
            pass
        raise

    try:
        written = os.stat(expanded).st_size
    except OSError as e:
        return {"ok": False, "error": f"Write succeeded but stat failed: {e}"}
    if written != len(rendered_bytes):
        return {
            "ok": False,
            "error": (
                f"Post-write size mismatch — expected {len(rendered_bytes)} "
                f"bytes, found {written}"
            ),
        }

    audit_log.info(
        "tool=secret_render_file workstream_id=%s secret_name=%s output_path=%s "
        "bytes=%d result=OK",
        WORKSTREAM_ID, secret_name, expanded, written,
    )
    return {"ok": True, "output_path": expanded}


if __name__ == "__main__":
    mcp.run()
