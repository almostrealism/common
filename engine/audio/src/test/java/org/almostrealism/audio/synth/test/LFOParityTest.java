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

package org.almostrealism.audio.synth.test;

import io.almostrealism.lifecycle.Destroyable;
import org.almostrealism.audio.synth.LFO;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Verifies that the device-side {@link LFO} reproduces the per-sample recurrence the LFO
 * computed on the host: the value of each tick is the waveform at the current phase, and
 * the phase then advances by {@code frequency / sampleRate}, wrapping at one.
 *
 * <p>One compiled tick drives every sample, and the frequency and shape are changed
 * between ticks without recompiling, so the test also shows that the parameters are read
 * from the device each tick. The sample rate and frequencies give increments that are
 * exact binary fractions, so the device phase and the reference phase agree exactly and
 * the comparison is not disturbed at a waveform's discontinuities; the sine is compared
 * within single-precision tolerance. Sample-and-hold draws from a random source, so it is
 * checked by its properties instead: the value is held for exactly one cycle and moves to
 * a new value in {@code [-1, 1]} at each wrap.</p>
 */
public class LFOParityTest extends TestSuiteBase {

	/** Sample rate, chosen so the frequencies below give exact binary increments. */
	private static final int SR = 64;

	/** Tolerance for single-precision device arithmetic. */
	private static final double TOLERANCE = 1e-5;

	/** A host reference of the LFO recurrence for the periodic shapes. */
	private static final class Reference {
		/** The shape. */
		private LFO.Shape shape;
		/** The frequency in Hz. */
		private double frequency;
		/** The phase, in [0, 1). */
		private double phase;
		/** The value of the last tick. */
		private double value;

		/**
		 * Creates a reference at phase zero.
		 *
		 * @param shape     the shape
		 * @param frequency the frequency in Hz
		 */
		private Reference(LFO.Shape shape, double frequency) {
			this.shape = shape;
			this.frequency = frequency;
		}

		/** Computes this tick's value and advances the phase. */
		private void tick() {
			value = valueAt(phase);
			phase = (phase + frequency / SR) % 1.0;
		}

		/**
		 * Returns the waveform at a phase.
		 *
		 * @param p the phase
		 * @return the value
		 */
		private double valueAt(double p) {
			switch (shape) {
				case SINE: return Math.sin(2.0 * Math.PI * p);
				case TRIANGLE: return p < 0.25 ? 4.0 * p : p < 0.75 ? 2.0 - 4.0 * p : -4.0 + 4.0 * p;
				case SQUARE: return p < 0.5 ? 1.0 : -1.0;
				case SAW_UP: return 2.0 * p - 1.0;
				case SAW_DOWN: return 1.0 - 2.0 * p;
				default: throw new IllegalArgumentException(String.valueOf(shape));
			}
		}
	}

	/** Every periodic shape matches the reference, through a frequency change and a shape change. */
	@Test(timeout = 120000)
	public void periodicShapesMatchTheHostRecurrence() {
		LFO.Shape[] shapes = { LFO.Shape.SINE, LFO.Shape.TRIANGLE, LFO.Shape.SQUARE,
				LFO.Shape.SAW_UP, LFO.Shape.SAW_DOWN };

		for (LFO.Shape shape : shapes) {
			LFO lfo = new LFO(shape, 4.0, SR);
			Runnable tick = lfo.tick().get();

			try {
				Reference reference = new Reference(shape, 4.0);

				assertTracksReference(tick, lfo, reference, 40, shape + " at 4 Hz");

				lfo.setFrequency(3.0);
				reference.frequency = 3.0;
				assertTracksReference(tick, lfo, reference, 40, shape + " at 3 Hz");

				LFO.Shape next = shapes[(shape.ordinal() + 1) % shapes.length];
				lfo.setShape(next);
				reference.shape = next;
				assertTracksReference(tick, lfo, reference, 40, shape + " then " + next);
			} finally {
				Destroyable.destroy(tick);
				lfo.destroy();
			}
		}
	}

	/** Setting the phase is seen by the next tick. */
	@Test(timeout = 60000)
	public void syncMovesThePhase() {
		LFO lfo = new LFO(LFO.Shape.SAW_UP, 4.0, SR);
		Runnable tick = lfo.tick().get();

		try {
			tick.run();

			lfo.sync(0.5);
			assertEquals(0.5, lfo.getPhase());
			tick.run();
			assertEquals(0.0, lfo.getValue());
			assertEquals(0.5625, lfo.getPhase());
		} finally {
			Destroyable.destroy(tick);
			lfo.destroy();
		}
	}

	/** Sample-and-hold holds one value per cycle and draws a new one in [-1, 1] at each wrap. */
	@Test(timeout = 60000)
	public void sampleAndHoldChangesOnlyAtTheWrap() {
		LFO lfo = new LFO(LFO.Shape.SAMPLE_HOLD, 4.0, SR);
		Runnable tick = lfo.tick().get();

		try {
			int cycle = SR / 4;

			List<Double> held = new ArrayList<>();
			for (int c = 0; c < 6; c++) {
				double first = Double.NaN;
				for (int i = 0; i < cycle; i++) {
					tick.run();
					double value = lfo.getValue();
					assertTrue("value " + value + " in [-1, 1]", value >= -1.0 && value <= 1.0);
					if (i == 0) {
						first = value;
					} else {
						assertEquals("cycle " + c + " sample " + i + " holds its value", first, value);
					}
				}

				held.add(first);
			}

			long distinct = held.stream().distinct().count();
			assertTrue("a new value is drawn at each wrap: " + held, distinct == held.size());
		} finally {
			Destroyable.destroy(tick);
			lfo.destroy();
		}
	}

	/**
	 * Destroying an LFO releases its device state and is idempotent: a second destroy, as can
	 * happen when an owner clears the same modulation source more than once, does nothing and
	 * does not throw. The LFO produces a valid value up to the point it is destroyed, including
	 * after sample-and-hold has compiled and run its redraw operation.
	 */
	@Test(timeout = 60000)
	public void destroyIsSafeAndIdempotent() {
		LFO lfo = new LFO(LFO.Shape.SAMPLE_HOLD, 4.0, SR);
		Runnable tick = lfo.tick().get();
		tick.run();

		double value = lfo.getValue();
		assertTrue("value " + value + " in [-1, 1]", value >= -1.0 && value <= 1.0);

		lfo.destroy();
		lfo.destroy();
	}

	/**
	 * Runs ticks and compares the device value, phase and output to the reference after each.
	 *
	 * @param tick      the compiled tick
	 * @param lfo       the device LFO
	 * @param reference the host reference
	 * @param count     the number of ticks
	 * @param label     a label for failures
	 */
	private void assertTracksReference(Runnable tick, LFO lfo, Reference reference, int count, String label) {
		for (int i = 0; i < count; i++) {
			tick.run();
			reference.tick();

			String at = label + ", tick " + i;
			assertClose(at + " value", reference.value, lfo.getValue());
			assertClose(at + " phase", reference.phase, lfo.getPhase());

			PackedCollection output = lfo.getOutput().get().evaluate();
			assertClose(at + " output", reference.value, output.toDouble(0));
		}
	}

	/**
	 * Asserts that two values agree within single-precision tolerance.
	 *
	 * @param label    what is compared
	 * @param expected the reference value
	 * @param actual   the device value
	 */
	private void assertClose(String label, double expected, double actual) {
		assertTrue(label + ": expected " + expected + " but was " + actual,
				Math.abs(expected - actual) <= TOLERANCE);
	}
}
