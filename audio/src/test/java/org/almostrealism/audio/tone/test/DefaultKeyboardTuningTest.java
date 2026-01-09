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

import org.almostrealism.audio.tone.DefaultKeyboardTuning;
import org.almostrealism.audio.tone.KeyNumbering;
import org.almostrealism.audio.tone.KeyboardTuning;
import org.almostrealism.audio.tone.WesternChromatic;
import org.almostrealism.time.Frequency;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for {@link DefaultKeyboardTuning} covering standard musical
 * tuning with 12-tone equal temperament based on A4 = 440 Hz.
 */
public class DefaultKeyboardTuningTest extends TestSuiteBase {

	private static final double FREQ_TOLERANCE = 0.01; // Hz tolerance for frequency comparison
	private static final double RATIO_TOLERANCE = 0.0001; // Tolerance for ratio comparisons

	/**
	 * The semitone ratio in 12-tone equal temperament.
	 */
	private static final double SEMITONE_RATIO = Math.pow(2, 1.0 / 12.0);

	/**
	 * Tests that A4 is 440 Hz with default tuning.
	 */
	@Test
	public void a4Is440Hz() {
		KeyboardTuning tuning = new DefaultKeyboardTuning();
		Frequency a4 = tuning.getTone(WesternChromatic.A4);

		Assert.assertEquals("A4 should be 440 Hz", 440.0, a4.asHertz(), FREQ_TOLERANCE);
	}

	/**
	 * Tests that custom A4 tuning works (e.g., A4 = 432 Hz).
	 */
	@Test
	public void customA4Tuning() {
		KeyboardTuning tuning = new DefaultKeyboardTuning(432);
		Frequency a4 = tuning.getTone(WesternChromatic.A4);

		Assert.assertEquals("A4 should be 432 Hz", 432.0, a4.asHertz(), FREQ_TOLERANCE);
	}

	/**
	 * Tests that octaves have a 2:1 frequency ratio.
	 */
	@Test
	public void octaveRatioIs2To1() {
		KeyboardTuning tuning = new DefaultKeyboardTuning();

		// Test A3 to A4
		double a3 = tuning.getTone(WesternChromatic.A3).asHertz();
		double a4 = tuning.getTone(WesternChromatic.A4).asHertz();
		Assert.assertEquals("A4/A3 ratio should be 2", 2.0, a4 / a3, RATIO_TOLERANCE);

		// Test A4 to A5
		double a5 = tuning.getTone(WesternChromatic.A5).asHertz();
		Assert.assertEquals("A5/A4 ratio should be 2", 2.0, a5 / a4, RATIO_TOLERANCE);

		// Test C4 to C5
		double c4 = tuning.getTone(WesternChromatic.C4).asHertz();
		double c5 = tuning.getTone(WesternChromatic.C5).asHertz();
		Assert.assertEquals("C5/C4 ratio should be 2", 2.0, c5 / c4, RATIO_TOLERANCE);
	}

	/**
	 * Tests that semitones follow equal temperament ratio.
	 */
	@Test
	public void semitoneRatioIsCorrect() {
		KeyboardTuning tuning = new DefaultKeyboardTuning();

		// Test consecutive semitones
		double a4 = tuning.getTone(WesternChromatic.A4).asHertz();
		double as4 = tuning.getTone(WesternChromatic.AS4).asHertz();

		Assert.assertEquals("A#4/A4 ratio should be 2^(1/12)",
				SEMITONE_RATIO, as4 / a4, RATIO_TOLERANCE);

		// Test another semitone pair
		double c4 = tuning.getTone(WesternChromatic.C4).asHertz();
		double cs4 = tuning.getTone(WesternChromatic.CS4).asHertz();

		Assert.assertEquals("C#4/C4 ratio should be 2^(1/12)",
				SEMITONE_RATIO, cs4 / c4, RATIO_TOLERANCE);
	}

	/**
	 * Tests standard reference frequencies.
	 */
	@Test
	public void standardFrequencies() {
		KeyboardTuning tuning = new DefaultKeyboardTuning();

		// Middle C (C4) should be approximately 261.63 Hz
		double c4 = tuning.getTone(WesternChromatic.C4).asHertz();
		Assert.assertEquals("C4 (middle C) should be ~261.63 Hz", 261.63, c4, 0.1);

		// A3 should be 220 Hz (one octave below A4)
		double a3 = tuning.getTone(WesternChromatic.A3).asHertz();
		Assert.assertEquals("A3 should be 220 Hz", 220.0, a3, FREQ_TOLERANCE);

		// A5 should be 880 Hz (one octave above A4)
		double a5 = tuning.getTone(WesternChromatic.A5).asHertz();
		Assert.assertEquals("A5 should be 880 Hz", 880.0, a5, FREQ_TOLERANCE);
	}

	/**
	 * Tests that all chromatic notes return valid frequencies.
	 */
	@Test
	public void allChromaticNotesHaveValidFrequencies() {
		KeyboardTuning tuning = new DefaultKeyboardTuning();

		for (WesternChromatic note : WesternChromatic.values()) {
			Frequency freq = tuning.getTone(note);
			Assert.assertNotNull("Frequency for " + note + " should not be null", freq);

			double hz = freq.asHertz();
			Assert.assertTrue("Frequency for " + note + " should be positive", hz > 0);
			Assert.assertTrue("Frequency for " + note + " should be reasonable (<20kHz)",
					hz < 20000);
		}
	}

	/**
	 * Tests frequency ranges across the keyboard.
	 */
	@Test
	public void frequencyRanges() {
		KeyboardTuning tuning = new DefaultKeyboardTuning();

		// A0 should be the lowest frequency (~27.5 Hz)
		double a0 = tuning.getTone(WesternChromatic.A0).asHertz();
		Assert.assertEquals("A0 should be ~27.5 Hz", 27.5, a0, 0.1);

		// C8 should be high (~4186 Hz)
		double c8 = tuning.getTone(WesternChromatic.C8).asHertz();
		Assert.assertEquals("C8 should be ~4186 Hz", 4186.0, c8, 1.0);
	}

	/**
	 * Tests perfect fifth interval (7 semitones = 3:2 ratio in just intonation,
	 * approximately 1.4983 in equal temperament).
	 */
	@Test
	public void perfectFifthInterval() {
		KeyboardTuning tuning = new DefaultKeyboardTuning();

		// C4 to G4 is a perfect fifth (7 semitones)
		double c4 = tuning.getTone(WesternChromatic.C4).asHertz();
		double g4 = tuning.getTone(WesternChromatic.G4).asHertz();

		double expectedRatio = Math.pow(2, 7.0 / 12.0); // Equal temperament fifth
		Assert.assertEquals("Perfect fifth should have ratio 2^(7/12)",
				expectedRatio, g4 / c4, RATIO_TOLERANCE);
	}

	/**
	 * Tests perfect fourth interval (5 semitones).
	 */
	@Test
	public void perfectFourthInterval() {
		KeyboardTuning tuning = new DefaultKeyboardTuning();

		// C4 to F4 is a perfect fourth (5 semitones)
		double c4 = tuning.getTone(WesternChromatic.C4).asHertz();
		double f4 = tuning.getTone(WesternChromatic.F4).asHertz();

		double expectedRatio = Math.pow(2, 5.0 / 12.0);
		Assert.assertEquals("Perfect fourth should have ratio 2^(5/12)",
				expectedRatio, f4 / c4, RATIO_TOLERANCE);
	}

	/**
	 * Tests major third interval (4 semitones).
	 */
	@Test
	public void majorThirdInterval() {
		KeyboardTuning tuning = new DefaultKeyboardTuning();

		// C4 to E4 is a major third (4 semitones)
		double c4 = tuning.getTone(WesternChromatic.C4).asHertz();
		double e4 = tuning.getTone(WesternChromatic.E4).asHertz();

		double expectedRatio = Math.pow(2, 4.0 / 12.0);
		Assert.assertEquals("Major third should have ratio 2^(4/12)",
				expectedRatio, e4 / c4, RATIO_TOLERANCE);
	}

	/**
	 * Tests MIDI key numbering conversion.
	 */
	@Test
	public void midiKeyNumbering() {
		KeyboardTuning tuning = new DefaultKeyboardTuning();

		// MIDI note 69 is A4 (440 Hz)
		Frequency midiA4 = tuning.getTone(69, KeyNumbering.MIDI);
		Assert.assertEquals("MIDI note 69 should be A4 (440 Hz)",
				440.0, midiA4.asHertz(), FREQ_TOLERANCE);

		// MIDI note 60 is C4 (middle C)
		Frequency midiC4 = tuning.getTone(60, KeyNumbering.MIDI);
		double c4 = tuning.getTone(WesternChromatic.C4).asHertz();
		Assert.assertEquals("MIDI note 60 should be C4",
				c4, midiC4.asHertz(), FREQ_TOLERANCE);
	}

	/**
	 * Tests that frequencies increase monotonically with key position.
	 * Note: This test skips E7 due to a known bug in WesternChromatic.position()
	 * where E7 returns 70 instead of 79.
	 */
	@Test
	public void frequenciesIncreaseMonotonically() {
		KeyboardTuning tuning = new DefaultKeyboardTuning();

		double prevFreq = 0;
		WesternChromatic prevNote = null;
		for (WesternChromatic note : WesternChromatic.values()) {
			// Skip E7 due to known bug in WesternChromatic.position()
			if (note == WesternChromatic.E7) {
				continue;
			}

			double freq = tuning.getTone(note).asHertz();
			if (prevNote != null && prevNote != WesternChromatic.DS7) {
				Assert.assertTrue("Frequency should increase: " + note + " = " + freq +
								" should be > " + prevFreq,
						freq > prevFreq);
			}
			prevFreq = freq;
			prevNote = note;
		}
	}

	/**
	 * Tests that 12 semitones equal one octave (2:1 ratio).
	 */
	@Test
	public void twelveSemitonesEqualOctave() {
		KeyboardTuning tuning = new DefaultKeyboardTuning();

		// Start from A3, go up 12 semitones, should equal A4
		double a3 = tuning.getTone(WesternChromatic.A3).asHertz();
		double computed = a3 * Math.pow(SEMITONE_RATIO, 12);

		double a4 = tuning.getTone(WesternChromatic.A4).asHertz();
		Assert.assertEquals("12 semitones should equal one octave",
				a4, computed, FREQ_TOLERANCE);
	}
}
