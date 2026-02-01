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

package org.almostrealism.audio.filter;

import io.almostrealism.lifecycle.Lifecycle;
import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.heredity.TemporalFactor;

import java.util.function.Supplier;

/**
 * ADSR (Attack-Decay-Sustain-Release) envelope generator for real-time audio synthesis.
 * <p>
 * This envelope generator implements the classic ADSR envelope pattern with
 * explicit note on/off triggers and sample-accurate state updates. It can be
 * used to modulate oscillator amplitude or filter cutoff frequency.
 * <p>
 * Envelope phases:
 * <ul>
 *   <li><b>Attack</b>: Linear ramp from 0 to 1</li>
 *   <li><b>Decay</b>: Linear ramp from 1 to sustain level</li>
 *   <li><b>Sustain</b>: Held at sustain level while gate is open</li>
 *   <li><b>Release</b>: Linear ramp from current level to 0 after gate closes</li>
 * </ul>
 * <p>
 * Usage:
 * <pre>{@code
 * ADSREnvelope env = new ADSREnvelope(0.01, 0.1, 0.7, 0.3);
 * oscillator.setEnvelope(env);
 *
 * // Trigger note
 * env.noteOn();
 * // ... play ...
 * env.noteOff();
 * }</pre>
 *
 * @see ADSREnvelopeData
 * @see TemporalFactor
 */
public class ADSREnvelope implements TemporalFactor<PackedCollection>, Lifecycle, CodeFeatures {

	private final ADSREnvelopeData data;
	private final int sampleRate;

	/**
	 * Creates an ADSREnvelope with default parameters.
	 * Default: attack=0.01s, decay=0.1s, sustain=0.7, release=0.2s
	 */
	public ADSREnvelope() {
		this(0.01, 0.1, 0.7, 0.2);
	}

	/**
	 * Creates an ADSREnvelope with specified parameters.
	 *
	 * @param attack attack time in seconds
	 * @param decay decay time in seconds
	 * @param sustain sustain level (0-1)
	 * @param release release time in seconds
	 */
	public ADSREnvelope(double attack, double decay, double sustain, double release) {
		this(new DefaultADSREnvelopeData(), OutputLine.sampleRate, attack, decay, sustain, release);
	}

	/**
	 * Creates an ADSREnvelope with specified parameters and sample rate.
	 *
	 * @param attack attack time in seconds
	 * @param decay decay time in seconds
	 * @param sustain sustain level (0-1)
	 * @param release release time in seconds
	 * @param sampleRate sample rate in Hz
	 */
	public ADSREnvelope(double attack, double decay, double sustain, double release, int sampleRate) {
		this(new DefaultADSREnvelopeData(), sampleRate, attack, decay, sustain, release);
	}

	/**
	 * Creates an ADSREnvelope with the specified data storage.
	 */
	public ADSREnvelope(ADSREnvelopeData data, int sampleRate,
						double attack, double decay, double sustain, double release) {
		this.data = data;
		this.sampleRate = sampleRate;
		data.setSampleRate(sampleRate);
		data.setParameters(attack, decay, sustain, release);
		data.reset();
	}

	/**
	 * Returns the envelope data storage.
	 */
	public ADSREnvelopeData getData() {
		return data;
	}

	/**
	 * Triggers the start of a note - begins attack phase.
	 */
	public void noteOn() {
		data.noteOn();
	}

	/**
	 * Triggers the end of a note - begins release phase.
	 */
	public void noteOff() {
		data.noteOff();
	}

	/**
	 * Returns true if the envelope is currently active.
	 */
	public boolean isActive() {
		return data.isActive();
	}

	/**
	 * Returns the current envelope level (0-1).
	 */
	public double getCurrentLevel() {
		return data.currentLevel().toDouble(0);
	}

	// Parameter setters

	public void setAttack(double seconds) { data.setAttackTime(seconds); }
	public void setDecay(double seconds) { data.setDecayTime(seconds); }
	public void setSustain(double level) { data.setSustainLevel(level); }
	public void setRelease(double seconds) { data.setReleaseTime(seconds); }

	public double getAttack() { return data.attackTime().toDouble(0); }
	public double getDecay() { return data.decayTime().toDouble(0); }
	public double getSustain() { return data.sustainLevel().toDouble(0); }
	public double getRelease() { return data.releaseTime().toDouble(0); }

	/**
	 * Returns the current envelope level as an amplitude multiplier.
	 * <p>
	 * The input parameter is ignored because ADSREnvelope manages its own timing
	 * via {@link #noteOn()}, {@link #noteOff()}, and {@link #tick()}. This is
	 * different from position-based envelopes that derive amplitude from note position.
	 * </p>
	 *
	 * @param input ignored - ADSREnvelope uses internal timing, not input position
	 * @return a Producer that reads the current envelope level (0-1)
	 */
	@Override
	public Producer<PackedCollection> getResultant(Producer<PackedCollection> input) {
		return data.getCurrentLevel();
	}

	/**
	 * Advances the envelope state by one sample.
	 * Updates the phase, position, and current level based on ADSR parameters.
	 */
	@Override
	public Supplier<Runnable> tick() {
		OperationList tick = new OperationList("ADSREnvelope Tick");

		// Compute phase increment per sample for each phase
		// The envelope computation is done in Java here rather than GPU
		// because the branching logic is complex. For a hardware-optimized
		// version, see ADSREnvelopeComputation.
		tick.add(() -> () -> {
			double phase = data.phase().toDouble(0);
			double position = data.position().toDouble(0);
			double currentLevel = data.currentLevel().toDouble(0);

			if (phase == ADSREnvelopeData.PHASE_IDLE) {
				// No processing in idle state
				return;
			}

			double attackTime = data.attackTime().toDouble(0);
			double decayTime = data.decayTime().toDouble(0);
			double sustainLevel = data.sustainLevel().toDouble(0);
			double releaseTime = data.releaseTime().toDouble(0);
			double releaseLevel = data.releaseLevel().toDouble(0);

			// Calculate position increment (1 / (time * sampleRate))
			double dt = 1.0 / sampleRate;

			if (phase == ADSREnvelopeData.PHASE_ATTACK) {
				if (attackTime > 0) {
					position += dt / attackTime;
					currentLevel = Math.min(1.0, position);
				} else {
					position = 1.0;
					currentLevel = 1.0;
				}

				if (position >= 1.0) {
					// Transition to decay
					data.setPhase(ADSREnvelopeData.PHASE_DECAY);
					data.setPosition(0);
				} else {
					data.setPosition(position);
				}
				data.setCurrentLevel(currentLevel);

			} else if (phase == ADSREnvelopeData.PHASE_DECAY) {
				if (decayTime > 0) {
					position += dt / decayTime;
					currentLevel = 1.0 - (1.0 - sustainLevel) * Math.min(1.0, position);
				} else {
					position = 1.0;
					currentLevel = sustainLevel;
				}

				if (position >= 1.0) {
					// Transition to sustain
					data.setPhase(ADSREnvelopeData.PHASE_SUSTAIN);
					data.setPosition(0);
					data.setCurrentLevel(sustainLevel);
				} else {
					data.setPosition(position);
					data.setCurrentLevel(currentLevel);
				}

			} else if (phase == ADSREnvelopeData.PHASE_SUSTAIN) {
				// Stay at sustain level while gate is open
				// noteOff() will transition to release phase
				data.setCurrentLevel(sustainLevel);

			} else if (phase == ADSREnvelopeData.PHASE_RELEASE) {
				if (releaseTime > 0) {
					position += dt / releaseTime;
					currentLevel = releaseLevel * (1.0 - Math.min(1.0, position));
				} else {
					position = 1.0;
					currentLevel = 0;
				}

				if (position >= 1.0) {
					// Transition to idle
					data.setPhase(ADSREnvelopeData.PHASE_IDLE);
					data.setPosition(0);
					data.setCurrentLevel(0);
				} else {
					data.setPosition(position);
					data.setCurrentLevel(currentLevel);
				}
			}
		});

		return tick;
	}

	@Override
	public void reset() {
		data.reset();
	}

	// Static factory methods

	/**
	 * Creates a fast attack envelope suitable for percussive sounds.
	 */
	public static ADSREnvelope percussive(double attack, double decay) {
		return new ADSREnvelope(attack, decay, 0.0, 0.01);
	}

	/**
	 * Creates a slow attack envelope suitable for pads.
	 */
	public static ADSREnvelope pad(double attack, double sustain, double release) {
		return new ADSREnvelope(attack, 0.1, sustain, release);
	}

	/**
	 * Creates an organ-style envelope with instant attack and release.
	 */
	public static ADSREnvelope organ() {
		return new ADSREnvelope(0.001, 0.0, 1.0, 0.001);
	}

	/**
	 * Creates a plucked string envelope.
	 */
	public static ADSREnvelope pluck(double decay) {
		return new ADSREnvelope(0.001, decay, 0.0, 0.05);
	}
}
