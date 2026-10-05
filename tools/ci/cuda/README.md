# AR CI CUDA Runner

Self-hosted GitHub Actions runners for the **CUDA lane**: jobs labelled
`[self-hosted, linux, ar-ci-cuda]` — `test-cuda` and `test-media-cuda` in
`.github/workflows/analysis.yaml`. They run the compute and media suites with
`AR_HARDWARE_DRIVER=native,cuda`, so the CUDA backend is exercised on a real
NVIDIA GPU. The lane runs in parallel with the OpenCL lane (`tools/ci/rocm`) on
its own machine.

The lane is currently **informational**: it is not in `analysis.needs`,
`all-checks` or `auto-resolve`, so a failure does not block a merge. It joins
the gate once it has a passing baseline.

## Design: Nothing Installed on the Host

The target host is an NVIDIA DGX Spark (GB10, aarch64) running NVIDIA's own OS
image, and the fleet is built so that **running it changes nothing NVIDIA
configured**:

- **Runtime:** the Docker engine and NVIDIA Container Toolkit that ship with the
  host. GPU access is the toolkit's standard device reservation in the compose
  file; no runtime or toolkit configuration is edited.
- **Image:** the Linux CPU fleet's image (`../docker`), built unchanged. The
  only CUDA-specific pieces are runtime mounts and a preflight script.
- **CUDA toolkit:** the host's own, bind-mounted read-only at `/usr/local/cuda`.
  The CUDA backend's JNI library loads NVRTC from there (its runpath is
  `/usr/local/cuda/lib64`), and the driver library (`libcuda`) is injected by the
  NVIDIA runtime. Using the host's toolkit keeps it matched to the host driver
  by construction, the same trade the ROCm fleet makes with `/opt/rocm`.

This differs from the ROCm fleet, which uses rootless podman under a dedicated
service account. Rootless podman would require installing packages and changing
user-namespace configuration on this host, which is exactly what we avoid here.
The cost is that the runner containers run under the root Docker daemon. That is
acceptable only because **CI does not run for pull requests from forks**; if that
ever changes, revisit this choice before anything else.

## Prerequisites

Already present on a DGX Spark as shipped:

- Docker with the compose plugin, and the NVIDIA Container Toolkit
- The CUDA toolkit at `/usr/local/cuda` (with NVRTC)
- `curl` and `jq` on the host — `fleet.sh up` uses them to resolve the current
  `actions/runner` release (skipped only when `RUNNER_VERSION` is pinned in `.env`)

You need to provide:

- A **GitHub token** with `admin:org` (classic) or the organization
  "Self-hosted runners" permission (fine-grained), for org-scoped registration
- An account in the `docker` group to run `fleet.sh`
- The **sample library** directory (see *Test Data*)

Check the GPU is reachable from a container before anything else:

```bash
docker run --rm --gpus all ubuntu nvidia-smi -L
```

## Quick Start

```bash
cd tools/ci/cuda
cp .env.example .env
$EDITOR .env                  # set GITHUB_PAT
./fleet.sh up                 # build the image and start one runner
./fleet.sh logs -f            # the GPU preflight is the first thing logged
```

Start with **one** runner. This host has a single GPU whose memory is unified with
system RAM, and the workflow's own `max-parallel` (3 for `test-cuda`, 2 for
`test-media-cuda`) bounds what a single pipeline can use. Raise the count with
`./fleet.sh up 2` only after measuring, and keep `RUNNER_MEMORY_LIMIT` times the
runner count well below the host's memory.

## How It Works

Each runner container:

1. Runs `cuda-preflight.sh`, which checks that `nvidia-smi` lists a GPU and that
   NVRTC is present in the mounted toolkit. If either check fails it explains the
   likely cause and exits **without registering**. A runner that cannot see the
   GPU would otherwise take CUDA jobs and report results that measure nothing.
2. Hands over to the CPU fleet's entrypoint, which claims the lowest free
   `<prefix>-N` name and registers as an **ephemeral** runner with
   `--disableupdate`.
3. Runs one job, exits, and is restarted by Docker, which registers it afresh.

### Why `--disableupdate`, and why `fleet.sh up` resolves the agent version

An ephemeral runner whose agent is one release behind updates itself at the
moment it reports a job's result. When that happens, GitHub can lose the
completion, and the job shows "in progress" for hours (see the ROCm README,
*Jobs stay "in progress"*). So the runners never self-update, and `fleet.sh up`
builds the image with the current `actions/runner` release instead, unless
`RUNNER_VERSION` is pinned in `.env`. Re-run `./fleet.sh up` every few weeks.
GitHub refuses work to agents that fall too far behind.

## Test Data (Sample Library)

`test-media-cuda` runs the studio suites, which read a curated audio sample
library through `AR_RINGS_LIBRARY` / `AR_RINGS_PATTERNS`. The compose file mounts
`AR_CI_SAMPLES_DIR` (default `/srv/ar-ci/music`) read-only at `/opt/ar-samples`,
laid out as in the ROCm fleet:

```
/srv/ar-ci/music/
├── Samples/
└── pattern-factory.json
```

The directory must exist before `fleet.sh up`. The mount is declared with
`create_host_path: false` precisely so that Docker never creates directories on
the host behind your back. Stage it with `tools/ci/sync-music-samples.sh` (see
the ROCm README's *Test Data*). Until it is staged, the tests that call
`AudioSceneTestBase.requireCuratedLibrary()` fail. That is expected while the
lane is informational.

## Operations

```bash
./fleet.sh up [N]       # build and start N runners (default 1); also how to scale
./fleet.sh down         # stop all; each runner deregisters on SIGTERM
./fleet.sh status
./fleet.sh logs [-f]
```

`down` cancels any job in flight. Runners are ephemeral, so that only costs a
retry.

Verify registration:

```bash
gh api orgs/almostrealism/actions/runners \
    --jq '.runners[] | select(.labels[].name == "ar-ci-cuda") | {name, status}'
```

For org scope, grant the runner group access to the repository once (Org
Settings → Actions → Runner groups → Repository access), or jobs queue forever
against a runner that reports healthy.

## Files

```
tools/ci/cuda/
├── .env.example        # Credentials and runner settings template
├── docker-compose.yml  # GPU reservation, mounts, labels; builds ../docker
├── cuda-preflight.sh   # GPU and NVRTC checks before registering
├── fleet.sh            # up / down / status / logs
└── README.md           # This file
```

For the CPU fleet see [`../docker/`](../docker/); for the OpenCL fleet see
[`../rocm/`](../rocm/).
