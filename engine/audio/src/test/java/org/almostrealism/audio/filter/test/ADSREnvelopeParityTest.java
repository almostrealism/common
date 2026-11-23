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

package org.almostrealism.audio.filter.test;

import org.almostrealism.audio.computations.ADSREnvelopeComputation;
import org.almostrealism.audio.filter.ADSREnvelope;
import org.almostrealism.audio.filter.ADSREnvelopeData;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Verifies that the device-side ADSR state machine advanced by {@link ADSREnvelopeData#tickUpdate(int)}
 * agrees, sample for sample, with an independent host reference of the ADSR recurrence, and that a
 * single compiled tick operation drives the whole envelope rather than compiling a kernel per sample.
 *
 * <p>The reference {@link Reference} reproduces the piecewise-linear ADSR specification the migrated
 * device operation implements; the device envelope is driven by the same note events and its
 * {@code phase}, {@code position} and {@code currentLevel} are compared to the reference after every
 * tick. The tolerance is a combined absolute and relative band wide enough for single-precision device
 * arithmetic yet far tighter than any wiring or formula error.</p>
 */
public class ADSREnvelopeParityTest extends TestSuiteBase {

	/** Low sample rate so each phase spans a handful of samples. */
	private static final int SR = 100;

	/** A host reference of the ADSR recurrence the device operation must match. */
	private static final class Reference {
		/** Attack time in seconds. */
		private final double attack;
		/** Decay time in seconds. */
		private final double decay;
		/** Sustain level. */
		private final double sustain;
		/** Release time in seconds. */
		private final double release;
		/** Sample rate in Hz. */
		private final int sampleRate;

		/** Current phase constant. */
		private double phase;
		/** Position within the current phase (0–1). */
		private double position;
		/** Current output level. */
		private double level;
		/** Level captured at note off, the release ramp's starting point. */
		private double releaseLevel;

		/**
		 * Creates a reference envelope with the given parameters, starting idle.
		 *
		 * @param attack     attack time in seconds
		 * @param decay      decay time in seconds
		 * @param sustain    sustain level
		 * @param release    release time in seconds
		 * @param sampleRate sample rate in Hz
		 */
		private Reference(double attack, double decay, double sustain, double release, int sampleRate) {
			this.attack = attack;
			this.decay = decay;
			this.sustain = sustain;
			this.release = release;
			this.sampleRate = sampleRate;
			this.phase = ADSREnvelopeData.PHASE_IDLE;
		}

		/** Begins the attack phase. */
		private void noteOn() {
			phase = ADSREnvelopeData.PHASE_ATTACK;
			position = 0;
		}

		/** Begins the release phase from the current level. */
		private void noteOff() {
			releaseLevel = level;
			phase = ADSREnvelopeData.PHASE_RELEASE;
			position = 0;
		}

		/** Advances the reference by one sample. */
		private void tick() {
			double dt = 1.0 / sampleRate;

			if (phase == ADSREnvelopeData.PHASE_IDLE) {
				return;
			}

			if (phase == ADSREnvelopeData.PHASE_ATTACK) {
				if (attack > 0) {
					position += dt / attack;
					level = Math.min(1.0, position);
				} else {
					position = 1.0;
					level = 1.0;
				}

				if (position >= 1.0) {
					phase = ADSREnvelopeData.PHASE_DECAY;
					position = 0;
				}
			} else if (phase == ADSREnvelopeData.PHASE_DECAY) {
				if (decay > 0) {
					position += dt / decay;
					level = 1.0 - (1.0 - sustain) * Math.min(1.0, position);
				} else {
					position = 1.0;
					level = sustain;
				}

				if (position >= 1.0) {
					phase = ADSREnvelopeData.PHASE_SUSTAIN;
					position = 0;
					level = sustain;
				}
			} else if (phase == ADSREnvelopeData.PHASE_SUSTAIN) {
				level = sustain;
			} else if (phase == ADSREnvelopeData.PHASE_RELEASE) {
				if (release > 0) {
					position += dt / release;
					level = releaseLevel * (1.0 - Math.min(1.0, position));
				} else {
					position = 1.0;
					level = 0;
				}

				if (position >= 1.0) {
					phase = ADSREnvelopeData.PHASE_IDLE;
					position = 0;
					level = 0;
				}
			}
		}
	}

	/**
	 * A moderate envelope, driven through attack, decay, sustain and release.
	 *
	 * <p>The phase durations are deliberately not integer multiples of the sample period, so each
	 * position crosses the transition threshold strictly between two samples. A duration that is an
	 * exact multiple lands the position on {@code 1.0}, where the host's double accumulation and the
	 * device's single-precision accumulation can disagree by one sample on a whole-unit phase value —
	 * a degenerate boundary, not a behavior under test.</p>
	 */
	@Test(timeout = 120000)
	public void fullEnvelope() {
		assertParity(0.052, 0.034, 0.6, 0.041, 11, 2, 6);
	}

	/** A zero-attack, zero-decay, zero-release envelope exercising the immediate-transition edges. */
	@Test(timeout = 120000)
	public void instantTransitions() {
		assertParity(0.0, 0.0, 0.5, 0.0, 4, 2, 4);
	}

	/** A percussive envelope with zero sustain that decays straight to the sustain floor. */
	@Test(timeout = 120000)
	public void percussive() {
		assertParity(0.021, 0.063, 0.0, 0.031, 11, 2, 5);
	}

	/**
	 * Advances a single attack-phase ADSR step through the optimizing
	 * {@link io.almostrealism.relation.Producer#evaluate(Object...)} entry point. Because
	 * {@code evaluate()} routes through {@code Process.optimized(this).get()}, this exercises the
	 * regeneration path of the eight-input {@link ADSREnvelopeComputation}: without a
	 * {@code generate(List)} override it would fail during optimization with
	 * {@code UnsupportedOperationException} before any kernel runs.
	 */
	@Test(timeout = 120000)
	public void singleStepOptimizedEvaluate() {
		ADSREnvelopeComputation update = new ADSREnvelopeComputation(SR,
				cp(pack((double) ADSREnvelopeData.PHASE_ATTACK)),
				cp(pack(0.0)),
				cp(pack(0.0)),
				cp(pack(0.05)),
				cp(pack(0.03)),
				cp(pack(0.6)),
				cp(pack(0.04)),
				cp(pack(0.0)));

		PackedCollection result = update.evaluate();

		// One attack sample advances position by dt/attack = (1/100)/0.05 = 0.2, the level tracks the
		// position, and the phase stays in attack until the position reaches 1.
		Assert.assertEquals(ADSREnvelopeData.PHASE_ATTACK, result.toDouble(0), 1e-4);
		Assert.assertEquals(0.2, result.toDouble(1), 1e-4);
		Assert.assertEquals(0.2, result.toDouble(2), 1e-4);
	}

	/**
	 * Drives a device envelope and the host {@link Reference} through the same note events, asserting
	 * per-tick agreement of phase, position and level. The device tick operation is built once and run
	 * repeatedly, confirming a runtime-varying state does not compile a kernel per sample.
	 *
	 * @param attack       attack time in seconds
	 * @param decay        decay time in seconds
	 * @param sustain      sustain level
	 * @param release      release time in seconds
	 * @param gateSamples  ticks to run while the gate is open (attack through sustain)
	 * @param holdSamples  additional sustain ticks before note off
	 * @param releaseSamples ticks to run after note off (release into idle)
	 */
	private void assertParity(double attack, double decay, double sustain, double release,
							  int gateSamples, int holdSamples, int releaseSamples) {
		ADSREnvelope env = new ADSREnvelope(attack, decay, sustain, release, SR);
		Reference ref = new Reference(attack, decay, sustain, release, SR);
		Runnable tick = env.tick().get();

		env.noteOn();
		ref.noteOn();
		for (int i = 0; i < gateSamples + holdSamples; i++) {
			tick.run();
			ref.tick();
			assertState("gate tick " + i, ref, env);
		}

		env.noteOff();
		ref.noteOff();
		for (int i = 0; i < releaseSamples; i++) {
			tick.run();
			ref.tick();
			assertState("release tick " + i, ref, env);
		}
	}

	/**
	 * Asserts that the device envelope's phase, position and level match the reference.
	 *
	 * @param label the assertion label
	 * @param ref   the host reference
	 * @param env   the device envelope
	 */
	private void assertState(String label, Reference ref, ADSREnvelope env) {
		ADSREnvelopeData data = env.getData();
		assertClose(label + " phase", ref.phase, data.phase().toDouble(0));
		assertClose(label + " position", ref.position, data.position().toDouble(0));
		assertClose(label + " level", ref.level, data.currentLevel().toDouble(0));
	}

	/**
	 * Asserts agreement with a combined absolute and relative tolerance.
	 *
	 * @param label    the assertion label
	 * @param expected the reference value
	 * @param actual   the device value
	 */
	private void assertClose(String label, double expected, double actual) {
		Assert.assertEquals(label, expected, actual, 1e-4 + 1e-4 * Math.abs(expected));
	}
}
