"""Tests for the rate reference and the circuit cut of the cell-type matrix.

The rate step must be the ``RatePopulation`` update the Java tests run, the
control draw must be the one ``CellTypeRateModelTest`` makes, and the cut must
be the sub-block of the full aggregate. These tests need numpy, which
studio/bioscience/scripts/requirements.txt lists; the aggregate is fed a small
in-memory table, so pyarrow is not needed.

Run from the repository root with:

    python3 -m unittest discover -s studio/bioscience/scripts -p "test_*.py"
"""

import math
import os
import sys
import tempfile
import unittest

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import male_cns  # noqa: E402
import male_cns_cell_types  # noqa: E402

CONSTANTS = {"dt": 1e-3, "tau": 0.02, "r_max": 100.0, "gain": 0.5, "drive": 50.0, "steps": 200}


class _Column:
    """A column of ``_Table`` with the ``to_numpy`` the aggregate reads."""

    def __init__(self, values):
        self.values = np.asarray(values, dtype=np.int64)

    def to_numpy(self):
        return self.values


class _Table:
    """The slice of the pyarrow table interface that ``aggregate`` uses."""

    def __init__(self, pre, post, count):
        self.columns = {male_cns.WEIGHT_PRE: pre, male_cns.WEIGHT_POST: post, male_cns.WEIGHT_COUNT: count}
        self.num_rows = len(pre)

    def slice(self, start, length):
        return _Table(*(self.columns[name][start:start + length] for name in
                        (male_cns.WEIGHT_PRE, male_cns.WEIGHT_POST, male_cns.WEIGHT_COUNT)))

    def __getitem__(self, name):
        return _Column(self.columns[name])


class JavaRandomTest(unittest.TestCase):
    """The port draws what ``new java.util.Random(20261026L).nextInt(bound)`` draws."""

    def test_matches_java(self):
        random = male_cns_cell_types.JavaRandom(20261026)
        # Recorded from java.util.Random(20261026L); 1 << 30 takes the power-of-two path and
        # 1,500,000,000 exercises the rejection loop.
        bounds = [7, 16, 1000, 3, 1 << 30, 1_500_000_000, 97]
        self.assertEqual([0, 6, 389, 1, 918785025, 113334809, 91], [random.next_int(b) for b in bounds])

    def test_bound_must_be_positive(self):
        with self.assertRaises(ValueError):
            male_cns_cell_types.JavaRandom(1).next_int(0)


class RateStepTest(unittest.TestCase):
    """One step is exponential Euler on the saturating rectifier."""

    def test_step_matches_equation(self):
        weights = np.array([[0.0, 2.0], [-3.0, 0.0]])
        scale = np.array([0.5, 0.25])
        rates = np.array([10.0, 20.0])
        drive = np.array([1.0, 0.0])
        decay = math.exp(-CONSTANTS["dt"] / CONSTANTS["tau"])
        expected = []
        for i in range(2):
            x = CONSTANTS["gain"] * scale[i] * sum(weights[i, j] * rates[j] for j in range(2)) + drive[i]
            f = CONSTANTS["r_max"] * math.tanh(max(x, 0.0) / CONSTANTS["r_max"])
            expected.append(decay * rates[i] + (1.0 - decay) * f)
        actual = male_cns_cell_types.rate_step(weights, scale, rates, drive, CONSTANTS)
        np.testing.assert_allclose(expected, actual, rtol=0, atol=1e-12)

    def test_no_drive_stays_silent(self):
        weights = np.ones((3, 3))
        final, peak, trace = male_cns_cell_types.rate_run(weights, np.ones(3), [], 0, CONSTANTS)
        self.assertEqual(0.0, np.abs(final).max())
        self.assertEqual(0.0, np.abs(peak).max())
        self.assertEqual(CONSTANTS["steps"], len(trace))


class CutTest(unittest.TestCase):
    """Restriction gives the sub-block; dropping silent types changes no other rate."""

    def test_restricted_aggregate_is_sub_block(self):
        body_ids = np.array([1, 2, 3, 4, 5], dtype=np.int64)
        types = ["a", "b", "b", "c", "d"]
        transmitters = {1: "acetylcholine", 2: "gaba", 3: "acetylcholine", 4: "glutamate", 5: None}
        pre = [1, 2, 3, 4, 5, 1, 4, 2]
        post = [2, 3, 1, 5, 4, 4, 1, 5]
        count = [3, 4, 5, 6, 7, 8, 9, 2]
        table = _Table(pre, post, count)
        names, full, _, _, _ = male_cns_cell_types.aggregate(body_ids, types, transmitters, table)

        kept = {"a", "b", "d"}
        restricted = male_cns_cell_types.restrict_bodies(body_ids, types, types, types, kept)
        cut_names, cut, _, _, _ = male_cns_cell_types.aggregate(restricted[0], restricted[1], transmitters, table)
        index = [names.index(n) for n in cut_names]
        self.assertEqual(sorted(kept), cut_names)
        np.testing.assert_array_equal(full[np.ix_(index, index)], cut)

    def test_dropping_silent_types_is_exact(self):
        # Type 3 only receives inhibition, so its rate stays zero; type 4 is unreachable.
        weights = np.array([[0.0, 0.0, 0.0, 0.0, 9.0],
                            [4.0, 0.0, 0.0, 5.0, 0.0],
                            [0.0, 6.0, 0.0, 7.0, 0.0],
                            [-2.0, -1.0, 0.0, 0.0, 0.0],
                            [0.0, 0.0, 0.0, 0.0, 0.0]])
        scale = np.ones(5)
        full, peak, _ = male_cns_cell_types.rate_run(weights, scale, [0], 2, CONSTANTS)
        self.assertEqual(0.0, peak[3])
        self.assertEqual(0.0, peak[4])

        kept, exact = male_cns_cell_types.select_types([peak], np.zeros(5), [0, 2], 3)
        self.assertTrue(exact)
        self.assertEqual([0, 1, 2], kept)
        cut, _, _ = male_cns_cell_types.rate_run(weights[np.ix_(kept, kept)], scale[kept], [0], 2, CONSTANTS)
        np.testing.assert_array_equal(full[kept], cut)

    def test_fallback_keeps_required_and_best_scored(self):
        peak = np.array([1.0, 2.0, 3.0, 4.0, 5.0])
        scores = np.array([0.0, 9.0, 1.0, 8.0, 2.0])
        kept, exact = male_cns_cell_types.select_types([peak], scores, [0], 3)
        self.assertFalse(exact)
        self.assertEqual([0, 1, 3], kept)

    def test_influence_follows_upstream_paths(self):
        # 0 -> 1 -> 2 (target); 3 -> 0 is three hops away; 4 is silent.
        coupling = np.zeros((5, 5))
        coupling[1, 0] = 1.0
        coupling[2, 1] = 2.0
        coupling[0, 3] = 1.0
        coupling[2, 4] = 5.0
        rates = np.array([1.0, 1.0, 1.0, 1.0, 0.0])
        scores = male_cns_cell_types.influence(coupling, rates, 2, 2)
        self.assertGreater(scores[1], 0.0)
        self.assertGreater(scores[0], 0.0)
        self.assertEqual(0.0, scores[3])
        self.assertEqual(0.0, scores[4])


class TextTensorsTest(unittest.TestCase):
    """The text layout of a committed circuit holds the same tensors as the safetensors file."""

    def test_round_trip(self):
        names = ["A", "B", "C"]
        matrix = np.array([[0, -3, 0], [12, 0, 4441], [0, 0, -1]], dtype=np.float32)
        sizes = np.array([3, 1, 2], dtype=np.int32)
        out = np.array([12116, 7, 0], dtype=np.int32)
        with tempfile.TemporaryDirectory() as directory:
            male_cns_cell_types.write_text_tensors(directory, names, matrix, sizes, out)
            with open(os.path.join(directory, male_cns_cell_types.WEIGHTS_TEXT_OUTPUT)) as source:
                lines = source.read().splitlines()
            self.assertEqual(["post\tpre\tweight", "0\t1\t-3", "1\t0\t12", "1\t2\t4441", "2\t2\t-1"], lines)
            tensors = male_cns_cell_types.read_text_tensors(directory)
        np.testing.assert_array_equal(matrix, tensors["weights"])
        np.testing.assert_array_equal(sizes, tensors["type_sizes"])
        np.testing.assert_array_equal(out, tensors["type_out_synapses"])


class MatchedControlTest(unittest.TestCase):
    """Sensory, non-gustatory, within 20% of outgoing synapses, never repeated."""

    def test_rule(self):
        superclasses = ["cb_sensory", "cb_sensory", "cb_sensory", "cb_motor", "ol_sensory", "cb_sensory"]
        classes = ["gustatory", "gustatory", "mechanosensory", "", "visual", "gustatory"]
        out = [100, 100, 110, 100, 85, 100]
        control = male_cns_cell_types.matched_control([0, 1], superclasses, classes, out, seed=5)
        self.assertEqual(2, len(control))
        self.assertEqual(2, len(set(control)))
        self.assertTrue(set(control) <= {2, 4})

    def test_no_candidate_fails(self):
        with self.assertRaises(SystemExit):
            male_cns_cell_types.matched_control([0], ["cb_sensory"], ["gustatory"], [10])

    def test_experiment_drives_grns_and_their_control(self):
        # One candidate per GRN type: only type 4 + i is within 20% of GRN type i.
        names = ["LB3a", "LB3b", "LB3c", "LB3d", "ca", "cb", "cc", "cd", "MN9"]
        superclasses = ["cb_sensory"] * 8 + ["cb_motor"]
        classes = ["gustatory"] * 4 + ["mechanosensory"] * 4 + [""]
        out = [100, 200, 400, 800, 110, 210, 410, 810, 100]
        driven = male_cns_cell_types.experiment_driven(names, superclasses, classes, out)
        self.assertEqual({"baseline": [], "grn": ["LB3a", "LB3b", "LB3c", "LB3d"],
                          "control": ["ca", "cb", "cc", "cd"]}, driven)


if __name__ == "__main__":
    unittest.main()
