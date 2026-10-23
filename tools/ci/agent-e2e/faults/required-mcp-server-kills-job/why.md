# A required MCP server that did not connect kills the job

## The defect

`McpConfigBuilder.requiredServerNames()` names ar-manager as required only
when ar-manager is configured **and** the `AR_REQUIRE_MCP_SERVERS`
enforcement switch is on; it is off by default. When a server is required,
`ClaudeCodeRunner` reads the session's `init` event, sees the server
reported as anything other than `connected`, and the job refuses to keep
the session's work.

`AgentJobFaultCatalogue.RequiresArManagerWheneverConfigured` is a
`McpConfigBuilder` whose `requiredServerNames()` ignores the switch —
ar-manager is required whenever it is configured, exactly as the policy
shipped. `requiredMcpServerKillsJob` installs it with
`CodingAgentJob.setMcpConfigBuilder` and runs
`workPublishesWhenArManagerDidNotConnect`.

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

It is the outage this suite exists for. `workPublishesWhenArManagerDidNotConnect`
asserts that a session whose ar-manager did not connect still gets its work
into the remote. With this fault installed that assertion must fail.

When it was first written it did **not** fail, because the job it built
never configured an ar-manager URL or token — and `requiredServerNames()`
names the server only when both are set. The policy under test was
unreachable. The test was green and covered nothing.

That is precisely the shape this catalogue exists to detect, and the reason
it is not enough to write a test named after a defect. The test has to be
watched failing. The unit tests of `McpConfigBuilder` pin what
`requiredServerNames()` returns; only the end-to-end scenario shows what the
runner and the job then do with a server that did not connect, which is
where the outage actually happened.
