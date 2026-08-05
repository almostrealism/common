# amd-halo — OpenCL CI Runner

Plan for bringing the `amd-halo` machine online as an organization-level
self-hosted GitHub Actions runner, and moving the `test-cl` and `test-media-cl`
jobs onto it.

## Goal

Give the OpenCL backend a real GPU to run on.

Today `test-cl` and `test-media-cl` run on the self-hosted **macOS** fleet with
`AR_HARDWARE_DRIVER=native,cl` — that is Apple's deprecated OpenCL
implementation, sharing a fleet with the Metal jobs. `.github/CLAUDE.md` records
two consequences: the GPU lane must be serialised four stages deep so the Metal
and CL suites do not contend for the same GPUs, and the CL backend still hits its
memory ceiling at `AR_HARDWARE_MEMORY_SCALE=7` (the highest scale used anywhere,
and exponential, so it cannot simply be raised).

Moving the CL lane to `amd-halo` addresses both: it is a genuine AMD OpenCL
implementation, it is a separate fleet so the macOS GPU lane gets two stages
shorter, and the device reports far more memory than the Macs do.

## Feasibility: verified, not assumed

The central risk was whether OpenCL kernels can be compiled from inside a
container on this machine. They can. This was measured on the machine, not
inferred.

A probe running **inside a container** — using the JOCL binding that
`base/hardware/pom.xml` declares, which is the exact path `CLComputeContext`
takes — enumerated the platform, found the device, and completed both
`clBuildProgram` and `clCreateKernel`:

```
platforms=1
platform=AMD Accelerated Parallel Processing
  device=gfx1151
  JOCL_BUILD_OK kernel=true
```

`clinfo` in the same container reports `CL_DEVICE_TYPE_GPU`, ~101 GB global
memory, and ~86 GB maximum single allocation.

`docker create` → `docker start` → `docker exec` with `--device` flags all
succeed under the machine's container runtime. That is the exact lifecycle a
GitHub Actions `container:` job uses, so container jobs are viable here.

### Three findings that will otherwise cost a session

These are not hypotheticals; each one produced a hard failure during
investigation.

1. **The host's OpenCL is broken out of the box.** `/etc/OpenCL/vendors`
   contains only an XRT/NPU ICD pointing at a shared object that does not exist
   on disk. A bare `clGetPlatformIDs` returns zero platforms and a `dlerror`.
   Anyone testing OpenCL here without knowing this will conclude the machine
   cannot do OpenCL at all.
2. **The ROCm ICD file names its library relatively.** The ICD shipped under
   `/opt/rocm/etc/OpenCL/vendors` contains a bare filename, which the loader
   cannot resolve. The image must write its own ICD entry containing an
   **absolute** path to the ROCm OpenCL runtime.
3. **`OCL_ICD_VENDORS` is silently ignored** by the host's ICD loader build. It
   was tried as a directory, as a single file, and with an absolute-path ICD;
   all three returned zero platforms. Do not build the setup around it. Writing
   the ICD file into `/etc/OpenCL/vendors` inside the image is the mechanism
   that works.

Additionally, `libatomic1` must be installed or the ROCm OpenCL runtime fails to
load entirely.

## Machine facts

Recorded here because they drive decisions below.

| | |
|---|---|
| GPU | Radeon 8060S (`gfx1151`), integrated, unified memory |
| RAM | 125 GB; OpenCL device exposes ~101 GB global |
| Disk | ~1.7 TB free |
| OS | Debian-based AMD Ryzen AI Developer Platform |
| ROCm | Installed at `/opt/rocm` from the platform-specific `therock-gfx1151` package |
| Container runtime | **podman, rootless**, emulating the Docker CLI; user socket active |
| Devices | `/dev/kfd`, `/dev/dri/renderD128` — `root:render`, plus a POSIX ACL |
| Already installed | *No* JDK, *no* Maven, *no* runner agent |

Note: `clinfo` reports 20 compute units where `amd-smi` reports 40. That is RDNA
work-group-processor accounting, not a misconfiguration.

## Decisions

### Bind-mount ROCm; do not bake it into the image

The image installs the ICD loader and registers an ICD entry, but the ROCm
userspace itself is mounted from the host read-only at `/opt/rocm`.

The host's ROCm comes from a platform-specific package matched to its kernel
driver. Pinning a public `rocm/*` base image would couple CI to a ROCm release
chosen independently of the host's kernel module, and this GPU is new enough
that support is not uniform across releases. Bind-mounting makes userspace and
kernel agree by construction, and keeps the image small (~500 MB rather than
tens of GB) so rebuilds stay cheap.

Trade-off, stated plainly: the image is then **not portable** to a machine
without a compatible ROCm at `/opt/rocm`. That is acceptable — it is a
single-purpose CI image for one fleet. It should be named so nobody mistakes it
for a general ROCm image.

**Not tested:** a stock public ROCm base image. If bind-mounting ever becomes
untenable, that is the fallback to evaluate, and it needs its own verification
pass.

### A dedicated service account

The runner gets its own unprivileged account rather than running as the
interactive user, so CI cannot read or write that user's home directory,
credentials, or SSH keys.

**This is the step most likely to silently break GPU access.** The render
devices carry an ACL granting the *current interactive user* — not a group. A
new account inherits nothing from that. It needs either membership in the
`render` group or its own ACL entries, and this must be verified from inside a
container running as that account before anything else is debugged.

Rootless podman under the service account also means container UID 0 maps to
that account on the host, so files the job writes into the mounted workspace are
owned by the service account. That is the desired outcome.

### A distinct runner label

`amd-halo` registers with `self-hosted, linux, ar-ci-cl` — and deliberately
**not** `ar-ci`.

The existing Linux fleet carries `ar-ci` and serves `test` and `test-media`. If
`amd-halo` also carried `ar-ci` it would start picking up general CPU test jobs,
which is not the intent and would put the CL lane behind an unrelated queue.
A distinct label keeps the machine dedicated to the CL lane.

### Organization scope

Registration is at the org level so other repositories in the org can share the
machine without re-registering it. This requires granting the runner group
repository access once, in org settings — otherwise jobs queue forever against
a runner that appears healthy.

Note that `tools/ci/docker/entrypoint.sh` is currently **repo-scoped only** — it
builds a `repos/{owner}/{repo}` API base and has no org branch. The macOS
`runner.sh` already supports `RUNNER_SCOPE=org`. The new entrypoint needs the
org branch; see the work breakdown.

> **Security:** GitHub advises against self-hosted runners on **public**
> repositories, because a fork PR can execute arbitrary code on the host. This
> repository is public. Restrict the runner group to private repositories, or
> require approval for fork-PR workflow runs, before the runner goes live.
> The existing macOS README carries the same warning; it applies with more force
> here, because this host has a GPU and a large local dataset.

## Work breakdown

### Phase 1 — Service account and device access

1. Create an unprivileged service account on `amd-halo` with its own home.
2. Grant it the render devices — group membership or explicit ACL entries on
   `/dev/kfd` and `/dev/dri/renderD128`. Make this survive reboot; udev rules
   are the durable mechanism, ACLs set by hand are not.
3. Enable lingering for the account so its user-level services and rootless
   containers run without an active login session.
4. Enable the user container socket for that account.
5. **Gate:** run `clinfo` in a container *as the service account* and confirm the
   device appears. Do not proceed until this passes — every later failure would
   otherwise be misattributed.

### Phase 2 — Image and scripts, under `tools/ci/rocm/`

A new directory beside the existing `tools/ci/docker/` and `tools/ci/macos/`,
following their established shape:

```
tools/ci/rocm/
├── .env.example       # config template, following tools/ci/docker/.env.example
├── Dockerfile         # runner agent + JDK + Maven + OpenCL ICD loader
├── docker-compose.yml # device passthrough, ROCm bind-mount, resource limits
├── entrypoint.sh      # register / run / deregister, with org scope support
├── settings.xml       # Maven settings, mirroring tools/ci/docker/settings.xml
└── README.md          # setup, operations, troubleshooting
```

Derive each file from its `tools/ci/docker/` counterpart rather than writing it
fresh — the naming/claiming, stale-runner cleanup, ephemeral registration, and
signal-handling logic there is already correct and battle-tested. The deltas:

**`Dockerfile`** — same base layers as the existing runner image (runner agent,
JDK matching what the workflow's `setup-java` step expects, Maven, a `runner`
user), plus:
- `ocl-icd-libopencl1`, `libatomic1`, `clinfo`
- an ICD entry written to `/etc/OpenCL/vendors/` containing the **absolute** path
  to the ROCm OpenCL runtime under the bind-mount
- `build-essential`, required by the `native` JNI backend, which the CL jobs also
  exercise via `AR_HARDWARE_DRIVER=native,cl`
- `/opt/rocm/bin` on `PATH`, `/opt/rocm/lib` on `LD_LIBRARY_PATH`
- a comment stating the image is inert without the ROCm bind-mount

**`docker-compose.yml`** — adds `devices:` for `/dev/kfd` and `/dev/dri`, a
read-only `/opt/rocm` bind-mount, `RUNNER_LABELS=ar-ci-cl`, and memory/CPU
limits sized for this host rather than the 16g default.

**`entrypoint.sh`** — add the `RUNNER_SCOPE=org` branch absent from the Docker
fleet's version: select `orgs/{owner}` vs `repos/{owner}/{repo}` for the
registration-token, remove-token, and runner-list API calls, and pass the
matching `--url` to `config.sh`. Port the shape from `tools/ci/macos/runner.sh`,
which already does this.

**`README.md`** — mirror the macOS README's structure. Must document the three
ICD findings above; they are the difference between a ten-minute setup and a
lost session.

### Phase 3 — Register the runner

Configure `.env`, bring the fleet up, grant the runner group repository access in
org settings, and confirm the runner appears with the expected labels.

Start with **one** runner. This host has a single GPU; concurrent jobs would
contend for it, which is the exact problem the macOS GPU lane's serialisation
exists to avoid. Scale only if measurements justify it.

### Phase 4 — Move `test-cl` and `test-media-cl`

Both jobs are in `.github/workflows/analysis.yaml` (`test-cl` at ~line 1306,
`test-media-cl` at ~line 1538).

Per job:

1. `runs-on: [self-hosted, macos, ar-ci]` → `[self-hosted, linux, ar-ci-cl]`.
2. `DYLD_LIBRARY_PATH` → `LD_LIBRARY_PATH` on **every** step. This is a macOS/Linux
   difference and it is easy to miss one of the ~10 occurrences across the two
   jobs. `AR_HARDWARE_LIBS` stays as-is; it is the primary mechanism.
3. **Revisit `needs:` and the `if:` gates.** Both jobs currently gate on
   `test-mac` and `test-media-mac` purely to serialise the *macOS* GPU fleet.
   Once they run on a different machine that rationale no longer holds, and
   keeping the gates would leave the CL lane idle waiting on unrelated Metal
   work. The CL jobs should become an independent third lane —
   `test-cl` → `test-media-cl` — gating on `build` and the four validation checks,
   and on each other, but not on the mac jobs. Update the lane description in
   `.github/CLAUDE.md` to match.
4. **Re-tune the memory settings.** `AR_HARDWARE_MEMORY_SCALE=7` and the 8-group
   split exist because the CL backend hit a ceiling on the Macs. This device
   exposes ~101 GB, so both may be over-constrained. Treat retuning as a
   **follow-up measured change, not part of the migration** — move first with
   settings unchanged, confirm green, then tune one variable at a time. Changing
   both at once makes a regression un-attributable.
5. Leave `max-parallel` at its current value or lower until single-GPU
   contention on this host is measured.

### Phase 5 — Validate

1. Confirm the runner picks up a job and the GPU is visible from inside it.
2. Run the migrated jobs on a branch and confirm green.
3. Compare wall-clock against the macOS baseline.
4. Confirm the macOS GPU lane is now two stages shorter and that `test-mac` /
   `test-media-mac` are unaffected.

## Known blocker: the sample library

`test-media-cl` runs `studio/music`, `studio/compose`, and `studio/spatial`.
Several of those tests read a real audio sample library, and on the macOS fleet
that lives at a shared macOS path seeded by `tools/ci/sync-music-samples.sh`.

This matters because the failure mode is **silent**: per the macOS README, a
runner without the library falls back to synthetic samples and reports
misleading timings rather than failing. A green migration could therefore hide
the fact that the benchmark tests stopped measuring anything real.

The good news is that `AudioSceneTestBase` reads its roots from the
`AR_RINGS_LIBRARY` and `AR_RINGS_PATTERNS` system properties, defaulting to the
macOS paths only when unset — so the fix is to stage the library on `amd-halo`
and pass those properties in the migrated steps.

Two caveats to resolve during Phase 4:

- Some tests hardcode absolute macOS paths rather than reading the properties
  (`MixdownManagerPdslVerificationTest`, `PrototypeDiscoveryTest`,
  `ReproduceRefreshBug`). Their behaviour on Linux must be checked explicitly —
  if they skip or silently degrade, that needs to be visible, and fixing them to
  honour the existing properties is in scope.
- ~~A Linux analogue of `sync-music-samples.sh` is needed, or the existing script
  extended.~~ **Resolved.** The script turned out to be platform-neutral already
  (its one macOS-specific line is guarded and no-ops elsewhere), so it moved up
  to `tools/ci/sync-music-samples.sh` and serves both fleets via `--dest` and
  `--group`. The service account's read access is handled by group ownership
  rather than by syncing as that account, which stays login-locked; see
  `tools/ci/rocm/setup-host.sh`.

## Risks

| Risk | Mitigation |
|---|---|
| Service account cannot reach the GPU (ACL grants the interactive user only) | Phase 1 gate — verify from a container as that account before anything else |
| A host ROCm upgrade changes paths under the bind-mount | Image writes the ICD at build time; a path change breaks loudly at job start, not subtly mid-run |
| GitHub Actions `container:` friction under podman | Create/start/exec with devices verified; fallback is running the runner agent itself in the container, as the Docker fleet already does |
| Public-repo fork-PR exposure | Restrict runner group to private repos or require fork-PR approval **before** going live |
| Single GPU, concurrent jobs contend | Start with one runner; keep `max-parallel` conservative |
| Silent sample-library fallback masks a broken benchmark | Stage the library and pass `AR_RINGS_LIBRARY`; verify a sample-dependent test actually reads it |
| CL backend behaves differently on AMD than on Apple OpenCL | This is the *point* of the migration — real failures here are signal. Note that per `.github/CLAUDE.md` the CL jobs upload no surefire reports and are the only results not eligible for auto-resolution, so failures need manual triage |

## Out of scope

- Retuning `AR_HARDWARE_MEMORY_SCALE` or the group count (follow-up, measured).
- Moving `test` / `test-media` off the existing Linux fleet.
- Any Metal-lane change beyond removing the now-unnecessary cross-lane gates.
- Using this host for FlowTree agent jobs.

## Rollback

Revert the `analysis.yaml` change. The jobs return to the macOS fleet, which
keeps its `ar-ci` label throughout and is never modified by this work. The
`amd-halo` runner can stay registered and idle — nothing targets `ar-ci-cl`
once the workflow is reverted. Rollback requires no change on the machine.
