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

package org.almostrealism.audio.midi.test;

import org.almostrealism.audio.midi.MidiInputListener;
import org.almostrealism.audio.midi.VelocityCurve;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests for {@link MidiInputListener} and related classes.
 */
public class MidiInputListenerTest implements TestFeatures {

	@Test
	public void testDefaultMethodsDoNotThrow() {
		MidiInputListener listener = new MidiInputListener() {};

		// All default methods should be callable without exception
		listener.noteOn(0, 60, 100);
		listener.noteOff(0, 60, 0);
		listener.controlChange(0, 1, 64);
		listener.pitchBend(0, 8192);
		listener.programChange(0, 0);
		listener.aftertouch(0, 64);
		listener.polyAftertouch(0, 60, 64);
		listener.clock();
		listener.start();
		listener.stop();
		listener.midiContinue();
	}

	@Test
	public void testCCConstants() {
		assertEquals(1, MidiInputListener.CC.MODULATION);
		assertEquals(7, MidiInputListener.CC.VOLUME);
		assertEquals(64, MidiInputListener.CC.SUSTAIN);
		assertEquals(74, MidiInputListener.CC.CUTOFF);
		assertEquals(123, MidiInputListener.CC.ALL_NOTES_OFF);
	}

	@Test
	public void testCustomListenerReceivesMessages() {
		List<String> received = new ArrayList<>();

		MidiInputListener listener = new MidiInputListener() {
			@Override
			public void noteOn(int channel, int note, int velocity) {
				received.add("noteOn:" + channel + ":" + note + ":" + velocity);
			}

			@Override
			public void noteOff(int channel, int note, int velocity) {
				received.add("noteOff:" + channel + ":" + note + ":" + velocity);
			}

			@Override
			public void controlChange(int channel, int controller, int value) {
				received.add("cc:" + channel + ":" + controller + ":" + value);
			}
		};

		listener.noteOn(0, 60, 100);
		listener.noteOff(0, 60, 0);
		listener.controlChange(0, 1, 64);

		assertEquals(3, received.size());
		assertEquals("noteOn:0:60:100", received.get(0));
		assertEquals("noteOff:0:60:0", received.get(1));
		assertEquals("cc:0:1:64", received.get(2));
	}

	@Test
	public void testVelocityCurveLinear() {
		assertEquals(0.0, VelocityCurve.LINEAR.apply(0), 0.001);
		assertEquals(0.5, VelocityCurve.LINEAR.apply(64), 0.02);  // 64/127 ~= 0.504
		assertEquals(1.0, VelocityCurve.LINEAR.apply(127), 0.001);
	}

	@Test
	public void testVelocityCurveSoft() {
		// Soft curve should give higher values at low velocities
		double soft32 = VelocityCurve.SOFT.apply(32);
		double linear32 = VelocityCurve.LINEAR.apply(32);
		assertTrue("Soft curve should be higher at low velocity", soft32 > linear32);

		assertEquals(0.0, VelocityCurve.SOFT.apply(0), 0.001);
		assertEquals(1.0, VelocityCurve.SOFT.apply(127), 0.001);
	}

	@Test
	public void testVelocityCurveHard() {
		// Hard curve should give lower values at low velocities
		double hard32 = VelocityCurve.HARD.apply(32);
		double linear32 = VelocityCurve.LINEAR.apply(32);
		assertTrue("Hard curve should be lower at low velocity", hard32 < linear32);

		assertEquals(0.0, VelocityCurve.HARD.apply(0), 0.001);
		assertEquals(1.0, VelocityCurve.HARD.apply(127), 0.001);
	}

	@Test
	public void testVelocityCurveFixed() {
		assertEquals(0.0, VelocityCurve.FIXED.apply(0), 0.001);
		assertEquals(1.0, VelocityCurve.FIXED.apply(1), 0.001);
		assertEquals(1.0, VelocityCurve.FIXED.apply(64), 0.001);
		assertEquals(1.0, VelocityCurve.FIXED.apply(127), 0.001);
	}

	@Test
	public void testVelocityCurveWithFloor() {
		double floor = 0.2;

		// At velocity 0, should be 0 (not floor)
		assertEquals(0.0, VelocityCurve.LINEAR.apply(0, floor), 0.001);

		// At velocity 127, should be 1.0
		assertEquals(1.0, VelocityCurve.LINEAR.apply(127, floor), 0.001);

		// At low velocity, should be above floor
		double result = VelocityCurve.LINEAR.apply(1, floor);
		assertTrue("Result should be above floor", result >= floor);
	}
}
