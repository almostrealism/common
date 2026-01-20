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

import org.almostrealism.audio.midi.MidiCCSource;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for {@link MidiCCSource}.
 */
public class MidiCCSourceTest implements TestFeatures {

	@Test
	public void testDefaultRange() {
		MidiCCSource source = new MidiCCSource(1);

		source.setValue(0);
		assertEquals(0.0, source.getValue(), 0.001);

		source.setValue(127);
		assertEquals(1.0, source.getValue(), 0.001);

		source.setValue(64);
		assertEquals(0.5, source.getValue(), 0.02);  // 64/127 ~= 0.504
	}

	@Test
	public void testCustomRange() {
		MidiCCSource source = new MidiCCSource(1);
		source.setRange(100.0, 5000.0);

		source.setValue(0);
		assertEquals(100.0, source.getValue(), 0.1);

		source.setValue(127);
		assertEquals(5000.0, source.getValue(), 0.1);

		// Midpoint should be around 2550
		source.setValue(64);
		double mid = source.getValue();
		assertTrue("Mid value should be around 2550", mid > 2400 && mid < 2700);
	}

	@Test
	public void testBipolarMode() {
		MidiCCSource source = new MidiCCSource(1);
		source.setBipolar(true);
		source.setRange(-1.0, 1.0);

		// Center (64) should be near 0
		source.setValue(64);
		assertEquals(0.0, source.getValue(), 0.02);

		// Min should be -1
		source.setValue(0);
		assertEquals(-1.0, source.getValue(), 0.02);

		// Max should be +1
		source.setValue(127);
		assertEquals(1.0, source.getValue(), 0.02);
	}

	@Test
	public void testExponentialCurve() {
		MidiCCSource source = new MidiCCSource(1);
		source.setCurve(MidiCCSource.CurveType.EXPONENTIAL);

		// Exponential should give lower values at low inputs
		source.setValue(32);
		double expValue = source.getValue();

		MidiCCSource linearSource = new MidiCCSource(1);
		linearSource.setValue(32);
		double linearValue = linearSource.getValue();

		assertTrue("Exponential should be lower at low input", expValue < linearValue);
	}

	@Test
	public void testLogarithmicCurve() {
		MidiCCSource source = new MidiCCSource(1);
		source.setCurve(MidiCCSource.CurveType.LOGARITHMIC);

		// Logarithmic should give higher values at low inputs
		source.setValue(32);
		double logValue = source.getValue();

		MidiCCSource linearSource = new MidiCCSource(1);
		linearSource.setValue(32);
		double linearValue = linearSource.getValue();

		assertTrue("Logarithmic should be higher at low input", logValue > linearValue);
	}

	@Test
	public void testSmoothing() {
		MidiCCSource source = new MidiCCSource(1);
		source.setSmoothing(0.9);  // High smoothing

		source.setValue(0);
		double initial = source.getValue();

		// Jump to max
		source.setValue(127);
		double firstRead = source.getValue();

		// With 90% smoothing, value should move slowly
		assertTrue("Smoothed value should not jump immediately",
			firstRead < 0.5);

		// Multiple reads should approach target
		for (int i = 0; i < 50; i++) {
			source.getValue();
		}
		double laterRead = source.getValue();
		assertTrue("Value should approach target after multiple reads",
			laterRead > 0.9);
	}

	@Test
	public void testCCNumber() {
		MidiCCSource source = new MidiCCSource(74);  // Cutoff
		assertEquals(74, source.getCCNumber());
	}

	@Test
	public void testRawValue() {
		MidiCCSource source = new MidiCCSource(1);

		source.setValue(100);
		assertEquals(100, source.getRawValue());

		// Test clamping
		source.setValue(-10);
		assertEquals(0, source.getRawValue());

		source.setValue(200);
		assertEquals(127, source.getRawValue());
	}

	@Test
	public void testIsBipolar() {
		MidiCCSource source = new MidiCCSource(1);
		assertFalse(source.isBipolar());

		source.setBipolar(true);
		assertTrue(source.isBipolar());
	}

	@Test
	public void testTickReturnsOperation() {
		MidiCCSource source = new MidiCCSource(1);
		assertNotNull(source.tick());
	}
}
