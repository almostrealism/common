"""
HTTP client helpers for communicating with the AR Tracker service.

All tracker REST calls route through the five functions here.  They read
TRACKER_URL and the optional auth token from config.py so workstream tools
(e.g. workstream_delete) can import them independently of server.py.

Extracted from server.py to keep individual modules manageable.
"""

import json
import logging
import sys
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from config import TRACKER_URL, _TRACKER_AUTH_TOKEN

_log = logging.getLogger("ar-manager")


def _tracker_headers() -> dict:
    """Return the common HTTP headers for ar-tracker requests."""
    h = {"Accept": "application/json"}
    if _TRACKER_AUTH_TOKEN:
        h["Authorization"] = f"Bearer {_TRACKER_AUTH_TOKEN}"
    return h


def _tracker_get(path: str, timeout: int = 10) -> dict:
    """GET a JSON resource from the ar-tracker service."""
    url = TRACKER_URL.rstrip("/") + path
    req = Request(url, headers=_tracker_headers())
    print(f"ar-manager: TRACKER GET {url}", file=sys.stderr)
    try:
        with urlopen(req, timeout=timeout) as resp:
            body = resp.read().decode("utf-8")
            return json.loads(body) if body else {}
    except HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")
        try:
            return json.loads(body)
        except json.JSONDecodeError:
            return {"ok": False, "error": f"Tracker returned HTTP {e.code}"}
    except URLError as e:
        return {"ok": False, "error": f"Tracker unreachable: {e.reason}"}
    except Exception as e:
        _log.error("Tracker GET %s: %s", path, e)
        return {"ok": False, "error": "Internal error contacting tracker"}


def _tracker_post(path: str, payload: dict, timeout: int = 15) -> dict:
    """POST a JSON payload to the ar-tracker service."""
    url = TRACKER_URL.rstrip("/") + path
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    h = _tracker_headers()
    h["Content-Type"] = "application/json; charset=utf-8"
    req = Request(url, data=data, headers=h)
    print(f"ar-manager: TRACKER POST {url}", file=sys.stderr)
    try:
        with urlopen(req, timeout=timeout) as resp:
            body = resp.read().decode("utf-8")
            return json.loads(body) if body else {"ok": True}
    except HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")
        try:
            return json.loads(body)
        except json.JSONDecodeError:
            return {"ok": False, "error": f"Tracker returned HTTP {e.code}"}
    except URLError as e:
        return {"ok": False, "error": f"Tracker unreachable: {e.reason}"}
    except Exception as e:
        _log.error("Tracker POST %s: %s", path, e)
        return {"ok": False, "error": "Internal error contacting tracker"}


def _tracker_put(path: str, payload: dict, timeout: int = 15) -> dict:
    """PUT a JSON payload to the ar-tracker service."""
    url = TRACKER_URL.rstrip("/") + path
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    h = _tracker_headers()
    h["Content-Type"] = "application/json; charset=utf-8"
    req = Request(url, data=data, headers=h, method="PUT")
    print(f"ar-manager: TRACKER PUT {url}", file=sys.stderr)
    try:
        with urlopen(req, timeout=timeout) as resp:
            body = resp.read().decode("utf-8")
            return json.loads(body) if body else {"ok": True}
    except HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")
        try:
            return json.loads(body)
        except json.JSONDecodeError:
            return {"ok": False, "error": f"Tracker returned HTTP {e.code}"}
    except URLError as e:
        return {"ok": False, "error": f"Tracker unreachable: {e.reason}"}
    except Exception as e:
        _log.error("Tracker PUT %s: %s", path, e)
        return {"ok": False, "error": "Internal error contacting tracker"}


def _tracker_delete(path: str, timeout: int = 10) -> dict:
    """DELETE a resource from the ar-tracker service."""
    url = TRACKER_URL.rstrip("/") + path
    req = Request(url, headers=_tracker_headers(), method="DELETE")
    print(f"ar-manager: TRACKER DELETE {url}", file=sys.stderr)
    try:
        with urlopen(req, timeout=timeout) as resp:
            body = resp.read().decode("utf-8")
            return json.loads(body) if body else {"ok": True}
    except HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")
        try:
            return json.loads(body)
        except json.JSONDecodeError:
            return {"ok": False, "error": f"Tracker returned HTTP {e.code}"}
    except URLError as e:
        return {"ok": False, "error": f"Tracker unreachable: {e.reason}"}
    except Exception as e:
        _log.error("Tracker DELETE %s: %s", path, e)
        return {"ok": False, "error": "Internal error contacting tracker"}
