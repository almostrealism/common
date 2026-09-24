# A required MCP server that did not connect kills the job

## The defect

`McpConfigBuilder.requiredServerNames()` names ar-manager as required
whenever it is configured. `ClaudeCodeRunner` reads the session's `init`
event, sees the server reported as anything other than `connected`, and
`CodingAgentJob.executeSingleRun()` throws. The throw escapes `doWork()`
before the commit block, so nothing the session produced is published.

The patch removes the gate that currently disables this, restoring the
behaviour exactly as it shipped.

## The failure it models

This ran in production for a day. Every agent job in the fleet failed with:

```
Required MCP server(s) unavailable when the agent session started:
ar-manager — the agent ran without them, so its output is not trusted
```

ar-manager was intermittently not connecting from the agent host. The policy
turned an infrastructure blip into total, fleet-wide downtime: no job could
publish anything, and a full working day was lost.

The whole test suite was green throughout. A large change was merged and
deployed against the outage; it addressed the reporting path and never
touched the line that was firing.

## Why this fault is in the catalogue

`workPublishesWhenArManagerDidNotConnect` asserts that a session whose
ar-manager did not connect still gets its work into the remote. With this
patch applied that assertion must fail.

When it was first written it did **not** fail, because the job it built
never configured an ar-manager URL or token — and `requiredServerNames()`
names the server only when both are set. The policy under test was
unreachable. The test was green and covered nothing.

That is precisely the shape this catalogue exists to detect, and the reason
it is not enough to write a test named after a defect. The test has to be
watched failing.
