/*
 * Copyright 2023 Michael Murray
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

import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import org.almostrealism.audio.SamplingFeatures;
import org.almostrealism.collect.PackedCollection;

/**
 * Factory methods for creating amplitude envelope generators.
 *
 * <p>EnvelopeFeatures provides methods for creating common envelope shapes
 * including ADSR (Attack-Decay-Sustain-Release), linear ramps, and custom
 * multi-segment envelopes. Envelopes are created as {@link Factor} instances
 * that can be applied to audio signals.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * EnvelopeFeatures e = EnvelopeFeatures.getInstance();
 *
 * // Create an ADSR envelope
 * EnvelopeSection env = e.envelope(duration, attack, decay, sustain, release);
 * Factor<PackedCollection> factor = env.get();
 *
 * // Apply to audio
 * Producer<PackedCollection> output = factor.getResultant(audioInput);
 * }</pre>
 *
 * @see EnvelopeSection
 * @see SamplingFeatures
 */
public interface EnvelopeFeatures extends SamplingFeatures {
	/**
	 * Creates a volume-scaling envelope factor that multiplies an input signal by the given volume producer.
	 *
	 * @param volume producer yielding the volume level to apply (0.0–1.0)
	 * @return a Factor that scales the input by the given volume
	 */
	default Factor<PackedCollection> volume(Producer<PackedCollection> volume) {
		return in -> multiply(in, volume);
	}

	/**
	 * Wraps the given {@link Factor} in an {@link EnvelopeSection} starting at time zero.
	 *
	 * @param envelope the shaping factor to wrap
	 * @return an EnvelopeSection driven by the current time source
	 */
	default EnvelopeSection envelope(Factor<PackedCollection> envelope) {
		return new EnvelopeSection(() -> time(), envelope);
	}

	/**
	 * Creates a full ADSR (Attack-Decay-Sustain-Release) envelope.
	 * <p>
	 * The attack time is automatically clamped to 75% of the total duration and
	 * the decay time is clamped to 25% of the total duration.
	 * </p>
	 *
	 * @param duration total envelope duration in seconds
	 * @param attack   attack time in seconds
	 * @param decay    decay time in seconds
	 * @param sustain  sustain level (0.0–1.0)
	 * @param release  release time in seconds
	 * @return a multi-segment EnvelopeSection implementing the ADSR shape
	 */
	default EnvelopeSection envelope(Producer<PackedCollection> duration,
									 Producer<PackedCollection> attack,
									 Producer<PackedCollection> decay,
									 Producer<PackedCollection> sustain,
									 Producer<PackedCollection> release) {
		Producer<PackedCollection> drAttack = c(0.75);
		Producer<PackedCollection> drDecay = c(0.25);

		attack = min(attack, multiply(drAttack, duration));
		decay = min(decay, multiply(drDecay, duration));

		return envelope(attack(attack))
				.andThenDecay(attack, decay, sustain)
				.andThen(add(attack, decay), sustain(sustain))
				.andThenRelease(duration, sustain, release, c(0.0));
	}

	/**
	 * Creates a constant-level sustain factor that multiplies the input signal by the sustain volume.
	 *
	 * @param volume sustain level (0.0–1.0)
	 * @return a Factor that holds the input at the given volume level
	 */
	default Factor<PackedCollection> sustain(Producer<PackedCollection> volume) {
		return in -> multiply(in, volume);
	}

	/**
	 * Creates a linear ramp factor from {@code startVolume} to {@code endVolume} over {@code duration},
	 * starting at {@code offset} in the timeline.
	 *
	 * @param offset      time offset from the start of the timeline in seconds
	 * @param duration    duration of the ramp in seconds
	 * @param startVolume starting amplitude (0.0–1.0)
	 * @param endVolume   ending amplitude (0.0–1.0)
	 * @return a Factor that linearly interpolates the input amplitude over the specified interval
	 */
	default Factor<PackedCollection> linear(Producer<PackedCollection> offset,
												    Producer<PackedCollection> duration,
													Producer<PackedCollection> startVolume,
													Producer<PackedCollection> endVolume) {
		return in -> {
			Producer<PackedCollection> t = subtract(time(), offset);
			Producer<PackedCollection> pos = divide(t, duration);
			Producer<PackedCollection> start = subtract(c(1.0), pos).multiply(startVolume);
			Producer<PackedCollection> end = multiply(endVolume, pos);
			Producer<PackedCollection> level = max(c(0.0), add(start, end));
			return multiply(in, level);
		};
	}

	/**
	 * Creates an attack ramp factor that linearly increases from 0 to full amplitude
	 * over the specified attack time, then remains at full amplitude.
	 *
	 * @param attack attack duration in seconds
	 * @return a Factor implementing the attack ramp
	 */
	default Factor<PackedCollection> attack(Producer<PackedCollection> attack) {
		return in -> multiply(in, min(c(1.0), divide(time(), attack)));
	}

	/**
	 * Creates a decay ramp factor that linearly decreases from full amplitude to {@code endVolume}
	 * over the specified decay time, starting at {@code offset}.
	 *
	 * @param offset    time offset in seconds where the decay begins
	 * @param decay     decay duration in seconds
	 * @param endVolume target amplitude at the end of the decay (0.0–1.0)
	 * @return a Factor implementing the decay ramp
	 */
	default Factor<PackedCollection> decay(Producer<PackedCollection> offset,
											  Producer<PackedCollection> decay,
											  Producer<PackedCollection> endVolume) {
		return linear(offset, decay, c(1.0), endVolume);
	}

	/**
	 * Creates a release ramp factor that linearly decreases from {@code startVolume} to {@code endVolume}
	 * over the specified release time, starting at {@code offset}.
	 *
	 * @param offset      time offset in seconds where the release begins
	 * @param startVolume amplitude at the start of the release (0.0–1.0)
	 * @param release     release duration in seconds
	 * @param endVolume   target amplitude at the end of the release (0.0–1.0)
	 * @return a Factor implementing the release ramp
	 */
	default Factor<PackedCollection> release(Producer<PackedCollection> offset,
											  Producer<PackedCollection> startVolume,
											  Producer<PackedCollection> release,
											  Producer<PackedCollection> endVolume) {
		return linear(offset, release, startVolume, endVolume);
	}

	/**
	 * Returns an anonymous instance of {@link EnvelopeFeatures} for use in non-interface contexts.
	 *
	 * @return a new EnvelopeFeatures instance
	 */
	static EnvelopeFeatures getInstance() {
		return new EnvelopeFeatures() { };
	}
}
