#!/usr/bin/env python3
"""
Best-effort validation of the SA3 remap config against the REAL released key set.

This test fetches only the *key map* of the released Stable Audio 3 checkpoint --
never the multi-GB tensor payloads -- and asserts that the keys the SA3 remap
config expects are actually present. It catches drift between our config and
Stability's released layout.

It is gated and MUST NOT fail the build when unavailable:

* If no Hugging Face token is found (env ``HF_TOKEN`` / ``HUGGING_FACE_HUB_TOKEN``
  or ``~/.cache/huggingface/token``) the test is skipped.
* If Hugging Face is unreachable, or the repo's key map cannot be read, the test
  is skipped with a clear message.

The released DiT repo is a single ``model.safetensors`` (no
``model.safetensors.index.json``), so the key set is read from the safetensors
*header*: a little-endian uint64 length at byte 0 followed by that many bytes of
JSON ``{name: {dtype, shape, data_offsets}}``. Two small HTTP Range requests
fetch just the header. The released DiT checkpoint also embeds the SAME-S
autoencoder under ``pretransform.model.*``, so both the DiT and embedded-AE
expected key sets are validated from this one header.
"""

import json
import os
import struct
import sys
import urllib.error
import urllib.request

import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import extract_sa3_weights as sa3

DIT_REPO = "stabilityai/stable-audio-3-small-music"
SAFETENSORS_URL = (
    "https://huggingface.co/%s/resolve/main/model.safetensors" % DIT_REPO)
TIMEOUT = 30


def _find_token():
    for env in ("HF_TOKEN", "HUGGING_FACE_HUB_TOKEN", "HUGGINGFACE_TOKEN"):
        value = os.environ.get(env)
        if value:
            return value.strip()
    for path in (
        os.path.expanduser("~/.cache/huggingface/token"),
        "/Users/worker/.cache/huggingface/token",
    ):
        if os.path.isfile(path):
            with open(path) as f:
                token = f.read().strip()
            if token:
                return token
    return None


def _range(url, token, start, end):
    request = urllib.request.Request(
        url, headers={"Authorization": "Bearer %s" % token,
                      "Range": "bytes=%d-%d" % (start, end)})
    # Use a context manager so the response (and its underlying socket/fd) is
    # closed promptly, even when the test is run repeatedly in-process.
    with urllib.request.urlopen(request, timeout=TIMEOUT) as resp:
        return resp.read()


def _fetch_safetensors_keys(url, token):
    """Return the set of tensor keys from a remote safetensors header."""
    size_bytes = _range(url, token, 0, 7)
    header_len = struct.unpack("<Q", size_bytes)[0]
    header_json = _range(url, token, 8, 8 + header_len - 1)
    header = json.loads(header_json.decode("utf-8"))
    return {k for k in header.keys() if k != "__metadata__"}


@pytest.fixture(scope="module")
def released_keys():
    token = _find_token()
    if not token:
        pytest.skip("No Hugging Face token available; skipping real-key validation.")
    try:
        keys = _fetch_safetensors_keys(SAFETENSORS_URL, token)
    except urllib.error.HTTPError as exc:
        pytest.skip("Hugging Face returned HTTP %s for %s; skipping."
                    % (exc.code, DIT_REPO))
    except (urllib.error.URLError, OSError, ValueError) as exc:
        pytest.skip("Could not read released key map (%s); skipping." % exc)
    if not keys:
        pytest.skip("Released key map was empty; skipping.")
    return keys


def test_dit_expected_keys_present(released_keys):
    """Every DiT input key the SA3 config expects exists in the released repo."""
    missing = sorted(k for k in sa3.SA3_DIT_EXPECTED_KEYS if k not in released_keys)
    assert not missing, (
        "SA3 DiT remap config expects keys absent from the released checkpoint "
        "(layout drift?): %s" % missing)


def test_ae_expected_keys_present(released_keys):
    """Every embedded-AE input key the SA3 config expects exists in the repo."""
    missing = sorted(k for k in sa3.SA3_AE_EXPECTED_KEYS if k not in released_keys)
    assert not missing, (
        "SA3 AE remap config expects keys absent from the released checkpoint "
        "(layout drift?): %s" % missing)


def test_dit_select_rule_matches_real_namespace(released_keys):
    """The DiT select rule captures a non-trivial slice of the real keys."""
    selected = {k for k in released_keys if k.startswith(sa3.DIT_PREFIX)}
    embedded_ae = {k for k in released_keys if k.startswith(sa3.AE_EMBEDDED_PREFIX)}
    assert selected, "no model.model.* keys found in released checkpoint"
    assert embedded_ae, "no pretransform.model.* (embedded AE) keys found"
    # The DiT selection must exclude the embedded AE and conditioner keys.
    assert selected.isdisjoint(embedded_ae)


if __name__ == "__main__":
    sys.exit(pytest.main([__file__, "-v"]))
