"""Tests for the standard-library parts of the MaleCNS table reader.

The transmitter sign rule decides the sign of every column of the cell-type
matrix, and the safetensors header decides whether the Java loader reads the
tensors at the right offsets. Both are pinned here without numpy or pyarrow.

Run from the repository root with:

    python3 -m unittest discover -s studio/bioscience/scripts -p "test_*.py"
"""

import json
import os
import struct
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import male_cns  # noqa: E402


class TransmitterSignTest(unittest.TestCase):
    """The Shiu et al. rule: only GABA and glutamate are inhibitory."""

    def test_inhibitory(self):
        self.assertEqual(-1, male_cns.transmitter_sign("gaba"))
        self.assertEqual(-1, male_cns.transmitter_sign("glutamate"))
        self.assertEqual(-1, male_cns.transmitter_sign("GABA"))

    def test_excitatory(self):
        for transmitter in ("acetylcholine", "dopamine", "serotonin", "octopamine",
                            "tyramine", "histamine", "unclear"):
            self.assertEqual(1, male_cns.transmitter_sign(transmitter), transmitter)

    def test_missing_prediction_is_excitatory(self):
        self.assertEqual(1, male_cns.transmitter_sign(None))


class MajorityAnnotationsTest(unittest.TestCase):
    """Each type takes the most common non-empty annotation of its members."""

    def test_most_common_wins(self):
        result = male_cns.majority_annotations(
            ["A", "A", "A", "B"],
            ["cb_sensory", "cb_sensory", "cb_intrinsic", None],
            ["gustatory", None, "gustatory", None])
        self.assertEqual(("cb_sensory", "gustatory"), result["A"])
        self.assertEqual(("", ""), result["B"])

    def test_ties_break_alphabetically(self):
        result = male_cns.majority_annotations(["A", "A"], ["y", "x"], ["", ""])
        self.assertEqual(("x", ""), result["A"])


class SafetensorsHeaderTest(unittest.TestCase):
    """The header layout the Java safetensors reader expects."""

    def parse(self, tensors):
        encoded = male_cns.safetensors_header(tensors)
        (length,) = struct.unpack("<Q", encoded[:8])
        self.assertEqual(len(encoded), 8 + length)
        return encoded, json.loads(encoded[8:].decode("utf-8"))

    def test_offsets_are_consecutive(self):
        _, header = self.parse([("weights", "F32", (3, 3)), ("type_sizes", "I32", (3,)),
                                ("type_out_synapses", "I32", (3,))])
        self.assertEqual([0, 36], header["weights"]["data_offsets"])
        self.assertEqual([36, 48], header["type_sizes"]["data_offsets"])
        self.assertEqual([48, 60], header["type_out_synapses"]["data_offsets"])
        self.assertEqual([3, 3], header["weights"]["shape"])
        self.assertEqual("I32", header["type_sizes"]["dtype"])

    def test_data_starts_aligned(self):
        for name in ("a", "ab", "abc", "abcd", "abcde", "abcdef", "abcdefg", "abcdefgh"):
            encoded, _ = self.parse([(name, "F64", (1,))])
            self.assertEqual(0, len(encoded) % 8, name)

    def test_unsupported_dtype(self):
        with self.assertRaises(ValueError):
            male_cns.safetensors_header([("x", "U8", (1,))])


if __name__ == "__main__":
    unittest.main()
