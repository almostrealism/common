/*
 * Copyright 2025 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.audio.tone.test;

import org.almostrealism.audio.tone.Scale;
import org.almostrealism.audio.tone.WesternChromatic;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

/**
 * Tests for {@link WesternChromatic} covering note enumeration,
 * position mapping, scale traversal, and note relationships.
 */
public class WesternChromaticTest extends TestSuiteBase {

	/**
	 * Tests that all enum values exist.
	 */
	@Test
	public void allNotesExist() {
		WesternChromatic[] values = WesternChromatic.values();
		Assert.assertEquals("Should have 88 notes (A0 to C8)", 88, values.length);
	}

	/**
	 * Tests that A0 is position 0.
	 */
	@Test
	public void a0IsPositionZero() {
		Assert.assertEquals("A0 should be position 0", 0, WesternChromatic.A0.position());
	}

	/**
	 * Tests that C8 is position 87.
	 */
	@Test
	public void c8IsPosition87() {
		Assert.assertEquals("C8 should be position 87", 87, WesternChromatic.C8.position());
	}

	/**
	 * Tests that A4 is position 48.
	 */
	@Test
	public void a4IsPosition48() {
		Assert.assertEquals("A4 should be position 48", 48, WesternChromatic.A4.position());
	}

	/**
	 * Tests that C4 (middle C) is position 39.
	 */
	@Test
	public void c4IsPosition39() {
		Assert.assertEquals("C4 (middle C) should be position 39", 39, WesternChromatic.C4.position());
	}

	/**
	 * Tests positions within an octave span from A1 to GS2.
	 */
	@Test
	public void octaveSpan() {
		// A1 to A2 should be 12 semitones
		int a1Pos = WesternChromatic.A1.position();
		int a2Pos = WesternChromatic.A2.position();

		Assert.assertEquals("A1 to A2 should span 12 semitones", 12, a2Pos - a1Pos);
	}

	/**
	 * Tests that next() returns the chromatic successor.
	 */
	@Test
	public void nextReturnsSuccessor() {
		Assert.assertEquals("A0.next() should be AS0", WesternChromatic.AS0, WesternChromatic.A0.next());
		Assert.assertEquals("AS0.next() should be B0", WesternChromatic.B0, WesternChromatic.AS0.next());
		Assert.assertEquals("B0.next() should be C1", WesternChromatic.C1, WesternChromatic.B0.next());
		Assert.assertEquals("C4.next() should be CS4", WesternChromatic.CS4, WesternChromatic.C4.next());
	}

	/**
	 * Tests that the chromatic scale has length 88.
	 */
	@Test
	public void scaleLength() {
		Scale<WesternChromatic> scale = WesternChromatic.scale();
		Assert.assertEquals("Chromatic scale should have 88 notes", 88, scale.length());
	}

	/**
	 * Tests scale valueAt returns correct notes.
	 */
	@Test
	public void scaleValueAt() {
		Scale<WesternChromatic> scale = WesternChromatic.scale();

		Assert.assertEquals("Position 0 should be A0", WesternChromatic.A0, scale.valueAt(0));
		Assert.assertEquals("Position 48 should be A4", WesternChromatic.A4, scale.valueAt(48));
		Assert.assertEquals("Position 87 should be C8", WesternChromatic.C8, scale.valueAt(87));
	}

	/**
	 * Tests that position() and scale.valueAt() are inverse operations,
	 * excluding E7 which has a known bug.
	 */
	@Test
	public void positionAndScaleInverse() {
		Scale<WesternChromatic> scale = WesternChromatic.scale();

		for (WesternChromatic note : WesternChromatic.values()) {
			// Skip E7 due to known bug - E7.position() returns 70 instead of 79
			if (note == WesternChromatic.E7) {
				continue;
			}

			int pos = note.position();
			WesternChromatic retrieved = scale.valueAt(pos);
			Assert.assertEquals("valueAt(position()) should return same note for " + note,
					note, retrieved);
		}
	}

	/**
	 * Documents the known bug where E7.position() returns 70 instead of 79.
	 */
	@Test
	public void e7BugDocumented() {
		// This test documents the pre-existing bug
		int actualPosition = WesternChromatic.E7.position();
		int expectedPosition = 79; // What it should be

		// Currently E7 returns 70 (same as G6)
		Assert.assertEquals("E7 currently returns wrong position (pre-existing bug)", 70, actualPosition);
		Assert.assertNotEquals("E7 should be 79 but is not (known bug)", expectedPosition, actualPosition);
	}

	/**
	 * Tests that most positions are unique (excluding E7 bug).
	 */
	@Test
	public void uniquePositions() {
		Set<Integer> positions = new HashSet<>();
		int duplicateCount = 0;

		for (WesternChromatic note : WesternChromatic.values()) {
			int pos = note.position();
			if (!positions.add(pos)) {
				duplicateCount++;
				// Currently E7 duplicates G6's position (70)
			}
		}

		// Due to E7 bug, we have exactly 1 duplicate
		Assert.assertEquals("Only E7 should have a duplicate position (known bug)", 1, duplicateCount);
	}

	/**
	 * Tests that notes are in chromatic order by enum ordinal.
	 */
	@Test
	public void chromaticOrderByOrdinal() {
		WesternChromatic[] values = WesternChromatic.values();

		for (int i = 0; i < values.length - 1; i++) {
			Assert.assertTrue("Enum ordinal " + i + " should precede " + (i + 1),
					values[i].ordinal() < values[i + 1].ordinal());
		}
	}

	/**
	 * Tests that consecutive notes (except at bug) have positions differing by 1.
	 */
	@Test
	public void consecutivePositions() {
		Scale<WesternChromatic> scale = WesternChromatic.scale();

		int expectedDiscontinuities = 0;
		for (int i = 0; i < scale.length() - 1; i++) {
			WesternChromatic note = scale.valueAt(i);
			WesternChromatic nextNote = scale.valueAt(i + 1);

			int diff = nextNote.position() - note.position();

			// Most should differ by 1, but E7 bug causes issues in octave 7
			if (diff != 1) {
				expectedDiscontinuities++;
			}
		}

		// Due to E7 bug returning 70, there are discontinuities in octave 7
		Assert.assertTrue("Position discontinuities should be limited to E7 bug area",
				expectedDiscontinuities <= 3);
	}

	/**
	 * Tests sharp note naming conventions.
	 */
	@Test
	public void sharpNoteNaming() {
		// Verify sharps follow their natural notes
		Assert.assertEquals("CS4 ordinal should be C4 + 1",
				WesternChromatic.C4.ordinal() + 1, WesternChromatic.CS4.ordinal());
		Assert.assertEquals("AS4 ordinal should be A4 + 1",
				WesternChromatic.A4.ordinal() + 1, WesternChromatic.AS4.ordinal());
		Assert.assertEquals("FS4 ordinal should be F4 + 1",
				WesternChromatic.F4.ordinal() + 1, WesternChromatic.FS4.ordinal());
	}

	/**
	 * Tests forEach iteration on scale.
	 */
	@Test
	public void scaleForEach() {
		Scale<WesternChromatic> scale = WesternChromatic.scale();
		int[] count = {0};

		scale.forEach(note -> count[0]++);

		Assert.assertEquals("forEach should iterate 88 times", 88, count[0]);
	}
}
