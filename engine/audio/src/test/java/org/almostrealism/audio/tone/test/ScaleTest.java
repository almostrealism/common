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
import org.almostrealism.audio.tone.SetIntervalScale;
import org.almostrealism.audio.tone.StaticScale;
import org.almostrealism.audio.tone.WesternChromatic;
import org.almostrealism.audio.tone.WesternScales;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests for {@link Scale} and its implementations including
 * {@link StaticScale}, {@link SetIntervalScale}, and {@link WesternScales}.
 */
public class ScaleTest extends TestSuiteBase {

	/**
	 * Tests Scale.of() factory method with varargs.
	 */
	@Test(timeout = 5000)
	public void scaleOfFactory() {
		Scale<WesternChromatic> scale = Scale.of(
				WesternChromatic.C4, WesternChromatic.E4, WesternChromatic.G4);

		Assert.assertEquals("Scale should have 3 notes", 3, scale.length());
		Assert.assertEquals("First note should be C4", WesternChromatic.C4, scale.valueAt(0));
		Assert.assertEquals("Second note should be E4", WesternChromatic.E4, scale.valueAt(1));
		Assert.assertEquals("Third note should be G4", WesternChromatic.G4, scale.valueAt(2));
	}

	/**
	 * Tests StaticScale construction and access.
	 */
	@Test(timeout = 5000)
	public void staticScaleBasics() {
		WesternChromatic[] notes = {WesternChromatic.C4, WesternChromatic.D4, WesternChromatic.E4};
		StaticScale<WesternChromatic> scale = new StaticScale<>(notes);

		Assert.assertEquals("Length should match array size", 3, scale.length());
		Assert.assertEquals("valueAt(0) should return first note", WesternChromatic.C4, scale.valueAt(0));
	}

	/**
	 * Tests StaticScale setNotes with List.
	 */
	@Test(timeout = 5000)
	public void staticScaleSetNotes() {
		StaticScale<WesternChromatic> scale = new StaticScale<>();
		scale.setNotes(List.of(WesternChromatic.A4, WesternChromatic.B4, WesternChromatic.C5));

		Assert.assertEquals("Scale should have 3 notes", 3, scale.length());
		Assert.assertEquals("First note should be A4", WesternChromatic.A4, scale.valueAt(0));
	}

	/**
	 * Tests C major scale has correct notes.
	 */
	@Test(timeout = 5000)
	public void cMajorScale() {
		Scale<WesternChromatic> cMajor = WesternScales.major(WesternChromatic.C4, 1);

		// C major scale: C, D, E, F, G, A, B (intervals: 2, 2, 1, 2, 2, 2, 1)
		Assert.assertEquals("C4 should be root", WesternChromatic.C4, cMajor.valueAt(0));
		Assert.assertEquals("D4 should be 2nd", WesternChromatic.D4, cMajor.valueAt(1));
		Assert.assertEquals("E4 should be 3rd", WesternChromatic.E4, cMajor.valueAt(2));
		Assert.assertEquals("F4 should be 4th", WesternChromatic.F4, cMajor.valueAt(3));
		Assert.assertEquals("G4 should be 5th", WesternChromatic.G4, cMajor.valueAt(4));
		Assert.assertEquals("A4 should be 6th", WesternChromatic.A4, cMajor.valueAt(5));
		Assert.assertEquals("B4 should be 7th", WesternChromatic.B4, cMajor.valueAt(6));
	}

	/**
	 * Tests G major scale has correct notes.
	 */
	@Test(timeout = 5000)
	public void gMajorScale() {
		Scale<WesternChromatic> gMajor = WesternScales.major(WesternChromatic.G3, 1);

		// G major scale: G, A, B, C, D, E, F#
		Assert.assertEquals("G3 should be root", WesternChromatic.G3, gMajor.valueAt(0));
		Assert.assertEquals("A3 should be 2nd", WesternChromatic.A3, gMajor.valueAt(1));
		Assert.assertEquals("B3 should be 3rd", WesternChromatic.B3, gMajor.valueAt(2));
		Assert.assertEquals("C4 should be 4th", WesternChromatic.C4, gMajor.valueAt(3));
		Assert.assertEquals("D4 should be 5th", WesternChromatic.D4, gMajor.valueAt(4));
		Assert.assertEquals("E4 should be 6th", WesternChromatic.E4, gMajor.valueAt(5));
		Assert.assertEquals("FS4 should be 7th (F#)", WesternChromatic.FS4, gMajor.valueAt(6));
	}

	/**
	 * Tests A minor scale has correct notes.
	 */
	@Test(timeout = 5000)
	public void aMinorScale() {
		Scale<WesternChromatic> aMinor = WesternScales.minor(WesternChromatic.A3, 1);

		// A natural minor scale: A, B, C, D, E, F, G (intervals: 2, 1, 2, 2, 1, 2, 2)
		Assert.assertEquals("A3 should be root", WesternChromatic.A3, aMinor.valueAt(0));
		Assert.assertEquals("B3 should be 2nd", WesternChromatic.B3, aMinor.valueAt(1));
		Assert.assertEquals("C4 should be 3rd", WesternChromatic.C4, aMinor.valueAt(2));
		Assert.assertEquals("D4 should be 4th", WesternChromatic.D4, aMinor.valueAt(3));
		Assert.assertEquals("E4 should be 5th", WesternChromatic.E4, aMinor.valueAt(4));
		Assert.assertEquals("F4 should be 6th", WesternChromatic.F4, aMinor.valueAt(5));
		Assert.assertEquals("G4 should be 7th", WesternChromatic.G4, aMinor.valueAt(6));
	}

	/**
	 * Tests major scale length is correct for multiple octaves.
	 */
	@Test(timeout = 5000)
	public void majorScaleLength() {
		Scale<WesternChromatic> oneOctave = WesternScales.major(WesternChromatic.C4, 1);
		Scale<WesternChromatic> twoOctaves = WesternScales.major(WesternChromatic.C4, 2);

		Assert.assertEquals("One octave major should have 7 notes", 7, oneOctave.length());
		Assert.assertEquals("Two octave major should have 14 notes", 14, twoOctaves.length());
	}

	/**
	 * Tests minor scale length is correct for multiple octaves.
	 */
	@Test(timeout = 5000)
	public void minorScaleLength() {
		Scale<WesternChromatic> oneOctave = WesternScales.minor(WesternChromatic.A3, 1);
		Scale<WesternChromatic> threeOctaves = WesternScales.minor(WesternChromatic.A3, 3);

		Assert.assertEquals("One octave minor should have 7 notes", 7, oneOctave.length());
		Assert.assertEquals("Three octave minor should have 21 notes", 21, threeOctaves.length());
	}

	/**
	 * Tests SetIntervalScale root getter/setter.
	 */
	@Test(timeout = 5000)
	public void setIntervalScaleRoot() {
		SetIntervalScale<WesternChromatic> scale = new SetIntervalScale<>();
		scale.setRoot(WesternChromatic.D4);
		scale.setIntervals(new int[]{2, 2, 1, 2, 2, 2, 1});
		scale.setRepetitions(1);

		Assert.assertEquals("Root should be D4", WesternChromatic.D4, scale.getRoot());
		Assert.assertEquals("valueAt(0) should return root", WesternChromatic.D4, scale.valueAt(0));
	}

	/**
	 * Tests SetIntervalScale with string root setter.
	 */
	@Test(timeout = 5000)
	public void setIntervalScaleStringRoot() {
		SetIntervalScale<WesternChromatic> scale = new SetIntervalScale<>();
		scale.setRoot("E4");
		scale.setIntervals(new int[]{2, 2, 1, 2, 2, 2, 1});
		scale.setRepetitions(1);

		Assert.assertEquals("Root should be E4", WesternChromatic.E4, scale.getRoot());
	}

	/**
	 * Tests scale forEach iteration.
	 */
	@Test(timeout = 5000)
	public void forEachIteration() {
		Scale<WesternChromatic> scale = Scale.of(
				WesternChromatic.C4, WesternChromatic.D4, WesternChromatic.E4);

		List<WesternChromatic> collected = new ArrayList<>();
		scale.forEach(collected::add);

		Assert.assertEquals("Should collect 3 notes", 3, collected.size());
		Assert.assertEquals("First collected should be C4", WesternChromatic.C4, collected.get(0));
		Assert.assertEquals("Second collected should be D4", WesternChromatic.D4, collected.get(1));
		Assert.assertEquals("Third collected should be E4", WesternChromatic.E4, collected.get(2));
	}

	/**
	 * Tests that major and minor scales starting on same root differ.
	 */
	@Test(timeout = 5000)
	public void majorVsMinorDifferent() {
		Scale<WesternChromatic> cMajor = WesternScales.major(WesternChromatic.C4, 1);
		Scale<WesternChromatic> cMinor = WesternScales.minor(WesternChromatic.C4, 1);

		// Root is same
		Assert.assertEquals("Both scales start on C4", cMajor.valueAt(0), cMinor.valueAt(0));

		// Third is different (E4 in major, DS4/Eb4 in minor)
		Assert.assertEquals("Major 3rd should be E4", WesternChromatic.E4, cMajor.valueAt(2));
		Assert.assertEquals("Minor 3rd should be DS4 (Eb)", WesternChromatic.DS4, cMinor.valueAt(2));
	}

	/**
	 * Tests chromatic scale spans 12 semitones per octave.
	 */
	@Test(timeout = 5000)
	public void chromaticScaleSpan() {
		Scale<WesternChromatic> chromatic = WesternChromatic.scale();

		// Position 0 to position 12 should span an octave (A0 to A1)
		Assert.assertEquals("Position 0 should be A0", WesternChromatic.A0, chromatic.valueAt(0));
		Assert.assertEquals("Position 12 should be A1", WesternChromatic.A1, chromatic.valueAt(12));
	}

	/**
	 * Tests SetIntervalScale with 2 octaves (positions beyond intervals.length).
	 * Verifies the fix for the UnsupportedOperationException that was previously
	 * thrown for positions greater than intervals.length.
	 */
	@Test(timeout = 5000)
	public void twoOctaveMajorScale() {
		Scale<WesternChromatic> twoOctaves = WesternScales.major(WesternChromatic.C4, 2);

		Assert.assertEquals("Length should be 14", 14, twoOctaves.length());

		// First octave: C4, D4, E4, F4, G4, A4, B4
		Assert.assertEquals("Position 0 should be C4", WesternChromatic.C4, twoOctaves.valueAt(0));
		Assert.assertEquals("Position 6 should be B4", WesternChromatic.B4, twoOctaves.valueAt(6));

		// Second octave starts at position 7: C5, D5, E5, F5, G5, A5, B5
		Assert.assertEquals("Position 7 should be C5", WesternChromatic.C5, twoOctaves.valueAt(7));
		Assert.assertEquals("Position 8 should be D5", WesternChromatic.D5, twoOctaves.valueAt(8));
		Assert.assertEquals("Position 9 should be E5", WesternChromatic.E5, twoOctaves.valueAt(9));
		Assert.assertEquals("Position 10 should be F5", WesternChromatic.F5, twoOctaves.valueAt(10));
		Assert.assertEquals("Position 11 should be G5", WesternChromatic.G5, twoOctaves.valueAt(11));
		Assert.assertEquals("Position 12 should be A5", WesternChromatic.A5, twoOctaves.valueAt(12));
		Assert.assertEquals("Position 13 should be B5", WesternChromatic.B5, twoOctaves.valueAt(13));
	}

	/**
	 * Tests SetIntervalScale with 3 octaves of a minor scale.
	 */
	@Test(timeout = 5000)
	public void threeOctaveMinorScale() {
		Scale<WesternChromatic> threeOctaves = WesternScales.minor(WesternChromatic.A3, 3);

		Assert.assertEquals("Length should be 21", 21, threeOctaves.length());

		// First octave ends with G4
		Assert.assertEquals("Root should be A3", WesternChromatic.A3, threeOctaves.valueAt(0));
		Assert.assertEquals("Position 6 should be G4", WesternChromatic.G4, threeOctaves.valueAt(6));

		// Second octave: A4, B4, C5, D5, E5, F5, G5
		Assert.assertEquals("Position 7 should be A4", WesternChromatic.A4, threeOctaves.valueAt(7));
		Assert.assertEquals("Position 13 should be G5", WesternChromatic.G5, threeOctaves.valueAt(13));

		// Third octave: A5, B5, C6, D6, E6, F6, G6
		Assert.assertEquals("Position 14 should be A5", WesternChromatic.A5, threeOctaves.valueAt(14));
		Assert.assertEquals("Position 20 should be G6", WesternChromatic.G6, threeOctaves.valueAt(20));
	}

	/**
	 * Tests that forEach works correctly with multi-octave scales.
	 */
	@Test(timeout = 5000)
	public void forEachMultiOctave() {
		Scale<WesternChromatic> twoOctaves = WesternScales.major(WesternChromatic.C4, 2);
		List<WesternChromatic> collected = new ArrayList<>();
		twoOctaves.forEach(collected::add);

		Assert.assertEquals("Should collect 14 notes", 14, collected.size());
		Assert.assertEquals("First should be C4", WesternChromatic.C4, collected.get(0));
		Assert.assertEquals("Last should be B5", WesternChromatic.B5, collected.get(13));
	}

	/**
	 * Tests that intervallic relationship is consistent across octaves.
	 */
	@Test(timeout = 5000)
	public void intervalConsistencyAcrossOctaves() {
		// Major third from C should always be E
		Scale<WesternChromatic> c3Major = WesternScales.major(WesternChromatic.C3, 1);
		Scale<WesternChromatic> c4Major = WesternScales.major(WesternChromatic.C4, 1);
		Scale<WesternChromatic> c5Major = WesternScales.major(WesternChromatic.C5, 1);

		Assert.assertEquals("C3 major third should be E3", WesternChromatic.E3, c3Major.valueAt(2));
		Assert.assertEquals("C4 major third should be E4", WesternChromatic.E4, c4Major.valueAt(2));
		Assert.assertEquals("C5 major third should be E5", WesternChromatic.E5, c5Major.valueAt(2));
	}
}
