---
status: proposed
created: 2026-11-21
closed:
tracker: []
---

# Plan: Patch the JVM Now, Then Move Every System to Java 25

## Summary

Two separate jobs are being asked for, and they should not wait on each other.

1. **Close the exposure.** The vulnerability reported this month
   (CVE-2026-114938) is fixed by a patch release on each supported line,
   including the line we already run. Getting patched does not need a major
   version change, and a major version change is the slow way to get patched.
2. **Move to Java 25.** This is a compatibility project: one value drives most
   of CI, but the agent and controller images, the macOS hosts, four `pom.xml`
   files and several old dependencies each need their own step.

Do the first in a day. Do the second in the phases below.

## What the vulnerability is, and where we are exposed

As publicly reported: a heap overflow in the HotSpot Base64 decoder, reachable
on x86-64 processors with AVX-512 VBMI, triggered by crafted Base64 input, with
remote code execution possible. Fixed builds were released for the Java 17, 21
and 25 lines. The stated interim mitigation is to start the JVM with AVX-512
disabled. The exact fixed build numbers were taken from secondary reporting and
must be read from the vendor advisory before anyone relies on them.

What that means here:

| System | Processor | Decodes untrusted Base64 | Exposure |
|---|---|---|---|
| Controller container | Depends on the deploy host | Yes: HTTP endpoints, tokens, webhook payloads | **Highest. Check first** |
| Agent containers on Linux nodes | x86-64 | Job input and tool output | High if the processor has AVX-512 VBMI |
| CUDA and OpenCL runner images | x86-64 | Only repository content under test | Lower, still patch |
| macOS runners and agent hosts | Apple Silicon | n/a | Not reachable by this flaw; the JDKs are still years stale |
| Hosted CI jobs | Hosted | n/a | `setup-java` installs the current patch on each run |

Whether a given Linux host has AVX-512 VBMI is a one-line check
(`grep -o avx512_vbmi /proc/cpuinfo`). That check could not be made from the
macOS host this plan was written on, and no container runtime was running there.

## Phase 0 — Patch in place (no version change)

1. On every Linux host, run the processor check above and record the result.
2. Rebuild the controller and agent images with `--pull --no-cache` and
   redeploy. Both use a floating base-image tag for the Java 17 line, so a
   rebuild picks up the patched build; a running container keeps the build it
   was created with until then.
3. Rebuild the CI runner images (`tools/ci/docker`, `tools/ci/rocm`; the CUDA
   fleet reuses the first) the same way and restart the runners.
4. On any x86-64 host that cannot be rebuilt the same day, add the AVX-512
   mitigation flag to the JVM options until it can.
5. Record `java -version` from inside each running container and on each host.
   This is the evidence that the patch landed; a rebuild that hit a cached layer
   changes nothing.

Nothing in this phase changes a file in the repository.

## Where Java is pinned today

| Place | Current | How it is set |
|---|---|---|
| All CI lanes | 17 | `EXPECTED_JDK_MAJOR` in `analysis.yaml`. Hosted jobs pass it to `setup-java`; self-hosted jobs verify the installed JDK against it through the `select-jdk` action |
| CI runner images | 17 | `ARG JDK_MAJOR` in `tools/ci/docker/Dockerfile` and `tools/ci/rocm/Dockerfile` |
| macOS runners | 17 | `EXPECTED_JDK_MAJOR` default in `tools/ci/macos/runner.sh` and `fleet.sh`; one install hint in `runner.sh` names the major literally |
| Agent image | 17 | Base image and the Maven stage in `flowtree/runtime/agent/Dockerfile`, both hard-coded |
| Controller image | 17 (JRE) | Base image in `flowtree/runtime/controller/Dockerfile`, hard-coded |
| macOS agent hosts | 17 | Tool path default in `flowtree/runtime/agent/macos/run.sh` and its example env file |
| Compiler level | 17 | Root `pom.xml`, repeated in `studio/compose` and `studio/music` |
| Compiler level | **16** | `base/hardware/pom.xml` overrides the root. This is the oldest setting in the tree |
| This agent host | 21, a build from early 2024 | System JDK. Ten JDKs are installed, back to Java 7 |

## Phase 1 — Find out what breaks on 25 (no merge)

Run the build and a bounded set of tests under a Java 25 JDK with the compiler
level left at 17. This separates "the runtime changed" from "the language level
changed". Expected trouble, in the order it is likely to appear:

- **Build plugins.** The coverage agent and the static-analysis plugins must
  understand Java 25 class files. The coverage plugin is the usual first
  failure: it refuses class files newer than it knows.
- **Groovy** in `extern/ml-script` is several years old and predates Java 25
  bytecode.
- **Native access.** The JNI backends load native libraries (five source files
  call `System.load`). Java 25 warns on native access that has not been enabled
  and is moving toward refusing it. The test and run configurations need the
  enable-native-access option; none sets it today.
- **Module flags.** One build file each uses `--add-opens` and `--add-exports`.
  Each must be rechecked against Java 25's stricter encapsulation.
- **Finalizers.** Five files define `finalize()`. Finalization is deprecated
  for removal; these should move to the `Destroyable` lifecycle the project
  already has. Not a blocker for 25, but it is the right time.
- **Generated native code and JNI headers.** The hardware backends compile C
  against the JDK's headers. Confirm on each lane, not just on one host.

The output of this phase is a list of concrete failures, each with its lane.

## Phase 2 — Dependencies and build files (owner makes the `pom.xml` edits)

Agents do not edit `pom.xml` in this project, so this phase is a change list for
the owner.

- Raise the compiler level in the root `pom.xml` and delete the three per-module
  overrides (`base/hardware`, `studio/compose`, `studio/music`) so there is one
  setting. The `base/hardware` override at 16 has no reason to survive.
- Raise the compiler, test and coverage plugins to releases that support 25.
- Raise the dependencies that Phase 1 shows failing, and the ones that are old
  enough to carry known vulnerabilities regardless of Java (next section).

## Phase 3 — CI and images (one `ci/...` branch)

- Set `EXPECTED_JDK_MAJOR` to 25. Every hosted and self-hosted lane follows.
- Change the `JDK_MAJOR` default in both runner Dockerfiles, and make the agent
  and controller Dockerfiles take the major as a build argument as the runner
  images do, so the next upgrade is one value.
- Replace the literal major in the macOS tool-path defaults and install hint
  with the variable.
- Update `tools/tests/test_self_hosted_jdk.py` expectations where they name the
  major.

Sequencing matters: the self-hosted lanes fail fast when the installed JDK does
not match the expected major. Install Java 25 alongside 17 on every runner
**before** this branch merges, and remove 17 only after. Open pull requests
whose head predates the change will need a rebase.

## Phase 4 — Hosts

- Install the current Java 25 build on each macOS runner and agent host, and
  point the tool path at it.
- Remove JDKs that are past support. On the host inspected, that is every
  installed JDK except the one in use; several are x86-64 builds on an Apple
  Silicon machine.
- Redeploy the controller last, in a quiet window, after the agent image has
  run jobs on 25.

## Other out-of-date software found while looking

Found from the repository and from one macOS host. The Linux hosts were not
inspected.

**Dependencies in the build (highest concern first)**

| Dependency | Module | Concern |
|---|---|---|
| Netty | `flowtree/runtime` | A release from early 2018 in the network-facing controller. Many published vulnerabilities since |
| Apache HttpClient 4 | root, `flowtree/runtime` | Old patch level of a line in maintenance |
| JSch | `flowtree/graphpersist` | The original project is unmaintained and lacks current key and cipher support; a maintained fork exists |
| Commons BeanUtils, Commons Collections | `flowtree` | Old releases of libraries with a history of deserialization issues |
| Jackson Databind | several | About three years behind |
| Guava | `flowtree/graphpersist` | About four years behind |
| JUnit 4 | everywhere | Works, but the line is frozen |
| Groovy | `extern/ml-script` | See Phase 1 |

**Container images**

- Runner images are built on an Ubuntu release from 2022; the agent and
  controller images on the same generation.
- Four Python service images use Python 3.11, which now receives security fixes
  only.
- The dashboard image is pinned to a release about two years old.
- Python requirements are mostly unpinned, so what is installed depends on when
  each image was last built. Rebuilding is the upgrade; it is also unreproducible.

**CI**

- `actions/checkout`, `upload-artifact`, `download-artifact` and `cache` are on
  a major version that targets a deprecated Node runtime; the runner already
  prints a deprecation warning on every job.
- One third-party action is referenced by a moving branch name rather than a
  release or commit.
- CI uses three different Python minor versions across jobs.

**The macOS agent host**

- Git from 2020. It lacks options the pre-finish checks in `CLAUDE.md` use.
- Node 16, which reached end of life in 2023.
- The system JDK noted above.

## Order of work

1. Phase 0 today, starting with the controller host.
2. Netty and the other network-facing dependencies in `flowtree`, because they
   are exposed regardless of the Java version.
3. Phase 1, to turn the Java 25 unknowns into a list.
4. Phases 2 to 4.
5. The remaining image, action and host items, in any order.

## Not verified

- The fixed build numbers for the vulnerability, against the vendor advisory.
- The processor features, JDK builds and image ages on the Linux hosts.
- Which dependency versions are the current ones. The table above states how
  old each pin is, not what to move to.
- Any behaviour on Java 25. Nothing has been built or run on it yet.
