"""
HTTP client helpers for communicating with the FlowTree controller.

All communication between ar-manager and the controller routes through
the two functions here.  They read CONTROLLER_URL from config.py so they
can be imported by workspace_map.py without depending on server.py.

Extracted from server.py to keep individual modules manageable.
"""

import json
import logging
import sys
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from config import CONTROLLER_URL

_log = logging.getLogger("ar-manager")


def _controller_get(path: str, timeout: int = 10, auth_token: str = None) -> dict:
    """GET a JSON resource from the FlowTree controller.

    Args:
        path: URL path (e.g., ``/api/health``).
        timeout: Request timeout in seconds.
        auth_token: Optional Bearer token for the Authorization header.

    Returns:
        Parsed JSON response as a dict.
    """
    url = CONTROLLER_URL.rstrip("/") + path
    headers = {"Accept": "application/json"}
    if auth_token:
        headers["Authorization"] = f"Bearer {auth_token}"
    req = Request(url, headers=headers)
    print(f"ar-manager: GET {url}", file=sys.stderr)
    _urlopen = getattr(sys.modules.get('server'), 'urlopen', None) or urlopen
    try:
        with _urlopen(req, timeout=timeout) as resp:
            body = resp.read().decode("utf-8")
            return json.loads(body) if body else {}
    except HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")
        _log.error("Controller GET %s: HTTP %d: %s", path, e.code, body[:500])
        try:
            return json.loads(body)
        except json.JSONDecodeError:
            return {"ok": False, "error": f"Controller returned HTTP {e.code}"}
    except URLError as e:
        return {"ok": False, "error": f"Controller unreachable: {e.reason}"}
    except Exception as e:
        _log.error("Controller GET %s: %s", path, e)
        return {"ok": False, "error": "Internal error contacting controller"}


def _controller_post(path: str, payload: dict, timeout: int = 15) -> dict:
    """POST a JSON payload to the FlowTree controller.

    Args:
        path: URL path (e.g., ``/api/submit``).
        payload: Dictionary to JSON-encode as the request body.
        timeout: Request timeout in seconds.

    Returns:
        Parsed JSON response as a dict.
    """
    url = CONTROLLER_URL.rstrip("/") + path
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = Request(
        url,
        data=data,
        headers={"Content-Type": "application/json; charset=utf-8"},
    )
    print(f"ar-manager: POST {url}", file=sys.stderr)
    _urlopen = getattr(sys.modules.get('server'), 'urlopen', None) or urlopen
    try:
        with _urlopen(req, timeout=timeout) as resp:
            body = resp.read().decode("utf-8")
            return json.loads(body) if body else {"ok": True}
    except HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")
        _log.error("Controller POST %s: HTTP %d: %s", path, e.code, body[:500])
        try:
            return json.loads(body)
        except json.JSONDecodeError:
            return {"ok": False, "error": f"Controller returned HTTP {e.code}"}
    except URLError as e:
        return {"ok": False, "error": f"Controller unreachable: {e.reason}"}
    except Exception as e:
        _log.error("Controller POST %s: %s", path, e)
        return {"ok": False, "error": "Internal error contacting controller"}
