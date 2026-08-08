# Unresolved: host-wide network loss on the ROCm runner

**Status: open, cause unknown.** Recovered by reboot. Recorded here so the next
occurrence starts from evidence rather than from scratch.

## What happened

2026-08-05. The machine became unreachable from outside. Investigation from the
console found:

- The network interface reported itself **connected**.
- No apparent DHCP problem.
- **No host was reachable from the machine** — outbound connectivity was gone.
- Tailscale was down, which is why it was unreachable from outside. That was a
  **consequence** of the outbound failure, not its cause.
- Only a restart recovered it.

At the time the fleet was running **three** concurrent runners: the legacy
pre-template `ar-ci-cl-runner.service` plus `@1` and `@2`.

## What it was not

An initial hypothesis of memory exhaustion was **wrong** and should not be
revived without new evidence. The reasoning that killed it: this host has an
integrated GPU whose memory is unified with system RAM, and ROCm allocations are
pinned, so overcommit tends to wedge the kernel outright. That produces a dead
machine — no link, no ping, no console. It does not produce a live interface on
a host that is otherwise responsive but cannot route anywhere.

The memory-headroom guard in `install-runner.sh` was added during this
investigation and is worth keeping on its own merits — three runners at the
unit's 64G default is 192G of allowance on a 125G host — but it is **not** a fix
for this outage and should not be recorded as one.

## Candidates, none confirmed

| Candidate | Signature to look for |
|---|---|
| NIC driver or firmware wedge | `NETDEV WATCHDOG`, `transmit queue timed out`, `Detected Hardware Unit Hang`. The classic "link up, no traffic, reboot required" profile. |
| conntrack table exhaustion | `nf_conntrack: table full, dropping packet` |
| Routing blackhole via `tailscaled` | A clobbered default route looks identical from userspace |
| DNS-only failure | Names fail while raw IPs still work — distinguish before assuming a link fault |

## What to run when it recurs

The answer is in the **previous** boot's journal, so capture it before the
machine has rebooted more than once.

```bash
journalctl --list-boots

journalctl -b -1 -k --no-pager \
  | grep -iE 'netdev watchdog|transmit .*timed out|hardware unit hang|reset adapter|link is (down|not ready)'

journalctl -b -1 -k --no-pager \
  | grep -iE 'nf_conntrack|table full|neighbou?r table overflow'

journalctl -b -1 -u tailscaled --no-pager | tail -50
journalctl -b -1 -e --no-pager | tail -80
```

While the machine is healthy, this is cheap to check and would settle the
conntrack theory:

```bash
sysctl net.netfilter.nf_conntrack_max net.netfilter.nf_conntrack_count
```

Watch the count during a pipeline. A low `max` (65536 is a common default) that
the count approaches under load would be both the explanation and a one-line
fix.

## Why the workload is a plausible contributor

The CL lane is sixteen matrix groups. Each one performs a full
`mvn install -DskipTests`, which is thousands of short-lived HTTPS connections to
Maven Central. Until it was fixed, each job additionally re-downloaded the
GitHub Actions agent, because the image pinned an out-of-date version and
ephemeral containers discard the update. Several of those running concurrently
is substantial connection churn.

That reasoning supports the conntrack candidate specifically. It says nothing
about the others, and the agent-download fix reduces outbound volume without
being a cure for anything.

## If it recurs before the cause is known

A systemd timer that checks outbound reachability and, on sustained failure,
bounces the interface or reboots would convert a trip to the hardware into a
self-recovery. Deliberately not built yet: it is a workaround for an
unidentified fault, and building it now would reduce the pressure to find the
real one.
