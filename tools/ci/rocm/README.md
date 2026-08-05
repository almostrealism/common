# AR CI OpenCL Runner (ROCm)

Self-hosted GitHub Actions runner fleet for the **OpenCL lane**. Runs under
rootless podman as systemd user services (Quadlet) and picks up jobs labelled
`[self-hosted, linux, ar-ci-cl]` — `test-cl` and `test-media-cl`.

Its purpose is to give the OpenCL backend a real GPU. Those jobs previously ran
on the macOS fleet against Apple's deprecated OpenCL implementation, sharing
GPUs with the Metal suites.

## Read This First: Three Findings That Will Otherwise Cost a Session

Each of these produced a hard failure during setup, and none of them reports an
error that names the real cause.

1. **The host's OpenCL may be broken out of the box.** On the target platform
   `/etc/OpenCL/vendors` contained only an XRT/NPU ICD pointing at a shared
   object that does not exist on disk. A bare `clGetPlatformIDs` returns **zero
   platforms** and a `dlerror`. Anyone testing OpenCL on the host without
   knowing this concludes the machine cannot do OpenCL at all. This image
   sidesteps it by registering its own ICD entry inside the container.

2. **The ROCm ICD file names its library relatively.** The ICD shipped under
   `/opt/rocm/etc/OpenCL/vendors` contains just `libamdocl64.so` — and the
   library lives in the `lib/opencl/` **subdirectory**, not `lib/`, so that bare
   name is not on the loader's search path and resolves to nothing. This image
   writes its own entry containing an **absolute** path (`ROCM_OPENCL_LIB`,
   baked in at build time), defaulting to the verified location:

   ```
   /opt/rocm/lib/opencl/libamdocl64.so -> libamdocl64.so.2
   ```

   Note this is *not* `/opt/rocm/lib/libamdocl64.so`, which is the path most
   ROCm documentation implies and which does not exist here.

3. **`OCL_ICD_VENDORS` is silently ignored** by the host's ICD loader build. It
   was tried as a directory, as a single file, and with an absolute-path ICD;
   all three enumerated zero platforms with no diagnostic. Do not build any part
   of the setup around it. Writing the ICD file into `/etc/OpenCL/vendors`
   inside the image is the mechanism that works.

Additionally, **`libatomic1` must be installed** or the ROCm OpenCL runtime
fails to load entirely, with an error that mentions neither ROCm nor OpenCL.

## Architecture

```
┌───────────────────────────────────────────────────────────────────┐
│                         GitHub Actions                            │
│                                                                   │
│   CPU lane (linux)      GPU lane (macOS)        CL lane (linux)    │
│   test → test-media     test-mac                test-cl           │
│                          → test-media-mac        → test-media-cl  │
└────────┬───────────────────────┬──────────────────────┬───────────┘
         │ [self-hosted,         │ [self-hosted,        │ [self-hosted,
         │  linux, ar-ci]        │  macos, ar-ci]       │  linux, ar-ci-cl]
         ▼                       ▼                      ▼
  Docker fleet (Linux)    Native runners (macOS)   This fleet (ROCm)
      tools/ci/docker         tools/ci/macos          tools/ci/rocm
```

The label is `ar-ci-cl`, deliberately **not** `ar-ci`. The existing Linux fleet
carries `ar-ci` and serves `test` and `test-media`; sharing the label would pull
general CPU test jobs onto this machine and put the CL lane behind an unrelated
queue.

## What This Image Is Not

It ships the ICD loader and an ICD entry, but **no ROCm userspace**. The host's
ROCm is bind-mounted read-only at `/opt/rocm`. Without that mount the image is
inert, and the entrypoint refuses to register a runner.

That is a deliberate trade. The host's ROCm comes from a platform-specific
package matched to its kernel driver; pinning a public `rocm/*` base image would
couple CI to a ROCm release chosen independently of the host's kernel module.
Bind-mounting makes userspace and kernel agree by construction and keeps the
image small, so rebuilds stay cheap.

The cost, stated plainly: this image is **not portable** to a machine without a
compatible ROCm at `/opt/rocm`. It is a single-purpose CI image for one fleet.

## Prerequisites

- A Linux host with an AMD GPU, a working kernel driver, and ROCm installed
- A container runtime exposing the Docker CLI (Docker or rootless podman)
- `/dev/kfd` and `/dev/dri/renderD128` present
- A **GitHub Personal Access Token** with `admin:org` (for org-scoped
  registration)

## Host Setup

`setup-host.sh` performs the whole of it and is idempotent, so it is also the
repair path when something drifts:

```bash
sudo ./setup-host.sh --admin-user <your-login>   # apply
sudo ./setup-host.sh --check                     # verify, change nothing
```

It creates the service account, writes the udev rule, allocates rootless-podman
id ranges, enables lingering and the user socket, prepares the sample-library
destination, and finishes by checking that the service account can actually
reach the render devices from inside a container.

The rest of this section explains what it does and why, because when a step
fails the reason is rarely visible in the error.

### A dedicated service account

The runner gets its own unprivileged account rather than running as the
interactive user, so CI cannot read or write that user's home directory,
credentials, or SSH keys. The account is created with a locked password; reach
it with `sudo -iu ar-ci`.

Do **not** give it SSH keys so it can receive the sample library — that would
undo the isolation it exists for. Stage the library as an admin account instead;
see *Test Data* below.

### Render-device access

**This is the step most likely to silently break GPU access.** The render
devices carry a POSIX ACL granting the *current interactive user* — `logind`
tags them `uaccess` and grants the active-seat user directly. A service account
inherits nothing from that ACL, and the ACL disappears the moment nobody is
logged in.

The durable mechanism is a group plus a udev rule:

```
KERNEL=="kfd", GROUP="render", MODE="0660"
SUBSYSTEM=="drm", KERNEL=="renderD*", GROUP="render", MODE="0660"
```

The script installs this as `/etc/udev/rules.d/99-amdgpu-ci.rules`. The `99-`
prefix is deliberate: udev applies rules files in filename order and, for `=`
assignments, the **last** one wins. A `70-` prefix would lose to ROCm's own
`70-amdgpu.rules` — and `70-amdgpu-ci.rules` sorts *before* `70-amdgpu.rules`,
because `-` (0x2D) precedes `.` (0x2E).

Verify with `getfacl`, not `ls -l`. When a device carries an ACL — and these do,
note the `+` in `ls -l` — the group bits shown by `ls`/`stat` are the ACL
**mask**, not the group entry. A narrow mask revokes group access while `ls`
still displays `rw`:

```console
$ getfacl /dev/dri/renderD128
# owner: root
# group: render
user::rw-
user:michael:rw-     <- logind's uaccess ACL for the active seat
group::rw-           <- what the service account uses
mask::rw-            <- must not be narrower than the group entry
other::---
```

### Supplementary groups do not cross into a rootless container

The container already runs as the service account, which holds the render group
on the host, but podman drops supplementary groups inside the user namespace
unless told to keep them. That is what `GroupAdd=keep-groups` in the Quadlet
unit is for.

Passing a numeric gid instead is read *inside* the user namespace, where it maps
to a subgid and grants nothing. It fails silently: no permission error, just
zero OpenCL platforms.

**This is also why the fleet is Quadlet-managed and not Compose-managed.**
`keep-groups` is a podman CLI/Quadlet token, translated into the
`run.oci.keep_original_groups=1` OCI annotation. `docker compose` bypasses the
CLI and posts `HostConfig.GroupAdd` to podman's Docker-compatible REST API,
which does a literal group-name lookup in the container's `/etc/group` and
fails with `Unable to find group keep-groups`. There is no compose-expressible
substitute, so this fleet diverges from its Compose-managed sibling in
`tools/ci/docker`.

### Rootless podman needs subuid/subgid ranges

An account created with `useradd` (unlike `adduser`) often gets none, and
without them rootless podman cannot start **any** container. The error mentions
neither subuid nor the account:

```bash
grep ar-ci /etc/subuid /etc/subgid    # empty is the problem
```

Lingering must also be enabled, or the user's containers and socket stop when
no session is active. Note that a *running* user manager holds the group set it
started with — if you add the render group after enabling lingering, run
`sudo loginctl terminate-user ar-ci` or the change will not be seen.

### The gate

Verify from inside a container **as the service account** before debugging
anything else — every later failure would otherwise be misattributed:

```bash
sudo -iu ar-ci
podman run --rm --device /dev/kfd --device /dev/dri \
    --group-add keep-groups \
    -v /opt/rocm:/opt/rocm:ro ar-ci-rocm-runner:latest \
    bash -c 'clinfo -l'
```

You should see the AMD platform and the GPU device. If you see zero platforms,
re-read the three findings above before changing anything else.

Reboot once and re-run `sudo ./setup-host.sh --check`. Surviving reboot is the
entire reason for using a udev rule rather than ACLs set by hand.

## Quick Start

```bash
# 1. One-time host setup (service account, udev rule, rootless prerequisites)
cd tools/ci/rocm
sudo ./setup-host.sh --admin-user <your-login>

# 2. Build and install, as the service account
sudo -iu ar-ci
cd ~/common/tools/ci/rocm
cp .env.example .env
$EDITOR .env                 # set GITHUB_PAT
./install-runner.sh

# 3. Watch it come up — the OpenCL preflight is the first thing it logs
journalctl --user -u 'ar-ci-cl-runner@*' -f
```

Start with **one** runner, then scale deliberately — see *Scaling* below.

## Scaling

```bash
./install-runner.sh --runners 4     # or set RUNNER_COUNT in .env
```

Each runner is an instance of the template unit, so this starts
`ar-ci-cl-runner@1` through `@4`. Re-running with a smaller number stops the
instances above it, so scaling down is the same command. The GitHub-side names
need no coordination: the entrypoint claims the lowest free `<prefix>-N` from the
API, so instances self-assign `amd-halo-1`, `-2`, and so on.

Two things decide whether more runners actually help.

**The workflow caps concurrency independently of the fleet.** `test-cl` runs
`max-parallel: 3` and `test-media-cl` `max-parallel: 2`, and the two never
overlap because `test-media-cl` gates on `test-cl`. A single pipeline therefore
cannot use more than three runners however many are registered — beyond that they
only absorb concurrent pipelines. Raise `max-parallel` in `analysis.yaml` if you
want one pipeline to use more.

**Resource limits are per runner and the GPU memory is not separate.** This is an
integrated GPU whose memory is unified with system RAM, so N concurrent jobs
contend for one ~125 GB pool that must also cover `AR_HARDWARE_MEMORY_SCALE=7`
per test JVM. Set `RUNNER_MEMORY_MAX` / `RUNNER_CPU_QUOTA` in `.env` to a value
that makes sense multiplied by `RUNNER_COUNT`; `install-runner.sh` writes them as
a systemd drop-in. Overcommitting surfaces as an OOM kill or a
`HardwareException`, not as a gradual slowdown.

Much of each job is CPU-bound — checkout, then a full `mvn install -DskipTests` —
which is why extra runners can pay off despite the single GPU. But the test
phases do contend. Raise the count in steps and compare wall-clock against the
previous setting rather than assuming more is faster.

## Configuration

Configuration is split by what it governs:

- **`.env`** — credentials and runner settings, read by the entrypoint.
  `install-runner.sh` copies it to `~/.config/ar-ci-cl/runner.env` at mode 600,
  so the token does not live in the checkout. `.env` is gitignored; never commit
  a filled-in copy.
- **`ar-ci-cl-runner@.container`** — the container's shape: devices, mounts,
  group handling, and default resource limits. A systemd *template*, so one file
  serves every runner. Edit it and re-run `install-runner.sh`.

| Variable | Default | Description |
|---|---|---|
| `GITHUB_PAT` | *(required)* | Token with `admin:org` for org-scoped registration |
| `GITHUB_OWNER` | `almostrealism` | GitHub org or user |
| `RUNNER_SCOPE` | `org` | `org` (shared across the org) or `repo` |
| `GITHUB_REPO` | `common` | Repository name (only used for `repo` scope) |
| `RUNNER_PREFIX` | `amd-halo` | Runners register as `<prefix>-1`, `<prefix>-2`, … |
| `RUNNER_GROUP` | `Default` | Runner group |
| `ROCM_OPENCL_LIB` | `/opt/rocm/lib/opencl/libamdocl64.so` | Absolute path baked into the ICD at build time |

Set in `ar-ci-cl-runner@.container` rather than `.env`, because systemd unit
files do not interpolate environment variables into these fields. The limits are
the exception: `install-runner.sh` writes them as a drop-in from `.env`, so
scaling does not mean editing the unit.

| Setting | Default | Description |
|---|---|---|
| `Volume=` (ROCm) | `/opt/rocm:/opt/rocm:ro` | Host ROCm bind-mount |
| `Volume=` (samples) | `/srv/ar-ci/music:/opt/ar-samples:ro` | Curated sample library |
| `GroupAdd=` | `keep-groups` | Render group across the namespace boundary |
| `MemoryMax=` | `64G` | Memory limit |
| `CPUQuota=` | `1200%` | CPU limit, in percent — 1200% is twelve cores |

## How It Works

Each runner container:

1. **Verifies OpenCL before registering.** The entrypoint checks that
   `/opt/rocm` is mounted, that the ICD's target library exists, and that
   `clinfo` enumerates a platform. If any check fails it prints the likely
   cause and exits **without registering** — a runner that cannot see the GPU
   would otherwise pick up the CL suite, run it with no CL backend, and report
   misleading results.
2. Queries GitHub for existing runners with the same prefix and claims the
   lowest available index.
3. Removes any stale offline runner with that name.
4. Registers as an **ephemeral** runner (one job, then exit).
5. Picks up a job matching `[self-hosted, linux, ar-ci-cl]`.
6. Exits after the job completes; Compose restarts it, which re-registers.

Registration is org-scoped by default, so other repositories in the org can
share the machine without re-registering it.

## Organization Scope

Org-level registration needs a PAT with `admin:org` (classic) or the
organization "Self-hosted runners" administration permission (fine-grained),
with `GITHUB_OWNER` as the token's resource owner.

It also requires granting the runner group repository access **once**, in
Org Settings → Actions → Runner groups → (group) → Repository access. Without
that grant, jobs queue forever against a runner that reports healthy.

> **Security:** GitHub advises against self-hosted runners on **public**
> repositories, because a pull request from a fork can execute arbitrary code on
> the host. This repository is public. Restrict the runner group to private
> repositories, or require approval for fork-PR workflow runs, **before** the
> runner goes live. This applies with more force here than on the macOS fleet,
> because this host has a GPU and a large local dataset.

Verify registration:

```bash
gh api orgs/almostrealism/actions/runners \
    --jq '.runners[] | select(.labels[].name == "ar-ci-cl") | {name, status, labels: [.labels[].name]}'
```

## Test Data (Sample Library)

`test-media-cl` runs `studio/music`, `studio/compose`, and `studio/spatial`.
Several of those tests read a real audio sample library.

Two different failure modes matter here, and they are not the same:

- Tests that call `AudioSceneTestBase.requireCuratedLibrary()` **fail loudly**
  on a host where a GPU is available but the library is absent. Under
  `AR_HARDWARE_DRIVER=native,cl` this host *does* have a GPU, so those tests
  fail until the library is staged. That is intended behaviour, not a
  misconfiguration to work around.
- Tests that only call `getSamplesDir()` fall back to **synthetic samples
  silently** and report misleading timings. This is the dangerous case: a green
  run can hide the fact that the benchmarks stopped measuring anything real.

Stage the library at the host path the unit mounts (`/srv/ar-ci/music` by
default), mirroring the macOS `/Users/Shared/Music` layout — a `Samples/`
directory beside `pattern-factory.json`:

```
/srv/ar-ci/music/
├── Samples/                  ->  /opt/ar-samples/Samples             (AR_RINGS_LIBRARY)
└── pattern-factory.json      ->  /opt/ar-samples/pattern-factory.json (AR_RINGS_PATTERNS)
```

The compose file mounts that directory read-only at `/opt/ar-samples`, and the
workflow points `AR_RINGS_LIBRARY` / `AR_RINGS_PATTERNS` at the mount.

**Sync as an admin account, not as the service account.** The service account is
login-locked on purpose; giving it `authorized_keys` so it can receive an rsync
would undo the isolation. Instead let the sync script hand the tree over by
group — `setup-host.sh` prepares exactly this, adding the admin account to the
service group and setting the destination to `admin:ar-ci` mode 750:

```bash
# From a machine that holds the library
cd tools/ci
./sync-music-samples.sh --dry-run \
    --host amd-halo --user <admin-user> --group ar-ci --dest /srv/ar-ci/music
```

The script's remote step does `chgrp -R` + `chmod -R g+rX`, which works without
sudo only if the calling account **owns** the files and **belongs to** the target
group — hence both halves of that setup. Its one macOS-specific line is guarded
and no-ops on Linux. Drop `--dry-run` once the manifest looks right; rsync is
incremental and idempotent, so it is safe to re-run for library updates.

Then confirm the service account can read what landed. This is the check that
catches a group mistake before CI does:

```bash
sudo -iu ar-ci ls /srv/ar-ci/music/Samples | head
sudo -iu ar-ci test -r /srv/ar-ci/music/pattern-factory.json && echo readable
```

The second matters more than it looks: `requireCuratedLibrary()` tests for the
pattern factory specifically, and a GPU is available on this host — so an
unreadable pattern factory fails those tests rather than skipping them.

## Operations

All as the service account (`sudo -iu ar-ci`):

```bash
# Status
systemctl --user list-units 'ar-ci-cl-runner@*'

# Logs — the OpenCL preflight is the first thing each container logs
journalctl --user -u 'ar-ci-cl-runner@*' -f

# Stop. The entrypoint traps SIGTERM and deregisters from GitHub first,
# so this does not leave a stale offline runner behind.
systemctl --user stop 'ar-ci-cl-runner@*'

# Apply a changed .env or unit file (no rebuild)
./install-runner.sh --no-build

# Rebuild after changing the Dockerfile, entrypoint, or ICD path
./install-runner.sh
```

Because runners register as ephemeral, the container exits after every job and
systemd starts the next one. A service that keeps restarting is normal; one that
enters `failed` is not.

## Troubleshooting

### "No OpenCL platform is visible inside this container"

The entrypoint prints this and refuses to register. In the order worth checking:

1. **Render-device permissions.** `GroupAdd=keep-groups` is missing from the
   unit, or the service account was never granted the group. Confirm with
   `getent group render` and `sudo -iu ar-ci id` on the host, then re-run the
   gate command in *Render-device access* above.
2. **The ROCm bind-mount.** Confirm the unit's ROCm `Volume=` line points at a
   real ROCm tree on the host.
3. **`libatomic1`.** Present in this image; if you derived your own, check it.

### "The registered ICD points at … which does not exist"

The host's ROCm layout differs from what the image was built for. The error
lists the `libamdocl*.so` candidates it found under `/opt/rocm`. To find it
yourself:

```bash
cat /opt/rocm/etc/OpenCL/vendors/*.icd    # the filename ROCm expects
find /opt/rocm/ -name 'libamdocl*'        # where it actually is
ls -ld /opt/rocm && readlink -f /opt/rocm # is the mount point a symlink?
```

Then set `ROCM_OPENCL_LIB` in `.env` and rebuild:

```bash
./install-runner.sh
```

Express the path under `/opt/rocm` — the mount point. If `/opt/rocm` is a
symlink on the host, using the resolved target instead gives a path that does
not exist inside the container, and the symptom is zero platforms with a
configuration that looks correct.

Do not edit the ICD inside a running container — the next rebuild silently
reverts it.

### The setup-host.sh gate fails but `sudo -iu ar-ci id` shows the render group

Run the probe by hand. Test **readability**, which is what matters:

```bash
cd /                                            # see cause 1 below
sudo -iu ar-ci
echo "XDG_RUNTIME_DIR=$XDG_RUNTIME_DIR"        # must be set for rootless podman
podman info --format '{{.Host.OCIRuntime.Name}}'
podman run --rm --device /dev/kfd --device /dev/dri --group-add keep-groups \
    docker.io/library/debian:12 \
    sh -c 'id; test -r /dev/kfd && test -r /dev/dri/renderD128 && echo GATE_OK'
```

**Do not judge this by the group id `id` reports.** Under `keep-groups` the
host's supplementary groups are retained on the process, but a gid outside the
subgid map is unmapped in the user namespace and displays as `65534(nogroup)`.
So a correct host looks like this:

```
uid=0(root) gid=0(root) groups=0(root),65534(nogroup)
GATE_OK
```

That `65534` **is** the render group. Its presence is evidence `keep-groups`
worked — without it you would see bare `groups=0(root)`. The kernel checks
access against the process's real credentials, where the gid is still intact,
which is why `GATE_OK` prints.

Four causes, in the order worth checking:

1. **The working directory is unreadable by the service account.** podman
   resolves its own cwd at startup, so running it from an admin user's home
   fails with `cannot chdir ...: Permission denied` and exit **125** — before
   any device is touched, which reads exactly like a GPU problem. Run it from
   `/` or anywhere world-traversable.
2. **`XDG_RUNTIME_DIR` is unset.** Rootless podman needs it. Lingering creates
   `/run/user/<uid>`, but a non-login invocation (`runuser`, some `sudo`
   configurations) does not export the variable.
3. **The OCI runtime is `runc`, not `crun`.** `keep-groups` is a crun feature;
   under runc it is not honoured. Install `crun` or set it in
   `~/.config/containers/containers.conf`.
4. **The image could not be pulled** — no network, or a registry restriction.

An exit code of 125 means podman itself failed before starting the container,
so it is always one of the first two rather than anything GPU-related.

Note that `test -r` on the device is *not* a valid probe. Inside a user
namespace the device shows as `nobody:nogroup` whenever the host owner falls
outside the subuid map, so a readability test reports failure even where access
works. Check group membership, not readability.

### Jobs don't get picked up

- Verify labels match `self-hosted`, `linux`, `ar-ci-cl`
- Confirm the runner shows as **Idle**, not just registered
- For org scope, confirm the runner group grants the repository access
- Only one job runs at a time per runner (ephemeral mode)

### `clinfo` reports fewer compute units than `amd-smi`

Expected. On RDNA parts `clinfo` counts work-group processors while `amd-smi`
counts compute units — roughly a factor of two. It is not a misconfiguration.

### Native library errors

The CL jobs run `AR_HARDWARE_DRIVER=native,cl`, so they also exercise the JNI
`native` backend and need a compiler — `build-essential` is in the image for
that reason. Linux uses `LD_LIBRARY_PATH` where macOS uses `DYLD_LIBRARY_PATH`;
`AR_HARDWARE_LIBS` remains the primary mechanism and the workflow sets it
per-step.

## Files

```
tools/ci/rocm/
├── .env.example              # Credentials and runner settings template
├── Dockerfile                # Runner agent + JDK + Maven + OpenCL ICD loader
├── ar-ci-cl-runner@.container # Quadlet template unit: devices, mounts, keep-groups
├── entrypoint.sh             # OpenCL preflight, register / run / deregister
├── install-runner.sh         # Build the image and install the systemd user service
├── settings.xml              # Maven settings
├── setup-host.sh             # One-time host setup; idempotent, --check to verify
└── README.md                 # This file
```

There is deliberately no `docker-compose.yml`. See *Supplementary groups do not
cross into a rootless container* — Compose cannot express `keep-groups`, and
without it the container has no GPU.

For the Linux CPU fleet see [`../docker/`](../docker/); for the macOS fleet see
[`../macos/`](../macos/).
