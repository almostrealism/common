# Copyright 2026 Michael Murray
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Tests for validate_agent_volume_isolation.

Run with:  python -m pytest tools/ci/test_validate_agent_volume_isolation.py
"""

import os

import yaml

import validate_agent_volume_isolation as v

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
REAL_COMPOSE = os.path.join(REPO_ROOT, "flowtree", "runtime", "agent", "docker-compose.yml")


def _compose(yaml_text):
    return yaml.safe_load(yaml_text)


# --- The real compose file must always pass. ---

def test_real_agent_compose_is_clean():
    assert v.validate_file(REAL_COMPOSE) == []


# --- A per-agent isolated config (the sanctioned pattern) passes. ---

ISOLATED = """
services:
  agent-1:
    build: {dockerfile: flowtree/runtime/agent/Dockerfile}
    volumes:
      - "/host/ssh:/home/agent/.ssh:ro"
      - "/host/models:/models:ro"
      - "/host/transcripts/agent-1:/agent-transcripts:rw"
  agent-2:
    build: {dockerfile: flowtree/runtime/agent/Dockerfile}
    volumes:
      - "/host/ssh:/home/agent/.ssh:ro"
      - "/host/models:/models:ro"
      - "/host/transcripts/agent-2:/agent-transcripts:rw"
"""


def test_per_agent_isolated_transcript_dirs_pass():
    assert v.find_violations(_compose(ISOLATED)) == []


# --- A single shared writable transcript dir is the exact mistake to catch. ---

SHARED_SINK = """
services:
  agent-1:
    build: {dockerfile: flowtree/runtime/agent/Dockerfile}
    volumes:
      - "/host/transcripts:/agent-transcripts:rw"
  agent-2:
    build: {dockerfile: flowtree/runtime/agent/Dockerfile}
    volumes:
      - "/host/transcripts:/agent-transcripts:rw"
"""


def test_shared_writable_sink_is_flagged():
    violations = v.find_violations(_compose(SHARED_SINK))
    assert violations
    assert any("share a volume source" in m for m in violations)


# --- One agent writable, another mounts a nested path under it. ---

NESTED = """
services:
  agent-1:
    build: {dockerfile: flowtree/runtime/agent/Dockerfile}
    volumes:
      - "/data:/agent-transcripts:rw"
  agent-2:
    build: {dockerfile: flowtree/runtime/agent/Dockerfile}
    volumes:
      - "/data/sub:/agent-transcripts:ro"
"""


def test_nested_paths_are_flagged():
    violations = v.find_violations(_compose(NESTED))
    assert any("share a volume source" in m for m in violations)


# --- Shared READ-ONLY mounts are safe and must NOT be flagged. ---

SHARED_RO = """
services:
  agent-1:
    build: {dockerfile: flowtree/runtime/agent/Dockerfile}
    volumes:
      - "/host/models:/models:ro"
      - "/host/transcripts/agent-1:/agent-transcripts:rw"
  agent-2:
    build: {dockerfile: flowtree/runtime/agent/Dockerfile}
    volumes:
      - "/host/models:/models:ro"
      - "/host/transcripts/agent-2:/agent-transcripts:rw"
"""


def test_shared_readonly_mounts_are_allowed():
    assert v.find_violations(_compose(SHARED_RO)) == []


# --- A writable mount targeting something other than the sink is guard drift. ---

WRONG_TARGET = """
services:
  agent-1:
    build: {dockerfile: flowtree/runtime/agent/Dockerfile}
    volumes:
      - "/host/work/agent-1:/workspace/project:rw"
"""


def test_writable_non_sink_target_is_flagged():
    violations = v.find_violations(_compose(WRONG_TARGET))
    assert any("/workspace/project" in m for m in violations)
    assert any("refuse to start" in m for m in violations)


# --- An undefined shared variable (no default) must still be detected. ---

SHARED_UNDEFINED_VAR = """
services:
  agent-1:
    build: {dockerfile: flowtree/runtime/agent/Dockerfile}
    volumes:
      - "${SHARED_DIR}:/agent-transcripts:rw"
  agent-2:
    build: {dockerfile: flowtree/runtime/agent/Dockerfile}
    volumes:
      - "${SHARED_DIR}:/agent-transcripts:rw"
"""


def test_shared_undefined_variable_is_flagged():
    # Both resolve to the same <<SHARED_DIR>> placeholder -> overlap.
    violations = v.find_violations(_compose(SHARED_UNDEFINED_VAR))
    assert any("share a volume source" in m for m in violations)


# --- Env interpolation with a default resolves to the default. ---

def test_resolve_env_uses_default():
    assert v.resolve_env("${FOO:-/x}/agent-1") == "/x/agent-1"


def test_resolve_env_distinct_suffixes_do_not_overlap():
    a = v.resolve_env("${T:-/d}/agent-1")
    b = v.resolve_env("${T:-/d}/agent-2")
    assert not v.paths_overlap(a, b)


# --- Anonymous volumes are per-container and never shared. ---

ANON = """
services:
  agent-1:
    build: {dockerfile: flowtree/runtime/agent/Dockerfile}
    volumes:
      - "/cache"
  agent-2:
    build: {dockerfile: flowtree/runtime/agent/Dockerfile}
    volumes:
      - "/cache"
"""


def test_anonymous_volumes_are_not_shared():
    # Anonymous volumes have no source; they cannot be a cross-agent channel.
    # However, they ARE writable mounts, and entrypoint.sh rejects any writable
    # mount whose target is not exactly /agent-transcripts. So guard-drift
    # now flags them.
    violations = v.find_violations(_compose(ANON))
    assert violations
    assert any("/agent-transcripts" in m for m in violations)
