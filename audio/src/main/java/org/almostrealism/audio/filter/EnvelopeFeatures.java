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

public interface EnvelopeFeatures extends SamplingFeatures {
	default Factor<PackedCollection> volume(Producer<PackedCollection> volume) {
		return in -> multiply(in, volume);
	}

	default EnvelopeSection envelope(Factor<PackedCollection> envelope) {
		return new EnvelopeSection(() -> time(), envelope);
	}

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

	default Factor<PackedCollection> sustain(Producer<PackedCollection> volume) {
		return in -> multiply(in, volume);
	}

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

	default Factor<PackedCollection> attack(Producer<PackedCollection> attack) {
		return in -> multiply(in, min(c(1.0), divide(time(), attack)));
	}

	default Factor<PackedCollection> decay(Producer<PackedCollection> offset,
											  Producer<PackedCollection> decay,
											  Producer<PackedCollection> endVolume) {
		return linear(offset, decay, c(1.0), endVolume);
	}

	default Factor<PackedCollection> release(Producer<PackedCollection> offset,
											  Producer<PackedCollection> startVolume,
											  Producer<PackedCollection> release,
											  Producer<PackedCollection> endVolume) {
		return linear(offset, release, startVolume, endVolume);
	}
	
	static EnvelopeFeatures getInstance() {
		return new EnvelopeFeatures() { };
	}
}
