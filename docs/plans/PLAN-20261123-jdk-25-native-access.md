---
status: proposed
created: 2026-11-23
closed:
tracker: [3668ee9a-51d3-42d3-9895-b10d6bdd9351]
---

# Plan: Move CI Runners and Agents to JDK 25, Granting Native Access Explicitly

**Tracker task:** `3668ee9a-51d3-42d3-9895-b10d6bdd9351` — "Move CI runners and agents
to JDK 25, granting native access explicitly". **Release:** Common 0.78.

Related plan: `PLAN-20261121-java-25-upgrade.md` surveys a wider Java 25 move
(including raising the bytecode level and upgrading old dependencies). This plan
carries only what the tracker task asks for. In particular, the bytecode level
**stays at 17**, which removes the hardest ordering constraint in that plan (a
Java 17 `javac` refusing `--release 25`).

## Motivation

The release owner decided on 2026-11-10 to move to JDK 25, the current LTS. The
runtime is the subject; the class files are not.

On JDK 24 and later (JEP 472), loading a native library or binding a native
method from code that was not granted native access prints a "restricted
method" warning, and a future release will refuse it by default. This project's
hardware backends are native code: `NativeCompiler`, `CU` and `MTL` in
`base/hardware` call `System.load`, and the OpenCL backend (JOCL), ONNX Runtime
(`extern/ml-onnx`) and DJL SentencePiece (`extern/ml-djl`) load their own
libraries. Native access is the application launcher's grant to make, not a
library's, so every JVM this repository launches has to pass
`--enable-native-access=ALL-UNNAMED`, and the library documentation has to tell
an embedding application to do the same. Getting that in now turns a future
hard failure into a configuration line.

JEP 471 has likewise deprecated the `sun.misc.Unsafe` memory-access methods and
JDK 24+ warns on their first use, so the task asks for that use to be found and
reported or fixed.

## What is already in place

The blocking task (`c8c06460`, self-hosted CI jobs stop downloading a JDK every
run) and the follow-up to the earlier plan made the JDK major a single value in
most places. Verified on master:

| Place | Value today | Mechanism |
|---|---|---|
| `.github/workflows/analysis.yaml` | `EXPECTED_JDK_MAJOR: "17"` | Workflow-level `env`. Hosted jobs pass it to `actions/setup-java`; the eight self-hosted jobs pass it to `.github/actions/select-jdk` |
| `tools/ci/docker/Dockerfile` | `ARG JDK_MAJOR=17` | Installs `temurin-${JDK_MAJOR}-jdk`, sets `JAVA_HOME` |
| `tools/ci/rocm/Dockerfile` | `ARG JDK_MAJOR=17` | Same; `install-runner.sh` passes `JDK_MAJOR` from `.env` |
| CUDA fleet | `JDK_MAJOR: ${JDK_MAJOR:-17}` in `tools/ci/cuda/docker-compose.yml` | Builds `tools/ci/docker`; there is no separate CUDA Dockerfile |
| macOS CI runners | `EXPECTED_JDK_MAJOR` default `17` in `tools/ci/macos/runner.sh` and `fleet.sh` | `fleet.sh` also derives the `openjdk@<major>` entry of `RUNNER_PATH` from it |
| `flowtree/runtime/agent/Dockerfile` | `ARG JDK_MAJOR=17` | Drives `eclipse-temurin:${JDK_MAJOR}-jdk-jammy` and the `maven:3-eclipse-temurin-${JDK_MAJOR}` stage. The task text still says `eclipse-temurin:17-jdk-jammy`; that literal is gone, the value is the build-arg default |
| `flowtree/runtime/controller/Dockerfile` | `ARG JDK_MAJOR=17` | `eclipse-temurin:${JDK_MAJOR}-jre-jammy` |

Literal `17`s that do not follow those values: the `brew install --cask
temurin@17` hint in `tools/ci/macos/runner.sh` (the second hint already uses the
variable), the `openjdk@17` path in `tools/ci/macos/.env.example` and
`tools/ci/macos/README.md`, and the `openjdk@17` entry of `DEFAULT_TOOL_PATH` in
`flowtree/runtime/agent/macos/run.sh` and its `agent.env.example`. The mac-studio
agent host runs Oracle JDK 21.0.2 (reported by the agent on this branch at job
start).

Structure tests that name the major: `tools/tests/test_self_hosted_jdk.py`
(asserts the `fleet.sh` default text `${ENV_EXPECTED_JDK_MAJOR:-17}`) and
`tools/tests/test_flowtree_image_jdk.py` (asserts that no Dockerfile hard-codes a
temurin major — this one stays valid unchanged).

Nothing in the repository sets `--enable-native-access` today.

## Scope

1. **CI images and runners to 25.** `EXPECTED_JDK_MAJOR` → `"25"`; `JDK_MAJOR`
   default → `25` in `tools/ci/docker/Dockerfile`, `tools/ci/rocm/Dockerfile`
   and `tools/ci/cuda/docker-compose.yml`; `EXPECTED_JDK_MAJOR` default → `25` in
   `tools/ci/macos/runner.sh` and `fleet.sh`; the literal install hints, example
   paths and READMEs under `tools/ci/` follow.
2. **Agent and controller images to 25.** `JDK_MAJOR` default → `25` in the agent
   and controller Dockerfiles; `DEFAULT_TOOL_PATH` in
   `flowtree/runtime/agent/macos/run.sh` and `agent.env.example` → the JDK 25
   Homebrew path.
3. **Native access granted at every launcher** listed under Approach §3.
4. **Library-user documentation** for the flag.
5. **`sun.misc.Unsafe` audit**, reported in the PR with each source.
6. **`<release>17</release>`** in place of `<source>`/`<target>` in the root
   `pom.xml`, and in the three modules that repeat or override it.

Out of scope: raising the bytecode level; upgrading dependencies for their own
sake; the vulnerability response in the earlier plan.

## Approach

### 1. Bytecode: `<release>17</release>` (root `pom.xml` and three modules)

In the root `maven-compiler-plugin` configuration replace `<source>17</source>`
and `<target>17</target>` with `<release>17</release>`. `--release` compiles
against the JDK 17 API signatures (`ct.sym`) instead of the running JDK's class
library, so a call to an API added after 17 fails at compile time on a JDK 25
builder instead of failing at run time on a 17 JVM.

The three per-module overrides must go the same way, or they defeat the point:

- `base/hardware/pom.xml` sets `<source>16</source>`/`<target>16</target>`.
  `--release 16` would compile `base/hardware` against the 16 API, which is
  stricter than the rest of the tree and has no reason to exist; delete the
  override so the module inherits `release 17`. (A grep of `base/hardware` for
  API added in 17 is part of the change; any such call already exists today and
  compiles only because `source/target` does not check the API.)
- `studio/compose/pom.xml` and `studio/music/pom.xml` repeat 17; delete the
  repeated `<source>`/`<target>` (or change them to `<release>17</release>` if
  the owner prefers explicit per-module values).

What stays valid under `--release`: the `-J--add-exports`/`-J--add-opens` flags
are options to the JVM running the forked `javac` (for Error Prone), not
compiler options, so `--release`'s refusal of `--add-exports` does not apply to
them. `-h target/headers` is unaffected. The only JDK-internal-looking import
in source is `com.sun.net.httpserver`, exported by its module and present in
`ct.sym`. No source imports `sun.*` or `jdk.internal.*`.

`engine/ml`'s Kotlin plugin has its own `<jvmTarget>1.8</jvmTarget>`; it is not
`maven-compiler-plugin` and is not part of this task.

### 2. Moving the JDK major to 25

**Linux images and the workflow.** One value per file, listed in Scope §1.
`tools/ci/rocm/README.md`, `tools/ci/docker/README.md`, `tools/ci/cuda/README.md`,
`tools/ci/macos/README.md` and `tools/ci/README.md` already describe the upgrade
as "change the value, rebuild"; their examples and stated defaults move to 25.
`.github/CI_ARCHITECTURE.md` needs no change (it names the variable, not a
value). `tools/tests/test_self_hosted_jdk.py`'s assertion on the `fleet.sh`
default text moves to `-25`.

**Cutover.** The self-hosted jobs fail fast when the runner's JDK is not the
expected major, and each Linux image bakes exactly one JDK, so there is a moment
when the workflow and the fleet disagree. The window is short for one reason
worth stating: `pull_request` runs take the workflow definition (and therefore
`EXPECTED_JDK_MAJOR`) from the pull request's merge commit with master, so once
the change is on master every open pull request picks up `25` on its next run
without a rebase. Only runs already queued at the moment of the flip, and
`push` runs on master's previous commit, see the old value. The recommended
order is therefore a coordinated cutover rather than a dual-JDK transition:

1. Install JDK 25 on the macOS CI hosts **beside** 17. `select-jdk` asks
   `/usr/libexec/java_home -v <major>` first, so a host with both satisfies
   either value; no routing work is needed there.
2. Merge the workflow change, then immediately rebuild and restart the Linux
   runner fleets (docker, ROCm, CUDA) with `JDK_MAJOR=25`.
3. Re-run any lane that failed its JDK check during the gap.

If the owner wants zero failed runs, the alternative from the earlier plan
(dual-JDK images with `select-jdk` also searching `/usr/lib/jvm`) remains
available; it is listed under open questions.

**Agents.** Rebuild the agent and controller images (`--pull`) with the new
default and redeploy, controller last. On the mac-studio agent host, install
JDK 25 and point `DEFAULT_TOOL_PATH` (or the host's `AGENT_TOOL_PATH` override in
its env file) at it; the committed default changes from
`/opt/homebrew/opt/openjdk@17/bin` to `/opt/homebrew/opt/openjdk@25/bin`. The
host's current JDK (21) is Oracle's, not Homebrew's, so the person installing 25
should confirm which path the running agent actually resolves `java` from
(`run.sh` prints it at start).

### 3. Native access, launcher by launcher

The flag is `--enable-native-access=ALL-UNNAMED`. The repository contains no
`module-info.java`, so every class — ours, JOCL's, ONNX Runtime's,
SentencePiece's — loads from the classpath into the unnamed module, and
`ALL-UNNAMED` is the grant that covers them. The flag has been accepted by the
`java` launcher since JDK 17 (JEP 412), so every change below works on the
current 17 lanes and can merge before the runner flip; this is to be confirmed
by the 17 lanes passing on the PR, as the plan notes under Success criteria.

| Launcher | File | Change |
|---|---|---|
| Surefire (every module) | root `pom.xml` | Add `<argLine>@{argLine} --enable-native-access=ALL-UNNAMED</argLine>` to the root `maven-surefire-plugin` configuration, and an empty `<argLine></argLine>` in the root `<properties>` |
| Failsafe | — | No module configures `maven-failsafe-plugin`; nothing to change. If one is added later it takes the same `argLine` |
| Agent container | `flowtree/runtime/agent/entrypoint.sh` | Add the flag to the fixed part of the `exec java` line (not to `JAVA_OPTS`, which is seeded from the operator-overridable `MAVEN_OPTS` here and would drop it) |
| Agent on macOS | `flowtree/runtime/agent/macos/run.sh` | Same, on its `exec java` line (here `JAVA_OPTS` is seeded from `AGENT_JVM_OPTS`) |
| Controller container | `flowtree/runtime/controller/Dockerfile` | Add it to the `ENTRYPOINT` array |
| Agent and controller from source | `flowtree/runtime/bin/start-agent.sh`, `start-controller.sh` | These run `mvn exec:java`, which executes in Maven's own JVM; prepend the flag to `MAVEN_OPTS` in the script |
| Moonbeam inference | `engine/ml/scripts/run_moonbeam_inference.sh` | Add to its `java` line |
| Optimizer scripts | `studio/compose/scripts/run-optimizer.sh`, `run-optimizer-benchmark.sh` | Add to their `exec java` lines |
| Render comparison | `studio/compose/scripts/render-single-vs-multi.sh` | Passes `-DargLine=...` to Maven; this composes with the pom's `@{argLine}` form and needs no change |
| ar-test-runner | `tools/mcp/test-runner/server.py` | Passes `-DargLine=<jvm_args>`; composes the same way, no change |
| OpenCL probe | `tools/ci/rocm/diagnose-opencl.sh` | Its `JoclProbe` `java` lines load JOCL; add the flag so the diagnostic output is not polluted by the warning |

Why the `@{argLine}` form and the empty property, together: the root `pom.xml`
binds `jacoco-maven-plugin`'s `prepare-agent`, which writes the coverage agent
into the `argLine` property. A plain `<argLine>--enable-native-access=...</argLine>`
would replace that and silently drop coverage in every module. `@{argLine}` is
substituted late, after `prepare-agent` runs, so the agent and the flag both
reach the fork, and a caller's `-DargLine=...` (the test runner, the render
script) is kept as well, because `prepare-agent` prepends to whatever the
property already holds. The empty `<argLine>` property covers the one case where
`prepare-agent` never runs (invoking `surefire:test` outside the lifecycle), in
which the literal text `@{argLine}` would otherwise reach the command line and
the fork would fail to start. `flowtree/runtime/pom.xml` and
`studio/experiments/pom.xml` configure surefire without an `argLine`, so they
inherit the root's.

Maven's own JVM: the flag is not needed for compilation (no native code loads in
`javac`). Whether Maven 3.9.x itself prints JDK 25 warnings (its bundled Jansi
and Guava) is a build-log question, not test output; record what the first
JDK 25 run shows and leave it unless it is noise in test logs.

Hosts' `_JAVA_OPTIONS`: `select-jdk` notes that the self-hosted runners set
`_JAVA_OPTIONS`. The grant belongs in the committed launchers, not in host
configuration, so nothing is added there.

### 4. Documentation for library users

Add a "Native access (JDK 24 and later)" subsection to `base/hardware/README.md`
under its Quick Start "Environment Setup", stating: the hardware backends load
native libraries; an application that embeds this library on the classpath
passes `--enable-native-access=ALL-UNNAMED` to `java`; an application on the
module path names the modules instead (for example the module that contains the
hardware classes and JOCL's); without it JDK 24+ prints a restricted-method
warning and a future JDK refuses the load. Point to it from the setup section of
`docs/QUICK_REFERENCE.md` and the hardware-acceleration tutorial
(`docs/tutorials/03-hardware-acceleration.html`). `flowtree/runtime/README.md`
shows no `java` command line, so it needs no change; its launchers carry the
flag themselves (§3). Regenerate
`llms-full.txt` if the project's docs tooling expects it to track the README.

### 5. `sun.misc.Unsafe` audit

No source file in the repository references `sun.misc.Unsafe` or
`jdk.internal.misc.Unsafe`. The use that JEP 471 will warn about comes from
dependencies:

| Dependency | Module | Note |
|---|---|---|
| `netty-all` 4.1.19.Final | `flowtree/runtime` | Uses `Unsafe` memory access for direct buffers when available |
| `guava` 31.1-jre | `flowtree/graphpersist` (and transitively elsewhere) | `Unsafe` in `UnsignedBytes`, `AbstractFuture`, `Striped64` |
| `protobuf-java` 3.21.7 | root, `engine/ml` | `UnsafeUtil` |

The implementation runs the selected test classes below on JDK 25 and records,
for each "terminally deprecated method in sun.misc.Unsafe" warning, the class the
JVM names as the caller. That list goes in the PR. Upgrading those libraries is a
dependency change outside this task (a follow-up task is filed for it); the
warning is printed once per JVM and does not fail anything on 25. Passing
`--sun-misc-unsafe-memory-access=allow` would silence it, but that hides the
signal the task asks to report, so it is not proposed.

### 6. Where the work lands

Files under `.github/workflows/` and `tools/ci/` change only on a `ci/...`
branch (`check-ci-file-lock.sh`). This branch is not one, so the work splits:

- **This branch:** the pom changes (§1, §3 surefire), the flowtree, engine and
  studio launchers (§3), the agent and controller Dockerfile defaults and the
  macOS agent tool path (§2 Agents), the documentation (§4), and the audit
  report (§5).
- **A `ci/jdk-25` branch:** `analysis.yaml`, everything under `tools/ci/`
  (including `diagnose-opencl.sh`), and the `tools/tests/test_self_hosted_jdk.py`
  expectation that follows it.

This branch merges first. It changes nothing a JDK 17 lane cannot run, and it
puts the flag in place before any lane runs 25, so the first JDK 25 run is
already clean.

## Success criteria

- The `ci/jdk-25` branch's run shows `select-jdk` reporting major 25 on every
  self-hosted lane (Linux `ar-ci`, `ar-ci-cl`, `ar-ci-cuda`, macOS `ar-ci`), and
  the hosted jobs' `setup-java` installing 25.
- The verify step passes on that run.
- No line containing `restricted method` or `--enable-native-access` appears in
  any surefire report or job log of that run. Checked by searching the test
  lanes' logs, and directly with these classes on JDK 25 before the CI flip:
  a `base/hardware` test that loads the JNI backend through `NativeCompiler`, one
  on the Metal backend on a macOS host, one on OpenCL (JOCL), and the
  `extern/ml-onnx` and `extern/ml-djl` tests, each through
  `mcp__ar-test-runner__start_test_run` with a bounded `test_classes` list.
- Coverage still arrives: the `analysis` job's coverage report is non-empty for
  `base/hardware` after the `argLine` change (the `@{argLine}` interaction).
- A deliberate call to a post-17 API in a scratch build fails compilation under
  `<release>17</release>` (done once, not committed).
- `java -version` from inside a rebuilt agent container and on the mac-studio
  host reports 25; an agent job started after redeploy logs the 25 path at start.
- `tools/tests/test_self_hosted_jdk.py` and `tools/tests/test_flowtree_image_jdk.py`
  pass.
- The PR lists the person actions below and the Unsafe audit result.

## Person actions (to be documented on the PR)

- Install JDK 25 beside 17 on each macOS CI runner host and on the mac-studio
  agent host, before the `ci/jdk-25` branch merges.
- After it merges: rebuild and restart the docker, ROCm and CUDA runner fleets
  with `JDK_MAJOR=25` (and remove any `JDK_MAJOR=17` override from each host's
  `.env`).
- Rebuild and redeploy the agent image, then the controller image.
- Once no queued run expects 17, remove JDK 17 from the macOS hosts if wanted.

## Dependencies

- Blocked-by task `c8c06460` (closed) supplied the single-value mechanism this
  plan relies on.
- **Owner authorization for `pom.xml` edits.** Project rules forbid agents from
  editing `pom.xml`. The task asks for two pom changes (the `release` switch and
  the surefire `argLine`). Either the owner authorizes the implementing session
  to make exactly those edits, or the owner makes them from the change list in
  §1 and §3.
- A `ci/...` branch for the workflow and `tools/ci` half (§6).

## Open questions

1. **Will the build itself run on 25 with the current plugins?** This is the
   main risk to "the verify step passes" and is not settled by anything in this
   plan. Error Prone 2.36.0 hooks into `javac` internals and has historically
   needed a newer release for each new JDK; JaCoCo 0.8.11 predates official
   JDK 25 support; the Kotlin compiler in `engine/ml` is 1.8.20; Groovy in
   `extern/ml-script` is 4.0.2. Each may need a version raise to *run* on a 25
   JDK even with 17 bytecode. The first step of implementation is a
   `mvn clean install -DskipTests` on a JDK 25 host; any plugin that fails is
   raised in the same pom change (still within "the verify step passes") and
   checked on 17 too. If more than a plugin raise is needed, that becomes its own
   task.
2. **Cutover or dual-JDK transition** (§2). Recommended: coordinated cutover,
   because pull requests pick up the workflow value from their merge ref. The
   alternative (images carrying both JDKs and `select-jdk` searching
   `/usr/lib/jvm`) costs an edit to the shared action and a larger image.
3. **`base/hardware`'s 16 override.** Recommended: delete it. If the owner knows
   of a consumer that needs 16 class files from that module, say so; with
   `release 17` everywhere else such a consumer could not use the rest of the
   tree anyway.
4. **Module-path consumers.** The documentation will name the flag for both
   classpath and module-path embedding, but nothing here is tested on the module
   path, because the repository builds no modules.

## Follow-up filed

- Upgrade the dependencies that use `sun.misc.Unsafe` memory access (Netty,
  Guava, protobuf-java), with the caller list from the §5 audit attached —
  tracker task `67f02cc5-5f87-4558-bb8a-f9ea16874a0f` (backlog).

## Estimated complexity

**Medium.** Every edit is small and mechanical, but it spans two branches, poms
under owner control, five fleets and two agent hosts, and the unknown in open
question 1 can turn into several plugin raises.
