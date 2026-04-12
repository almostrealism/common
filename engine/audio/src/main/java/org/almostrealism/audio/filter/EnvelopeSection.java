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
import org.almostrealism.collect.PackedCollection;

import java.util.function.Supplier;

/**
 * A segment of a multi-phase envelope with chainable construction.
 *
 * <p>EnvelopeSection represents one segment of an envelope (attack, decay,
 * sustain, or release) and supports fluent chaining to build complex
 * multi-segment envelopes. Sections can be combined using {@code andThen}
 * methods.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * EnvelopeSection env = envelope(attack(c(0.1)))
 *     .andThenDecay(c(0.1), c(0.2), c(0.7))
 *     .andThenRelease(c(0.5), c(0.7), c(0.3), c(0.0));
 *
 * Factor<PackedCollection> factor = env.get();
 * }</pre>
 *
 * @see EnvelopeFeatures
 * @see FilterEnvelopeProcessor
 */
public class EnvelopeSection implements Supplier<Factor<PackedCollection>>, EnvelopeFeatures {
	/** When true, uses repeating time-phase logic to wrap the envelope for looping. */
	public static boolean enableRepeat = false;

	/** Supplier for the time producer used to determine which segment is active. */
	private Supplier<Producer<PackedCollection>> time;

	/** The start time of this section; sections with earlier times take precedence. */
	private final Producer<PackedCollection> start;

	/** The preceding envelope section in the chain, evaluated when time is before {@link #start}. */
	private final Supplier<Factor<PackedCollection>> lastEnvelope;

	/** The factor to apply when the current time is past {@link #start}. */
	private final Factor<PackedCollection> envelope;

	/**
	 * Creates a root EnvelopeSection (with no preceding section).
	 *
	 * @param time     supplier for the current time producer
	 * @param envelope the factor to apply unconditionally for this section
	 */
	public EnvelopeSection(Supplier<Producer<PackedCollection>> time,
						   Factor<PackedCollection> envelope) {
		this(time, null, null, envelope);
	}

	/**
	 * Creates an EnvelopeSection that follows a preceding section.
	 *
	 * @param time         supplier for the current time producer
	 * @param start        start time of this section
	 * @param lastEnvelope the preceding section (applied when time is before start)
	 * @param envelope     the factor to apply when time is past start
	 */
	public EnvelopeSection(Supplier<Producer<PackedCollection>> time,
						   Producer<PackedCollection> start,
						   Supplier<Factor<PackedCollection>> lastEnvelope,
						   Factor<PackedCollection> envelope) {
		this.time = time;
		this.start = start;
		this.lastEnvelope = lastEnvelope;
		this.envelope = envelope;
	}

	/** Returns the supplier for the time producer used to determine which section is active. */
	public Supplier<Producer<PackedCollection>> getTime() {
		return time;
	}

	/**
	 * Replaces the time supplier on this and all chained sections.
	 *
	 * @param time the new time supplier
	 */
	public void setTime(Supplier<Producer<PackedCollection>> time) {
		this.time = time;
	}

	/**
	 * Creates a new section that follows this one, active when time passes the given start.
	 *
	 * @param start    the start time of the next section
	 * @param envelope the factor to apply in the next section
	 * @return a new EnvelopeSection chained after this one
	 */
	public EnvelopeSection andThen(Producer<PackedCollection> start, Factor<PackedCollection> envelope) {
		return new EnvelopeSection(time, start, this, envelope);
	}

	/**
	 * Chains a decay section that linearly ramps volume from start to endVolume.
	 *
	 * @param offset    start time of the decay section
	 * @param decay     duration of the decay ramp
	 * @param endVolume target volume at the end of the decay
	 * @return a new EnvelopeSection for the decay phase
	 */
	public EnvelopeSection andThenDecay(Producer<PackedCollection> offset,
										Producer<PackedCollection> decay,
										Producer<PackedCollection> endVolume) {
		return andThen(offset, decay(offset, decay, endVolume));
	}

	/**
	 * Chains a release section that linearly ramps volume from startVolume to endVolume.
	 *
	 * @param offset       start time of the release section
	 * @param startVolume  initial volume at the start of the release
	 * @param release      duration of the release ramp
	 * @param endVolume    target volume at the end of the release
	 * @return a new EnvelopeSection for the release phase
	 */
	public EnvelopeSection andThenRelease(Producer<PackedCollection> offset,
										  Producer<PackedCollection> startVolume,
										  Producer<PackedCollection> release,
										  Producer<PackedCollection> endVolume) {
		return andThen(offset, release(offset, startVolume, release, endVolume));
	}

	@Override
	public Factor<PackedCollection> get() {
		if (lastEnvelope == null) {
			return envelope;
		} else if (enableRepeat) {
			return in ->
					greaterThanConditional(time.get(), repeat(shape(time.get()).getCount(), start),
					envelope.getResultant(in),
					lastEnvelope.get().getResultant(in));
		} else {
			return in ->
					greaterThanConditional(time.get(), start,
							envelope.getResultant(in),
							lastEnvelope.get().getResultant(in));
		}
	}
}
