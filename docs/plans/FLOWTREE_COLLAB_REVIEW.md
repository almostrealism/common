# FlowTree collaborative-job review

Notes from a coordinating session (2026-09-13, `feature/sa3-prep`) that ran one
collaborative FlowTree job and one plain job through ar-manager while doing its own
implementation work in parallel. The division of labor was worth it: the job fetched
gated configuration and weights, ran real-weight parity tests, and produced reference
dumps the coordinator could not have produced locally. The friction below is what
kept it from being smooth. Each item names the observed behaviour and the smallest
change that would have fixed it.

## 1. Messages sent to a job are lost across relaunches

The job was relaunched twice for inactivity (`claude produced no output within the
inactivity window — relaunching (attempt N of 4)`). After each relaunch it re-oriented
from memories and its own earlier messages but did not read the coordinator's
messages already in the conversation, so it redid work it had been told to skip
(a second 2.3 GB download) and did not start the work it had been asked for until
the request was repeated three times.

Fix: a relaunched collaborative session should replay the workstream conversation
since its own last message (the `await_message(since=0)` catch-up the tool docs
describe) as part of its prompt, and the relaunch prompt should say "messages
addressed to you arrived while you were down; act on the newest instruction".

## 2. The inactivity watchdog counts long tool calls as silence

The relaunches happened while the job was inside long-running MCP calls (a multi-GB
download, a 165-second test run). Nothing was wrong, but the harness saw no output.

Fix: treat an in-flight tool call as liveness, or let a job extend its own window by
posting a status message (which it was already doing). At minimum, do not relaunch a
session whose last event is a tool call that has not returned.

## 3. Reference artifacts under `target/` are deleted as "binary litter"

At session end the harness removed every `.bin` file under
`engine/ml/target/test-classes/` (37 files), which is exactly where the reference-dump
scripts write by design so nothing reaches source control. The T5Gemma references the
coordinator had asked for were gone before they could be used; a parity run now has to
regenerate them first.

Fix: exempt git-ignored build output from litter cleanup (`git check-ignore` already
answers this), or scope the cleanup to paths git would actually consider untracked.
Alternatively give jobs a sanctioned scratch directory outside the repository that
survives the session and is reported in the final message.

## 4. `await_message` and `send_message` time out at roughly 60 seconds

From the coordinator's side, `await_message` with any `timeout_seconds` above ~30
returned an MCP timeout rather than an empty result, and `send_message`,
`memory_store` and `consult` intermittently timed out too (the message usually still
landed, which required an `include_own` re-read to confirm). Long waits are the whole
point of the tool.

Fix: keep the MCP round-trip under the transport limit (the server can return
`timed_out=true` after ~25 seconds and let the caller loop), and make `send_message`
idempotent on a client-supplied id so a timed-out send can be retried without
duplicating.

## 5. The workspace-secret tool was denied inside the job

`workspace_secret_render_file` was refused for the job ("you haven't granted it
yet") although it worked for the coordinator; the job found an alternative
(`ar-secrets`). Also, when the coordinator rendered the secret, it landed on the
ar-manager host, not on the coordinator's machine, which the tool result did not make
obvious.

Fix: pre-grant the secret tools a job needs when the workstream exposes secrets, and
have the render tool state which host received the file.

## 6. Runner selection is opaque

The first job landed on a Metal machine, which matters here because a known Metal
defect turns one of the parity tests to NaN; the second job was targeted with
`required_labels="os:linux"` on a guess. `agent_options` lists runners and models but
not the labels each runner advertises.

Fix: report each runner's labels (and whether it is currently busy) from
`agent_options` or `workstream_list`, and echo the chosen runner in the submit result.

## 7. Jobs cannot see the coordinator's uncommitted work

The job repeatedly re-reported a bug the coordinator had already fixed locally
("still unfixed as of this check"), because it can only see the pushed branch. That is
correct behaviour, but the job did not know it was looking at a stale view.

Fix: include the branch head the job checked out in its first status message, and let
the coordinator post a "working tree ahead of origin by N files" note the job can quote
instead of re-verifying.

## 8. Smaller points

- A job's final report was posted twice (once from a timed-out send). Idempotent sends
  (item 4) would remove the duplicate.
- The review phase ran after the job had already said it was finished; a
  collaborative job should be able to end with an explicit "finish" from the
  coordinator rather than an inactivity timeout.
- `workstream_get_job` still reported `STARTED` for a job whose primary phase had
  completed; a phase field would make polling from the coordinator side useful.
