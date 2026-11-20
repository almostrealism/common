"""Guard: GitHub-hosted CI jobs restore a deliberately-built Maven cache.

The hosted (``ubuntu-latest``) jobs used to take their dependency cache from
``actions/setup-java``'s ``cache: maven``. That cache saved the local repository
only on a miss, from whichever hosted job finished first, and never re-saved it
on a hit. The job that won that race (checkstyle, say) did not run jacoco, so
``org.jacoco:jacoco-maven-plugin`` was absent from the saved cache — and every
hosted job that runs ``mvn test`` (and so ``jacoco:prepare-agent``) re-downloaded
it from Maven Central on every run, failing on 429s and transient "not found"
errors.

The fix, pinned here:

* a ``maven-cache`` job resolves the full plugin and dependency set on purpose
  and saves it with an explicit ``actions/cache`` under a key built from the pom
  hashes and a manual version prefix, with a restore-keys fallback;
* every hosted Maven job restores it read-only through the shared
  ``restore-maven-cache`` composite action, so no consumer can re-save a partial
  repository;
* the save key is the exact key the restore action reports, so the two cannot
  drift;
* ``.mvn/maven.config`` carries the Maven transport retry/backoff so a remaining
  429/503/dropped connection is retried rather than failing the job;
* the ``analysis`` job takes jacoco-cli from the Maven cache, and when it falls
  back to Maven Central the ``curl`` fails on an HTTP error and retries, and the
  zip is verified before it is unpacked.

Self-hosted lanes are out of scope here: they have their own host-mounted cache
(see ``test_maven_cache.py``).
"""

import os
import re
import unittest

import yaml

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_WORKFLOW = os.path.join(_REPO_ROOT, ".github", "workflows", "analysis.yaml")
_RESTORE_ACTION = os.path.join(
    _REPO_ROOT, ".github", "actions", "restore-maven-cache", "action.yml")
_RESTORE_ACTION_REF = "./.github/actions/restore-maven-cache"
_MAVEN_CONFIG = os.path.join(_REPO_ROOT, ".mvn", "maven.config")

# The jobs that run Maven on a hosted runner and therefore must restore the
# deliberately-built cache. The maven-cache producer is listed because it, too,
# restores through the shared action before warming.
_HOSTED_MAVEN_JOBS = {
    "build", "maven-cache", "code-policy-check", "test-timeout-check",
    "duplicate-code-check", "checkstyle", "test-flowtree", "agent-e2e-faults",
    "test-media", "analysis",
}

_PROJECT_GROUP_EXCLUDE = "!~/.m2/repository/org/almostrealism"


def _read(path):
    with open(path) as f:
        return f.read()


def _workflow():
    with open(_WORKFLOW) as f:
        return yaml.safe_load(f)


def _jobs():
    return _workflow()["jobs"]


def _runs_on(job):
    labels = job.get("runs-on", [])
    return [labels] if isinstance(labels, str) else list(labels)


def _is_self_hosted(job):
    return "self-hosted" in _runs_on(job)


def _runs_mvn(job):
    return any(re.search(r"(^|\s)mvn\s", str(s.get("run", "")))
              for s in job.get("steps", []))


def _steps(job, uses):
    return [s for s in job.get("steps", [])
            if str(s.get("uses", "")).rstrip("/") == uses]


def _action():
    with open(_RESTORE_ACTION) as f:
        return yaml.safe_load(f)


class RestoreActionShapeTest(unittest.TestCase):
    def test_is_a_composite_action_with_a_cache_version_input(self):
        action = _action()
        self.assertEqual("composite", action["runs"]["using"])
        self.assertIn("cache-version", action.get("inputs", {}))

    def test_cache_version_is_required_with_no_default(self):
        """A default would silently pin a caller that forgot to pass the input
        to one prefix, so a bump of HOSTED_MAVEN_CACHE_VERSION would leave it
        reading a stale cache scope. The input must be required with no default
        so every caller declares the version explicitly."""
        cache_version = _action()["inputs"]["cache-version"]
        self.assertTrue(cache_version.get("required"),
                        "cache-version must be required so no caller falls back "
                        "to a stale default scope")
        self.assertNotIn("default", cache_version,
                         "cache-version must have no default (see required=true)")

    def test_action_references_no_env_context(self):
        """GitHub evaluates ${{ }} tokens anywhere in an action.yml, including
        the description and input fields, and the env context is not available
        at action-definition scope — the caller passes HOSTED_MAVEN_CACHE_VERSION
        as the cache-version input instead. A stray ${{ env.* }} (even in prose)
        makes the action fail to load with 'Unrecognized named-value: env',
        which breaks every hosted job that restores the cache."""
        with open(_RESTORE_ACTION) as f:
            text = f.read()
        self.assertNotIn("env.", text,
                         "restore-maven-cache action.yml must not reference the "
                         "env context; it is unavailable at action scope and "
                         "makes the action fail to load")

    def test_restores_read_only_and_excludes_project_artifacts(self):
        """It must use cache/restore (never the combined cache action, which
        would save a partial repository at the end of a consumer job) and must
        exclude the project's own artifacts, which come from build instead."""
        steps = _action()["runs"]["steps"]
        restore = [s for s in steps
                   if str(s.get("uses", "")).startswith("actions/cache/restore")]
        self.assertEqual(1, len(restore), "exactly one cache/restore step")
        self.assertEqual([], [s for s in steps
                              if str(s.get("uses", "")).rstrip("/") == "actions/cache"],
                         "must not use the combined cache action (it would save)")
        self.assertEqual([], [s for s in steps
                              if str(s.get("uses", "")).startswith("actions/cache/save")],
                         "the restore action must never save")
        path = restore[0]["with"]["path"]
        self.assertIn("~/.m2/repository", path)
        self.assertIn(_PROJECT_GROUP_EXCLUDE, path)

    def test_key_is_pom_hash_and_version_with_restore_keys_fallback(self):
        restore = [s for s in _action()["runs"]["steps"]
                   if str(s.get("uses", "")).startswith("actions/cache/restore")][0]
        key = restore["with"]["key"]
        self.assertIn("inputs.cache-version", key)
        self.assertIn("runner.os", key)
        self.assertIn("hashFiles('**/pom.xml')", key)
        restore_keys = restore["with"]["restore-keys"]
        # The fallback prefix is the key without the pom hash, so a pom change
        # starts from the previous cache rather than from empty.
        self.assertIn("inputs.cache-version", restore_keys)
        self.assertNotIn("hashFiles", restore_keys)

    def test_exposes_cache_hit_and_primary_key_outputs(self):
        """The maven-cache job gates its warm on cache-hit and saves under
        the exact primary key, so both must be exported outputs."""
        outputs = _action().get("outputs", {})
        self.assertIn("cache-hit", outputs)
        self.assertIn("cache-primary-key", outputs)


class HostedJobWiringTest(unittest.TestCase):
    def test_every_hosted_maven_job_restores_the_cache_once(self):
        seen = 0
        for name, job in _jobs().items():
            if _is_self_hosted(job) or not _runs_mvn(job):
                continue
            seen += 1
            with self.subTest(job=name):
                self.assertIn(name, _HOSTED_MAVEN_JOBS,
                              f"{name} runs Maven on a hosted runner but is not in "
                              "_HOSTED_MAVEN_JOBS; wire it to restore-maven-cache "
                              "and add it here")
                self.assertEqual(1, len(_steps(job, _RESTORE_ACTION_REF)),
                                 f"{name} must restore the Maven cache via "
                                 f"{_RESTORE_ACTION_REF} exactly once")
        self.assertGreater(seen, 0, "expected at least one hosted Maven job")

    def test_no_hosted_setup_java_uses_cache_maven(self):
        """Reintroducing setup-java's cache:maven on any hosted job would bring
        back the partial-cache race this change removed."""
        for name, job in _jobs().items():
            if _is_self_hosted(job):
                continue
            for step in job.get("steps", []):
                if str(step.get("uses", "")).startswith("actions/setup-java"):
                    with self.subTest(job=name):
                        self.assertNotEqual(
                            "maven", str(step.get("with", {}).get("cache", "")),
                            f"{name} setup-java must not set cache: maven")

    def test_nothing_depends_on_the_maven_cache_job(self):
        """maven-cache maintains the cache for subsequent runs; it is kept off
        the critical path, so no job may list it in needs (which would couple a
        warm-cache failure to the build and test lanes)."""
        for name, job in _jobs().items():
            needs = job.get("needs", [])
            needs = [needs] if isinstance(needs, str) else list(needs)
            with self.subTest(job=name):
                self.assertNotIn("maven-cache", needs,
                                 f"{name} must not depend on maven-cache")


class MavenCacheJobTest(unittest.TestCase):
    def setUp(self):
        self.job = _jobs()["maven-cache"]
        self.steps = self.job["steps"]

    def _step(self, name):
        for s in self.steps:
            if s.get("name") == name:
                return s
        self.fail(f"maven-cache has no step named {name!r}")

    def test_restores_through_the_shared_action_with_an_id(self):
        restore = _steps(self.job, _RESTORE_ACTION_REF)
        self.assertEqual(1, len(restore))
        self.assertEqual("restore", restore[0].get("id"),
                         "the restore step needs id: restore so warm/save can read its outputs")

    def test_warm_resolves_the_full_set(self):
        warm = self._step("Warm the Maven repository")
        run = warm["run"]
        # package -DskipTests resolves compiler/error-prone/surefire/jacoco and
        # test-scope deps (CPD) and reaches package so studio/compose can unpack
        # engine/ml's package-attached proto artifact (test-compile cannot — it
        # fails deterministically on MDEP-98); go-offline/resolve-plugins catch
        # the rest; the checkstyle plugin and the jacoco-cli distribution are
        # named explicitly.
        self.assertRegex(run, r"mvn\s+-B\s+-ntp\s+package\s+-DskipTests")
        self.assertNotIn("test-compile -Dcheckstyle", run)
        self.assertIn("dependency:go-offline", run)
        self.assertIn("dependency:resolve-plugins", run)
        self.assertIn("maven-checkstyle-plugin", run)
        self.assertRegex(run, r"dependency:get.*org\.jacoco:jacoco")
        self.assertTrue(warm.get("continue-on-error"),
                        "warming must be best-effort so a transient failure does "
                        "not fail this maintenance job")
        self.assertIn("steps.restore.outputs.cache-hit", str(warm.get("if", "")))

    def test_saves_under_the_restored_primary_key_only_after_a_successful_warm(self):
        """Cache keys are immutable. Saving a partially-warmed repository under
        the exact primary key would make every later run see cache-hit == 'true'
        and skip warming, leaving the read-only consumers re-downloading the
        missing artifacts forever. The save must therefore be gated on the warm
        step's outcome (which is 'skipped' on an exact hit and 'failure' on a
        failed warm despite continue-on-error), never on always()."""
        warm = self._step("Warm the Maven repository")
        self.assertEqual("warm", warm.get("id"),
                         "the warm step needs id: warm so the save can read its outcome")
        save = self._step("Save Maven dependencies")
        self.assertTrue(str(save.get("uses", "")).startswith("actions/cache/save"))
        self.assertEqual("${{ steps.restore.outputs.cache-primary-key }}",
                         save["with"]["key"],
                         "save must use the exact key the restore reported")
        self.assertIn(_PROJECT_GROUP_EXCLUDE, save["with"]["path"])
        cond = str(save.get("if", ""))
        self.assertIn("steps.warm.outcome == 'success'", cond)
        self.assertNotIn("always()", cond,
                         "a failed warm must not be saved under the immutable key")
        self.assertNotIn("conclusion", cond,
                         "conclusion is 'success' for a failed continue-on-error "
                         "step; the gate must read outcome")

    def test_removes_failed_transfer_markers_before_saving(self):
        """`*.lastUpdated` resolver markers make Maven's update policy suppress
        the retry for a consumer restoring this cache, turning a transient miss
        into a hard failure. The markers must be deleted, under the same gate as
        the save and ordered before it, so the saved cache never records a
        failed transfer."""
        names = [s.get("name") for s in self.steps]
        self.assertIn("Remove failed-transfer markers", names,
                      "maven-cache must delete *.lastUpdated markers before saving")
        self.assertLess(names.index("Remove failed-transfer markers"),
                        names.index("Save Maven dependencies"),
                        "the marker removal must run before the save")
        remove = self._step("Remove failed-transfer markers")
        self.assertRegex(remove["run"], r"\*\.lastUpdated",
                         "the step must target *.lastUpdated marker files")
        self.assertEqual(str(self._step("Save Maven dependencies").get("if", "")),
                         str(remove.get("if", "")),
                         "the marker removal must run under the same gate as the save")


class MavenConfigRetryTest(unittest.TestCase):
    def test_maven_config_sets_transport_retry_backoff(self):
        self.assertTrue(os.path.exists(_MAVEN_CONFIG),
                        ".mvn/maven.config must exist so every CI mvn picks up the retry")
        text = _read(_MAVEN_CONFIG)
        for prop in ("aether.connector.http.retryHandler.count",
                     "aether.connector.http.retryHandler.interval",
                     "aether.connector.http.retryHandler.intervalMax"):
            self.assertIn(prop, text, f".mvn/maven.config must set {prop}")

    def test_maven_config_has_no_comment_lines(self):
        """Maven reads .mvn/maven.config as whitespace-separated arguments, so a
        '#' line would be parsed as a bogus goal. Every line must be a -D flag."""
        for line in _read(_MAVEN_CONFIG).splitlines():
            line = line.strip()
            if not line:
                continue
            self.assertTrue(line.startswith("-D"),
                            f"unexpected non-flag line in maven.config: {line!r}")

    def test_mvn_config_dir_triggers_the_code_pipeline(self):
        """.mvn/ holds build inputs with no code extension (maven.config, the
        wrapper pins). The changes job must list it in the code-change pathspec,
        otherwise a PR that only edits Maven transport config is classified
        docs-only and skips build, maven-cache and the Maven validation jobs even
        though a build input changed."""
        text = _read(_WORKFLOW)
        match = re.search(r"CODE_FILES=\$\(git diff[^)]*\)", text)
        self.assertIsNotNone(
            match, "the changes job must assign CODE_FILES from a git diff pathspec")
        self.assertIn(
            "'.mvn/'", match.group(0),
            ".mvn/ must be in the code-change pathspec so a maven.config-only "
            "change still runs the build and Maven validation jobs")


class AnalysisJacocoDownloadTest(unittest.TestCase):
    def _merge_step_run(self):
        for s in _jobs()["analysis"]["steps"]:
            if s.get("name") == "Merge Coverage Data":
                return s["run"]
        self.fail("analysis has no 'Merge Coverage Data' step")

    def test_prefers_the_maven_cache_copy_of_jacoco_cli(self):
        run = self._merge_step_run()
        self.assertIn(".m2/repository/org/jacoco/jacoco", run,
                      "analysis must take jacoco-cli from the Maven cache when present")

    def test_curl_fallback_fails_on_http_error_and_retries(self):
        run = self._merge_step_run()
        # A curl that downloads the zip must fail loudly on an HTTP error (-f)
        # and retry transient ones, so a 429 or rate-limit page is never written
        # to the file and unzipped.
        self.assertRegex(run, r"curl[^\n]*\s-f", "the jacoco-cli curl must pass -f/--fail")
        self.assertRegex(run, r"curl[^\n]*--retry", "the jacoco-cli curl must retry")
        self.assertNotRegex(run, r"curl\s+-sL\s+-o",
                            "the old curl -sL (no -f) must be gone")

    def test_verifies_the_zip_before_unzipping(self):
        run = self._merge_step_run()
        self.assertIn("unzip -tq", run,
                      "analysis must test the archive integrity before unzipping it")

    def test_derives_the_jacoco_version_from_the_pom(self):
        """The version must be read from <jacoco.version> in the root pom, the
        same source the maven-cache warm step uses to prefetch the zip, so a
        jacoco upgrade cannot leave analysis looking for a version the cache does
        not hold (which would force the Central fetch this change removes)."""
        run = self._merge_step_run()
        self.assertRegex(
            run,
            r"JACOCO_VERSION=\$\(sed[^\n]*<jacoco\.version>[^\n]*pom\.xml",
            "analysis must derive JACOCO_VERSION from <jacoco.version> in pom.xml")
        self.assertNotRegex(
            run, r"JACOCO_VERSION=\d",
            "the jacoco-cli version must not be hardcoded in the analysis step")


if __name__ == "__main__":
    unittest.main()
