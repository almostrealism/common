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

package org.almostrealism.audio.synth;

import io.almostrealism.relation.Producer;
import org.almostrealism.audio.SamplingFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

/**
 * An {@link AudioSynthesisModel} that generates ADSR envelope levels.
 * <p>
 * This model produces amplitude levels following the standard ADSR
 * (Attack, Decay, Sustain, Release) envelope shape. The envelope
 * is applied uniformly to all frequency components.
 * <p>
 * The envelope timing is expressed in seconds:
 * <ul>
 *   <li><b>Attack</b>: Time to rise from 0 to peak (1.0)</li>
 *   <li><b>Decay</b>: Time to fall from peak to sustain level</li>
 *   <li><b>Sustain</b>: Level maintained while note is held (0.0-1.0)</li>
 *   <li><b>Release</b>: Time to fall from sustain to 0 after note off</li>
 * </ul>
 * <p>
 * For the buffer-based synthesis approach, this model assumes notes
 * are triggered at time 0 and sustain for the duration of the buffer
 * (no noteOff during buffer generation).
 *
 * @see AudioSynthesisModel
 * @see AudioSynthesizer
 */
public class ADSRSynthesisModel implements AudioSynthesisModel, SamplingFeatures {

	private double attack;
	private double decay;
	private double sustain;
	private double release;

	/**
	 * Creates an ADSR synthesis model with default parameters.
	 * Default: quick attack (10ms), moderate decay (100ms),
	 * sustain at 70%, moderate release (300ms).
	 */
	public ADSRSynthesisModel() {
		this(0.01, 0.1, 0.7, 0.3);
	}

	/**
	 * Creates an ADSR synthesis model with the specified parameters.
	 *
	 * @param attack attack time in seconds
	 * @param decay decay time in seconds
	 * @param sustain sustain level (0.0-1.0)
	 * @param release release time in seconds
	 */
	public ADSRSynthesisModel(double attack, double decay, double sustain, double release) {
		this.attack = attack;
		this.decay = decay;
		this.sustain = sustain;
		this.release = release;
	}

	public double getAttack() { return attack; }
	public void setAttack(double attack) { this.attack = attack; }

	public double getDecay() { return decay; }
	public void setDecay(double decay) { this.decay = decay; }

	public double getSustain() { return sustain; }
	public void setSustain(double sustain) { this.sustain = sustain; }

	public double getRelease() { return release; }
	public void setRelease(double release) { this.release = release; }

	@Override
	public Producer<PackedCollection> getLevels(double frequencyRatio, Producer<PackedCollection> time) {
		// The envelope shape is independent of frequency ratio for basic ADSR
		// (though subclasses could implement frequency-dependent envelopes)
		return computeEnvelope((CollectionProducer) time);
	}

	/**
	 * Computes the ADSR envelope values for the given time series.
	 * <p>
	 * For buffer-based synthesis, assumes note is on (no release phase).
	 */
	private CollectionProducer computeEnvelope(CollectionProducer time) {
		// Attack phase: linear ramp from 0 to 1 over attack time
		// Decay phase: linear ramp from 1 to sustain over decay time
		// Sustain phase: constant at sustain level

		double attackEnd = attack;
		double decayEnd = attack + decay;

		// For each time value t:
		// if t < attack: level = t / attack
		// if t < attack + decay: level = 1 - (1 - sustain) * (t - attack) / decay
		// else: level = sustain

		// Attack slope
		CollectionProducer attackLevel;
		if (attack > 0.0001) {
			// t/attack, clamped to 1.0 via lessThan
			CollectionProducer rawAttack = time.divide(attack);
			attackLevel = rawAttack.lessThan(c(1.0), rawAttack, c(1.0));
		} else {
			attackLevel = c(1.0);
		}

		// Decay calculation: starts at 1.0, drops to sustain
		CollectionProducer decayLevel;
		if (decay > 0.0001) {
			CollectionProducer decayProgress = time.subtract(attack).divide(decay);
			// Clamp progress to [0, 1] range
			CollectionProducer clampedProgress = decayProgress.lessThan(c(1.0), decayProgress, c(1.0));
			decayLevel = c(1.0).subtract(c(1.0 - sustain).multiply(clampedProgress));
		} else {
			decayLevel = c(sustain);
		}

		// Use lessThan with true/false value selection
		// t < attack ? attackLevel : (t < decayEnd ? decayLevel : sustain)
		CollectionProducer sustainLevel = c(sustain);

		// Inner conditional: decay or sustain
		CollectionProducer decayOrSustain = time.lessThan(c(decayEnd), decayLevel, sustainLevel);

		// Outer conditional: attack or (decay/sustain)
		return time.lessThan(c(attackEnd), attackLevel, decayOrSustain);
	}

	/**
	 * Creates a percussive envelope (fast attack, no sustain).
	 */
	public static ADSRSynthesisModel percussive(double attack, double decay) {
		return new ADSRSynthesisModel(attack, decay, 0.0, decay);
	}

	/**
	 * Creates a pad envelope (slow attack, high sustain).
	 */
	public static ADSRSynthesisModel pad(double attack, double release) {
		return new ADSRSynthesisModel(attack, 0.1, 0.8, release);
	}

	/**
	 * Creates an organ-like envelope (instant attack, full sustain).
	 */
	public static ADSRSynthesisModel organ() {
		return new ADSRSynthesisModel(0.001, 0.0, 1.0, 0.01);
	}

	/**
	 * Creates a pluck envelope (fast attack, medium decay, low sustain).
	 */
	public static ADSRSynthesisModel pluck() {
		return new ADSRSynthesisModel(0.001, 0.3, 0.2, 0.2);
	}
}
