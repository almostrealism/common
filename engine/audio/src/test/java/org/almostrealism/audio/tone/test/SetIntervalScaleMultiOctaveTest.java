/*
 * Copyright 2026 Michael Murray
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
import org.almostrealism.audio.tone.WesternScales;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests for {@link org.almostrealism.audio.tone.SetIntervalScale} multi-octave
 * support, verifying the fix for the {@link UnsupportedOperationException} that
 * was previously thrown for positions beyond the interval array length.
 */
public class SetIntervalScaleMultiOctaveTest extends TestSuiteBase {

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
}
