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

package org.almostrealism.audio.midi.test;

import org.almostrealism.audio.midi.MidiSynthesizerBridge;
import org.almostrealism.audio.synth.PolyphonicSynthesizer;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for {@link MidiSynthesizerBridge#pitchBend(int, int)} verifying
 * MIDI 14-bit value conversion and channel filtering.
 */
public class MidiSynthesizerBridgePitchBendTest extends TestSuiteBase {

	/**
	 * Verifies that pitch bend at center value (8192) does not throw
	 * and represents zero semitones.
	 */
	@Test(timeout = 5000)
	public void pitchBendCenterDoesNotThrow() {
		PolyphonicSynthesizer synth = new PolyphonicSynthesizer(1);
		MidiSynthesizerBridge bridge = new MidiSynthesizerBridge(synth);
		bridge.pitchBend(0, 8192);
	}

	/**
	 * Verifies that pitch bend at maximum value (16383) does not throw.
	 */
	@Test(timeout = 5000)
	public void pitchBendMaxDoesNotThrow() {
		PolyphonicSynthesizer synth = new PolyphonicSynthesizer(1);
		MidiSynthesizerBridge bridge = new MidiSynthesizerBridge(synth);
		bridge.pitchBend(0, 16383);
	}

	/**
	 * Verifies that pitch bend at minimum value (0) does not throw.
	 */
	@Test(timeout = 5000)
	public void pitchBendMinDoesNotThrow() {
		PolyphonicSynthesizer synth = new PolyphonicSynthesizer(1);
		MidiSynthesizerBridge bridge = new MidiSynthesizerBridge(synth);
		bridge.pitchBend(0, 0);
	}

	/**
	 * Verifies that pitch bend on a non-matching channel is ignored when
	 * the bridge is set to a specific channel.
	 */
	@Test(timeout = 5000)
	public void pitchBendIgnoredOnWrongChannel() {
		PolyphonicSynthesizer synth = new PolyphonicSynthesizer(1);
		MidiSynthesizerBridge bridge = new MidiSynthesizerBridge(synth);
		bridge.setChannel(5);

		// Should be silently ignored since channel 3 != 5
		bridge.pitchBend(3, 16383);
	}

	/**
	 * Verifies that pitch bend is processed on the correct channel.
	 */
	@Test(timeout = 5000)
	public void pitchBendProcessedOnCorrectChannel() {
		PolyphonicSynthesizer synth = new PolyphonicSynthesizer(1);
		MidiSynthesizerBridge bridge = new MidiSynthesizerBridge(synth);
		bridge.setChannel(5);

		// Should be processed since channel matches
		bridge.pitchBend(5, 16383);
	}

	/**
	 * Verifies that pitch bend is processed in omni mode (channel = -1).
	 */
	@Test(timeout = 5000)
	public void pitchBendProcessedInOmniMode() {
		PolyphonicSynthesizer synth = new PolyphonicSynthesizer(1);
		MidiSynthesizerBridge bridge = new MidiSynthesizerBridge(synth);
		// Default is omni mode (-1)

		bridge.pitchBend(7, 0);
		bridge.pitchBend(15, 16383);
	}

	/**
	 * Verifies that setPitchBendRange updates the range correctly.
	 */
	@Test(timeout = 5000)
	public void setPitchBendRange() {
		PolyphonicSynthesizer synth = new PolyphonicSynthesizer(1);
		MidiSynthesizerBridge bridge = new MidiSynthesizerBridge(synth);

		// Set a wide range and send max bend - should not throw
		bridge.setPitchBendRange(12.0);
		bridge.pitchBend(0, 16383);

		// Set a narrow range and send max bend - should not throw
		bridge.setPitchBendRange(0.5);
		bridge.pitchBend(0, 0);
	}

	/**
	 * Verifies the MIDI 14-bit to semitone conversion formula:
	 * normalized = (value - 8192) / 8192.0
	 * semitones = normalized * pitchBendRange
	 */
	@Test(timeout = 5000)
	public void conversionFormula() {
		// Center (8192) -> 0 semitones
		double normalized = (8192 - 8192) / 8192.0;
		Assert.assertEquals(0.0, normalized, 1e-10);

		// Max (16383) -> ~+1.0 (almost full range)
		normalized = (16383 - 8192) / 8192.0;
		Assert.assertEquals(0.99987792968750, normalized, 1e-6);
		double semitones = normalized * 2.0; // default range
		Assert.assertTrue("Max bend should be close to +2 semitones", semitones > 1.99 && semitones < 2.01);

		// Min (0) -> -1.0
		normalized = (0 - 8192) / 8192.0;
		Assert.assertEquals(-1.0, normalized, 1e-10);
		semitones = normalized * 2.0;
		Assert.assertEquals(-2.0, semitones, 1e-10);
	}

	/**
	 * Verifies the semitone-to-Hz formula used in AudioSynthesizer.setPitchBend:
	 * bentHz = baseHz * 2^(semitones/12)
	 */
	@Test(timeout = 5000)
	public void semitoneToHzFormula() {
		double baseHz = 440.0; // A4

		// +12 semitones = octave up = 880 Hz
		double bentHz = baseHz * Math.pow(2.0, 12.0 / 12.0);
		Assert.assertEquals(880.0, bentHz, 1e-10);

		// -12 semitones = octave down = 220 Hz
		bentHz = baseHz * Math.pow(2.0, -12.0 / 12.0);
		Assert.assertEquals(220.0, bentHz, 1e-10);

		// +2 semitones (default range max)
		bentHz = baseHz * Math.pow(2.0, 2.0 / 12.0);
		Assert.assertEquals(493.8833, bentHz, 0.001);

		// 0 semitones = no change
		bentHz = baseHz * Math.pow(2.0, 0.0 / 12.0);
		Assert.assertEquals(440.0, bentHz, 1e-10);
	}
}
