"""Guard: .dockerignore must not exclude a path a Dockerfile COPYs.

The root .dockerignore exists so that images built with the repo root as their
context do not upload the whole repository. It is shared by every such build,
so an exclusion added for one image silently applies to all of them.

That is not hypothetical. A blanket ``**/target/`` was added for the ar-manager
image, which needs none of it — but the controller and agent images COPY
``flowtree/runtime/target/*.jar``. Both builds failed with::

    lstat /flowtree/runtime/target: no such file or directory

and only at deploy time, because no CI job built those two images. This test
closes that gap without needing a Docker daemon: it reads the COPY sources out
of every Dockerfile and checks them against the ignore rules.
"""

import os
import re
import unittest
from fnmatch import fnmatch

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_DOCKERIGNORE = os.path.join(_REPO_ROOT, ".dockerignore")


def _ignore_patterns():
    """Ordered (pattern, is_negation) pairs from .dockerignore."""
    patterns = []
    with open(_DOCKERIGNORE, encoding="utf-8") as handle:
        for raw in handle:
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            negated = line.startswith("!")
            patterns.append((line.lstrip("!").rstrip("/"), negated))
    return patterns


def _is_excluded(path):
    """Whether *path* would be withheld from the build context.

    Docker matches every path against every pattern and the last match wins,
    which is what allows a later ``!`` line to re-include something an earlier
    line excluded. A pattern also covers everything beneath it, so the check
    tests each ancestor directory as well as the path itself.
    """
    excluded = False
    candidates = [path]
    parts = path.split("/")
    for i in range(1, len(parts)):
        candidates.append("/".join(parts[:i]))

    for pattern, negated in _ignore_patterns():
        for candidate in candidates:
            if fnmatch(candidate, pattern) or fnmatch(candidate, pattern + "/*"):
                excluded = not negated
                break
            # `**/x` must also match a bare `x` at the context root.
            if pattern.startswith("**/") and fnmatch(candidate, pattern[3:]):
                excluded = not negated
                break
    return excluded


def _dockerfiles():
    """Every Dockerfile in the repo, excluding build output."""
    found = []
    for root, dirs, files in os.walk(_REPO_ROOT):
        dirs[:] = [d for d in dirs if d not in ("target", ".git", "node_modules")]
        for name in files:
            if name == "Dockerfile" or name.startswith("Dockerfile."):
                found.append(os.path.join(root, name))
    return found


def _copy_sources(dockerfile):
    """Local COPY sources in *dockerfile*, as context-relative paths.

    Skips ``COPY --from=`` (those read from an earlier build stage, not the
    context) and skips the destination argument.
    """
    sources = []
    with open(dockerfile, encoding="utf-8") as handle:
        for line in handle:
            stripped = line.strip()
            if not stripped.upper().startswith("COPY "):
                continue
            tokens = stripped[len("COPY "):].split()
            if any(t.startswith("--from=") for t in tokens):
                continue
            tokens = [t for t in tokens if not t.startswith("--")]
            if len(tokens) < 2:
                continue
            sources.extend(tokens[:-1])
    return sources


class DockerignoreConsistencyTests(unittest.TestCase):

    def test_no_dockerfile_copies_an_excluded_path(self):
        violations = []
        for dockerfile in _dockerfiles():
            for source in _copy_sources(dockerfile):
                probe = source.replace("*", "x")
                if _is_excluded(probe):
                    rel = os.path.relpath(dockerfile, _REPO_ROOT)
                    violations.append(f"{rel}: COPY {source}")

        self.assertEqual(
            violations, [],
            "These Dockerfile COPY sources are excluded by .dockerignore, so "
            "the build fails with 'no such file or directory'. Re-include them "
            "with a '!' rule, or narrow the exclusion:\n  "
            + "\n  ".join(violations),
        )

    def test_the_flowtree_runtime_jars_are_reachable(self):
        # The specific regression: these are what the controller and agent
        # images need, and what a blanket **/target/ rule takes away.
        for path in ("flowtree/runtime/target/ar-flowtree-runtime-x.jar",
                     "flowtree/runtime/target/dependency/x.jar"):
            self.assertFalse(
                _is_excluded(path),
                f"{path} must reach the build context; the controller and "
                f"agent images COPY it.",
            )

    def test_other_module_build_output_is_still_excluded(self):
        # The exclusion must still do its job, or the context balloons.
        for path in ("engine/utils/target/classes/X.class",
                     "base/hardware/target/x.jar"):
            self.assertTrue(
                _is_excluded(path),
                f"{path} should be excluded from the build context.",
            )


if __name__ == "__main__":
    unittest.main()
