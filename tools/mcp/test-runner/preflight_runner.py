#!/usr/bin/env python3
"""Preflight orchestration for a single run.

:mod:`preflight` decides *what* the upstream state is — which artifacts are
missing, how stale the installed ones are, how to seed them. This module is the
part that belongs to a run: it drives those steps in order and narrates them
into the run's ``output.txt``, so an agent reading the output sees why a run was
seeded, skipped, or refused before any Maven test output appears.

Nothing here touches run state beyond the output file, which is why it is
functions over a run directory rather than methods on the runner.
"""

from pathlib import Path

import preflight

# Number of missing artifacts named individually before the summary elides the
# rest; a long list buries the banner it is meant to explain.
MAX_NAMED_ARTIFACTS = 8


def write_section(output_file: Path, header: str, body: str = "") -> None:
    """Write a clearly-delimited preflight section to the run's output file.

    The banner mirrors the section markers used by ar-build-validator
    so an agent scrolling through ``output.txt`` can immediately see
    which steps are preflight vs. test execution.
    """
    with open(output_file, "a") as handle:
        handle.write(f"\n{'=' * 60}\n")
        handle.write(f"[ar-test-runner] PREFLIGHT: {header}\n")
        handle.write(f"{'=' * 60}\n")
        if body:
            if not body.endswith("\n"):
                body = body + "\n"
            handle.write(body)
        handle.write("\n")


def run(run_dir: Path, module: str, project_root: Path) -> preflight.PreflightResult:
    """Run the upstream-artifact preflight and persist its output.

    Returns the :class:`preflight.PreflightResult` produced by
    :func:`preflight.seed_upstream_artifacts`. Output emitted by
    Maven during the seed is appended to ``run_dir/output.txt`` so
    ``get_run_output`` shows it alongside (and before) the test run
    output.

    Failures inside the preflight helper itself (for example, a
    ``pom.xml`` that fails to parse for a reason the helper does
    not already swallow) are reported as a synthetic ``"failed"``
    result so the caller can short-circuit cleanly without
    spawning Maven on a broken setup.
    """
    output_file = run_dir / "output.txt"

    # Always surface installed-artifact ages first: `mvn test -pl <module>`
    # recompiles only <module>, so a dependency edited but not reinstalled
    # runs stale. This banner makes that obvious before the test launches.
    try:
        age_report = preflight.format_artifact_age_report(project_root, module)
        write_section(output_file, "dependency artifact ages (~/.m2)", age_report)
    except Exception as exc:  # noqa: BLE001 - a reporting error must never break a run
        write_section(
            output_file,
            "dependency artifact ages (~/.m2)",
            f"Artifact-age report unavailable: {exc}")

    try:
        missing = preflight.find_missing_upstream_artifacts(project_root, module)
    except Exception as exc:  # noqa: BLE001
        # Inspection failures short-circuit the run (action="failed"), mirroring seed failures.
        # find_missing_upstream_artifacts already returns [] on expected filesystem errors, so this
        # branch should only fire on truly unexpected exceptions.
        # If that behavior is undesirable, consider letting the test invocation proceed instead.
        write_section(
            output_file,
            "INSPECTION FAILED",
            f"Could not determine upstream dependencies: {exc}\n"
            "The test invocation is aborted due to this inspection error.",
        )
        return preflight.PreflightResult(
            action="failed",
            exit_code=1,
            reason=f"preflight inspection failed: {exc}",
        )

    if not missing:
        write_section(
            output_file,
            "skipped (upstream artifacts present)",
            f"All direct org.almostrealism dependencies of {module}\n"
            "are already installed in ~/.m2; no seed needed.",
        )
        return preflight.PreflightResult(
            action="skipped",
            reason="All direct org.almostrealism dependencies already installed",
        )

    missing_summary = ", ".join(
        f"{m.artifact_id}:{m.version}" for m in missing[:MAX_NAMED_ARTIFACTS])
    if len(missing) > MAX_NAMED_ARTIFACTS:
        missing_summary += f", ... (+{len(missing) - MAX_NAMED_ARTIFACTS} more)"
    write_section(
        output_file,
        f"seeding {len(missing)} upstream artifact(s)",
        f"Missing direct dependencies for {module}: {missing_summary}\n"
        f"Running: {' '.join(preflight.build_seed_command(module))}\n"
        "(This makes the FIRST ar-test-runner call in a fresh worktree "
        "self-sufficient — subsequent calls skip this step.)",
    )

    def _writer(chunk: str) -> None:
        try:
            with open(output_file, "a") as handle:
                handle.write(chunk)
        except OSError:
            # An output-write failure must never break the preflight.
            pass

    result = preflight.seed_upstream_artifacts(
        project_root, module, output_writer=_writer)

    if result.action == "seeded":
        write_section(
            output_file,
            f"seeded {len(result.missing)} artifact(s) in "
            f"{result.duration_seconds:.1f}s",
            "Test invocation will now proceed.",
        )
    elif result.action == "failed":
        write_section(
            output_file,
            f"FAILED (mvn install exited {result.exit_code})",
            "The upstream artifacts could not be installed. The test "
            "invocation is skipped because Maven would fail with the "
            "same dependency-resolution error.",
        )
    return result
