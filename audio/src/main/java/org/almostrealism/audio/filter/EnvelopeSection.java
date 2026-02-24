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
	public static boolean enableRepeat = false;

	private Supplier<Producer<PackedCollection>> time;
	private final Producer<PackedCollection> start;
	private final Supplier<Factor<PackedCollection>> lastEnvelope;
	private final Factor<PackedCollection> envelope;

	public EnvelopeSection(Supplier<Producer<PackedCollection>> time,
						   Factor<PackedCollection> envelope) {
		this(time, null, null, envelope);
	}

	public EnvelopeSection(Supplier<Producer<PackedCollection>> time,
						   Producer<PackedCollection> start,
						   Supplier<Factor<PackedCollection>> lastEnvelope,
						   Factor<PackedCollection> envelope) {
		this.time = time;
		this.start = start;
		this.lastEnvelope = lastEnvelope;
		this.envelope = envelope;
	}

	public Supplier<Producer<PackedCollection>> getTime() {
		return time;
	}

	public void setTime(Supplier<Producer<PackedCollection>> time) {
		this.time = time;
	}

	public EnvelopeSection andThen(Producer<PackedCollection> start, Factor<PackedCollection> envelope) {
		return new EnvelopeSection(time, start, this, envelope);
	}

	public EnvelopeSection andThenDecay(Producer<PackedCollection> offset,
										Producer<PackedCollection> decay,
										Producer<PackedCollection> endVolume) {
		return andThen(offset, decay(offset, decay, endVolume));
	}

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
