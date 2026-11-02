"""Tests for the compressed-sparse-row conversion of neuron-level connectomes.

``compressed_rows`` decides which presynaptic neuron and value the Java loader
reads at every position, and the synthetic conversion is the path the committed
Java test fixture is written by. Both are pinned here. These tests need
numpy, which studio/bioscience/scripts/requirements.txt lists.

Run from the repository root with:

    python3 -m unittest discover -s studio/bioscience/scripts -p "test_*.py"
"""

import os
import sys
import tempfile
import unittest

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import brian2_reference  # noqa: E402
import connectome_csr  # noqa: E402
import male_cns  # noqa: E402


class CompressedRowsTest(unittest.TestCase):
    """Rows are postsynaptic neurons, entries sorted by presynaptic neuron."""

    def test_rows_are_postsynaptic(self):
        pre = np.array([2, 0, 1, 2], dtype=np.int64)
        post = np.array([0, 2, 0, 1], dtype=np.int64)
        values = np.array([5, -3, 7, 1], dtype=np.int64)
        offsets, columns, ordered = connectome_csr.compressed_rows(4, pre, post, values)
        self.assertEqual([0, 2, 3, 4, 4], offsets.tolist())
        self.assertEqual([1, 2, 2, 0], columns.tolist())
        self.assertEqual([7, 5, 1, -3], ordered.tolist())

    def test_empty(self):
        empty = np.zeros(0, dtype=np.int64)
        offsets, columns, ordered = connectome_csr.compressed_rows(3, empty, empty, empty)
        self.assertEqual([0, 0, 0, 0], offsets.tolist())
        self.assertEqual(0, len(columns))

    def test_duplicate_edge(self):
        pre = np.array([1, 1], dtype=np.int64)
        post = np.array([0, 0], dtype=np.int64)
        with self.assertRaises(ValueError):
            connectome_csr.compressed_rows(2, pre, post, np.array([1, 2], dtype=np.int64))

    def test_index_out_of_range(self):
        with self.assertRaises(ValueError):
            connectome_csr.compressed_rows(2, np.array([2], dtype=np.int64),
                                           np.array([0], dtype=np.int64), np.array([1], dtype=np.int64))


class SyntheticConversionTest(unittest.TestCase):
    """The synthetic dataset converts with spot checks that agree with its rows."""

    def test_convert(self):
        with tempfile.TemporaryDirectory() as output:
            metadata = connectome_csr.convert(connectome_csr.synthetic(40), output, 1, {}, "test")
            self.assertEqual(40, metadata["neurons"])
            self.assertEqual(metadata["edges"], metadata["excitatory_edges"] + metadata["inhibitory_edges"])
            for name in (connectome_csr.TENSORS_OUTPUT, connectome_csr.NEURONS_OUTPUT,
                         connectome_csr.SPOT_CHECKS_OUTPUT, "SHA256SUMS"):
                self.assertTrue(os.path.exists(os.path.join(output, name)), name)

    def test_min_count_drops_edges(self):
        with tempfile.TemporaryDirectory() as output:
            everything = connectome_csr.convert(connectome_csr.synthetic(40), output, 1, {}, "test")
        with tempfile.TemporaryDirectory() as output:
            strong = connectome_csr.convert(connectome_csr.synthetic(40), output, 10, {}, "test")
        self.assertLess(strong["edges"], everything["edges"])


class SubsetTest(unittest.TestCase):
    """A subset keeps the chosen neurons in order and exactly the edges joining two of them."""

    def test_induced_edges(self):
        full = connectome_csr.synthetic(40)
        keep = np.zeros(40, dtype=bool)
        keep[[3, 7, 8, 20, 31]] = True
        part = full.subset("part", keep, "five neurons")
        self.assertEqual(full.body_ids[keep].tolist(), part.body_ids.tolist())
        self.assertEqual(full.signs[keep].tolist(), part.signs.tolist())
        self.assertEqual(5, len(part.annotations))

        joined = keep[full.pre] & keep[full.post]
        expected = sorted(zip(full.body_ids[full.pre[joined]].tolist(),
                              full.body_ids[full.post[joined]].tolist(), full.count[joined].tolist()))
        actual = sorted(zip(part.body_ids[part.pre].tolist(), part.body_ids[part.post].tolist(),
                            part.count.tolist()))
        self.assertEqual(expected, actual)
        self.assertIn("five neurons", part.description["neurons"])

    def test_convert_spot_checks_the_subset(self):
        keep = np.arange(40) % 2 == 0
        part = connectome_csr.synthetic(40).subset("part", keep, "even neurons")
        with tempfile.TemporaryDirectory() as output:
            metadata = connectome_csr.convert(part, output, 1, {}, "test")
        self.assertEqual(20, metadata["neurons"])
        self.assertEqual(len(part.count), metadata["edges"])


class SafetensorsRoundTripTest(unittest.TestCase):
    """What write_safetensors writes, read_safetensors reads back exactly."""

    def test_round_trip(self):
        with tempfile.TemporaryDirectory() as directory:
            path = os.path.join(directory, "t.safetensors")
            ints = np.array([[1, -2, 3], [4, 5, -6]])
            floats = np.array([0.5, -1.25])
            male_cns.write_safetensors(path, [("i", "I32", ints), ("f", "F32", floats)])
            tensors = male_cns.read_safetensors(path)
            self.assertEqual(ints.tolist(), tensors["i"].tolist())
            self.assertEqual(floats.tolist(), tensors["f"].tolist())


class KickScheduleTest(unittest.TestCase):
    """The Brian2 fixture's kick schedule: seeded, and only on the driven neurons."""

    def test_schedule(self):
        drive = brian2_reference.kick_schedule(10, (2, 5), 4000, 150.0, 3)
        self.assertEqual((4000, 10), drive.shape)
        self.assertEqual({0.0, brian2_reference.KICK_MV}, set(np.unique(drive).tolist()))
        self.assertEqual({2, 5}, set(np.nonzero(drive)[1].tolist()))
        kicks = (drive > 0).sum()
        self.assertAlmostEqual(2 * 4000 * 0.015, kicks, delta=4 * np.sqrt(2 * 4000 * 0.015))
        np.testing.assert_array_equal(drive, brian2_reference.kick_schedule(10, (2, 5), 4000, 150.0, 3))


class EventsTest(unittest.TestCase):
    """The references store a run as (step, neuron) events, from which the dense run is exact."""

    def test_events(self):
        array = np.zeros((4, 3))
        array[0, 2] = 1
        array[2, 0] = 68.75
        array[2, 1] = 1
        self.assertEqual([[0, 2], [2, 0], [2, 1]], brian2_reference.events(array).tolist())

    def test_no_events(self):
        self.assertEqual((0, 2), brian2_reference.events(np.zeros((5, 4))).shape)

    def test_compressed_steps(self):
        array = np.zeros((4, 3))
        array[0, 2] = 1.5
        array[2, 0] = 68.75
        array[2, 1] = 2.0
        offsets, neurons, values = brian2_reference.compressed_steps(array)
        self.assertEqual([0, 1, 1, 3, 3], offsets.tolist())
        self.assertEqual([2, 0, 1], neurons.tolist())
        self.assertEqual([1.5, 68.75, 2.0], values.tolist())
        rebuilt = np.zeros_like(array)
        for step in range(4):
            row = slice(offsets[step], offsets[step + 1])
            rebuilt[step, neurons[row]] = values[row]
        np.testing.assert_array_equal(array, rebuilt)


if __name__ == "__main__":
    unittest.main()
