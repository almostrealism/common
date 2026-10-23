#!/usr/bin/env python3
"""Tell a FlowTree job session apart from an interactive one.

Some policies exist only because of how the FlowTree harness captures a job's
work: it stages, commits and pushes the job's own working tree, and nothing
else. Work done anywhere else -- above all in a second git worktree -- is lost
when the job ends, while the job still reports success. A developer at a
terminal has no such harness and can use worktrees freely, so those policies
apply to job sessions only.

The harness marks every agent process it starts with ``AR_FLOWTREE_JOB=1``
(``AgentRunRequest.ENV_FLOWTREE_JOB``, set in
``AgentProcessRunner.applyRequestToProcessBuilder`` after the workstream's own
environment, so a workstream cannot clear it). A session is also treated as a
job when ``AR_WORKSTREAM_URL`` is set: the harness has always set it for a
job bound to a workstream, so nodes still running a harness from before the
marker existed stay covered. Neither variable is set in an interactive session,
and a hook reads the environment the session was started with, which nothing
inside the session can change.
"""

import os

JOB_MARKER = "AR_FLOWTREE_JOB"
LEGACY_JOB_VARIABLES = ("AR_WORKSTREAM_URL",)


def is_flowtree_job(environ=None):
    """True when the session was started by the FlowTree harness."""
    env = os.environ if environ is None else environ
    return any(env.get(name, "").strip() for name in (JOB_MARKER,) + LEGACY_JOB_VARIABLES)
