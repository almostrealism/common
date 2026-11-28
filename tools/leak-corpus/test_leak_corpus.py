#!/usr/bin/env python3
"""Tests for the adversarial leak corpus, and a coverage snapshot of the guard.

Two jobs:

1. **Corpus integrity** — prove the corpus is well-formed and that nothing it
   commits is itself a publishable secret (every link and id is built from
   fragments at run time, per the red-team task).

2. **Detector coverage snapshot** — run every case through the private-link
   detector that exists today (``.claude/hooks/lib/private_url_check.py``) and
   record what it catches and what slips through. A plain, unobfuscated literal
   that the detector already understands must stay caught — that is a
   regression guard (``DetectorRegressionTests``). The obfuscation families the
   detector cannot yet see through are asserted, one family per method, through
   ``unittest.expectedFailure`` (``DetectorGapTests``): today each is an
   *expected failure*, so the suite is green; when the guard learns to
   normalise that family the assertion starts passing, unittest reports an
   *unexpected success*, and whoever strengthened the guard removes the
   decorator. The gaps thus ratchet shut instead of being forgotten.

The detector is loaded and exercised only as data; this test never publishes
anything and never performs a GitHub or network write.
"""
import importlib.util
import os
import unicodedata
import unittest

import leak_corpus


HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
DETECTOR_PATH = os.path.join(REPO, ".claude", "hooks", "lib", "private_url_check.py")

# The allowlist entries the detector's lab-host check needs; mirrors the fixture
# in .claude/hooks/lib/test_private_url_check.py so this test does not depend on
# the committed allowlist file.
ENTRIES = ["amd-halo", "amd-halo.local", "192.168.8.0/24", "100.64.0.0/10",
           "*.docker.internal", "*.example-tailnet.ts.net"]


def _load_detector():
    spec = importlib.util.spec_from_file_location("private_url_check", DETECTOR_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


detector = _load_detector()


def detected(text):
    """True when today's detector would flag ``text`` as a private link."""
    return bool(detector.private_links(text, ENTRIES))


def cases_of_kind(kind):
    return [case for case in leak_corpus.all_cases() if case.kind == kind]


class CorpusIntegrityTests(unittest.TestCase):

    def test_every_family_contributes_cases(self):
        self.assertTrue(leak_corpus.all_cases())
        self.assertGreaterEqual(len(leak_corpus.kinds()), 15)

    def test_the_corpus_source_holds_no_literal_secret(self):
        leak_corpus.assert_no_literal_secret_in_source(leak_corpus.__file__)

    def test_this_test_source_holds_no_literal_secret(self):
        leak_corpus.assert_no_literal_secret_in_source(os.path.abspath(__file__))

    def test_canonical_tokens_are_plain(self):
        """Each canonical token is ASCII, NFKC-stable and free of zero-width."""
        for case in leak_corpus.all_cases():
            canonical = case.canonical
            self.assertEqual(canonical, unicodedata.normalize("NFKC", canonical), case)
            for zero_width in leak_corpus.ZERO_WIDTH:
                self.assertNotIn(zero_width, canonical, case)

    def test_obfuscated_payloads_differ_from_their_canonical(self):
        """A case that claims a normalisation step must actually disguise the token."""
        for case in leak_corpus.all_cases():
            if case.normalizer_required:
                self.assertNotEqual(case.payload, case.canonical, case)

    def test_cases_are_rebuilt_fresh_each_call(self):
        """all_cases() is a pure generator over fragments, equal across calls."""
        self.assertEqual(leak_corpus.all_cases(), leak_corpus.all_cases())

    def test_branch_name_surface_is_present(self):
        """Branch names are a surface the detector's routing does not scan today."""
        self.assertTrue(leak_corpus.cases_for_surface("branch_name"))


class DetectorRegressionTests(unittest.TestCase):
    """A plain, literal, already-understood token must never stop being caught."""

    def test_literal_unobfuscated_tokens_stay_blocked(self):
        checked = 0
        for case in leak_corpus.all_cases():
            if case.normalizer_required:
                continue
            if case.canonical not in case.payload:
                continue
            if not detected(case.canonical):
                continue
            checked += 1
            self.assertTrue(detected(case.payload),
                            "detector regressed on a literal token: %r" % (case,))
        self.assertGreater(checked, 0)

    def test_the_plain_session_url_is_blocked(self):
        for case in cases_of_kind("url_form"):
            self.assertTrue(detected(case.payload), case)


class DetectorGapTests(unittest.TestCase):
    """One method per obfuscation family the detector cannot yet see through.

    Each asserts the detector blocks every case in the family. The decorator
    says the assertion is expected to fail against today's detector; remove it
    for a family once the guard normalises that family (unittest will flag the
    unexpected success until you do).
    """

    def _assert_family_blocked(self, kind):
        cases = cases_of_kind(kind)
        self.assertTrue(cases, "no cases for kind %r" % kind)
        for case in cases:
            self.assertTrue(detected(case.payload),
                            "detector let a %s payload through: %r" % (kind, case))

    @unittest.expectedFailure
    def test_should_block_url_encoded(self):
        self._assert_family_blocked("url_encoded")

    @unittest.expectedFailure
    def test_should_block_url_encoded_double(self):
        self._assert_family_blocked("url_encoded_double")

    @unittest.expectedFailure
    def test_should_block_html_entity_decimal(self):
        self._assert_family_blocked("html_entity_decimal")

    @unittest.expectedFailure
    def test_should_block_html_entity_hex(self):
        self._assert_family_blocked("html_entity_hex")

    @unittest.expectedFailure
    def test_should_block_html_entity_dot(self):
        self._assert_family_blocked("html_entity_dot")

    @unittest.expectedFailure
    def test_should_block_nfkc_fullwidth(self):
        self._assert_family_blocked("nfkc_fullwidth")

    @unittest.expectedFailure
    def test_should_block_homoglyph(self):
        self._assert_family_blocked("homoglyph")

    @unittest.expectedFailure
    def test_should_block_zero_width(self):
        self._assert_family_blocked("zero_width")

    @unittest.expectedFailure
    def test_should_block_line_split(self):
        self._assert_family_blocked("line_split")

    @unittest.expectedFailure
    def test_should_block_line_split_hyphen(self):
        self._assert_family_blocked("line_split_hyphen")

    @unittest.expectedFailure
    def test_should_block_case_folded_id(self):
        self._assert_family_blocked("case_folded_id")

    @unittest.expectedFailure
    def test_should_block_bare_host_no_scheme(self):
        self._assert_family_blocked("bare_host_no_scheme")

    @unittest.expectedFailure
    def test_should_block_host_dash_munge(self):
        self._assert_family_blocked("host_dash_munge")

    @unittest.expectedFailure
    def test_should_block_credential(self):
        self._assert_family_blocked("credential")

    @unittest.expectedFailure
    def test_should_block_stacked_zw_percent(self):
        self._assert_family_blocked("stacked_zw_percent")

    @unittest.expectedFailure
    def test_should_block_stacked_homoglyph_split(self):
        self._assert_family_blocked("stacked_homoglyph_split")


class CoverageSnapshotTests(unittest.TestCase):
    """Record the caught/missed split so the corpus doubles as a gap report."""

    def test_known_gap_families_are_currently_missed(self):
        """These families are the filed gaps: every case slips through today.

        If one starts being caught, the detector gained a normalisation and the
        matching expectedFailure above will flip to an unexpected success.
        """
        gap_kinds = ["url_encoded", "html_entity_decimal", "nfkc_fullwidth",
                     "homoglyph", "zero_width", "line_split", "case_folded_id",
                     "bare_host_no_scheme", "host_dash_munge", "credential"]
        for kind in gap_kinds:
            for case in cases_of_kind(kind):
                self.assertFalse(detected(case.payload),
                                 "a gap family is now caught (good — update the "
                                 "expectedFailure test): %r" % (case,))


if __name__ == "__main__":
    unittest.main()
