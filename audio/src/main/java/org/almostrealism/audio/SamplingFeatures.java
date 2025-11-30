/*
 * Copyright 2025 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.audio;

import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

import java.util.function.Supplier;

public interface SamplingFeatures extends CodeFeatures {
	ThreadLocal<Integer> sampleRate = new ThreadLocal<>();
	ThreadLocal<Producer<PackedCollection>> frames = new ThreadLocal<>();

	default <T> T frames(Producer<PackedCollection> f, Supplier<T> r) {
		Producer<PackedCollection> lastT = frames.get();

		try {
			frames.set(f);
			return r.get();
		} finally {
			frames.set(lastT);
		}
	}

	default Producer<PackedCollection> frame() {
		Producer<PackedCollection> f = frames.get();
		if (f == null) {
			throw new UnsupportedOperationException();
		}

		return f;
	}

	default CollectionProducer time() { return divide(frame(), c(sampleRate())); }

	default <T> T sampleRate(int sr, Supplier<T> r) {
		Integer lastSr = sampleRate.get();

		try {
			sampleRate.set(sr);
			return r.get();
		} finally {
			sampleRate.set(lastSr);
		}
	}

	default int sampleRate() { return sampleRate.get() == null ? OutputLine.sampleRate : sampleRate.get(); }

	default <T> T sampling(int rate, Supplier<T> r) {
		return sampleRate(rate, () -> frames(integers(), r));
	}

	default <T> T sampling(int rate, double duration, Supplier<T> r) {
//		int frames = (int) (rate * duration);
//		return sampleRate(rate, () -> frames(integers(0, frames), r));
		return sampleRate(rate, () -> frames(integers(), r));
	}

	default int toFrames(double sec) { return (int) (sampleRate() * sec); }

	default Producer<PackedCollection> toFrames(Producer<PackedCollection> sec) {
		return multiply(c(sampleRate()), sec);
	}

	default int toFramesMilli(int msec) { return (int) (sampleRate() * msec / 1000d); }

	default Producer<PackedCollection> toFramesMilli(Producer<PackedCollection> msec) {
		return multiply(c(sampleRate() / 1000d), msec);
	}

	default CollectionProducer grains(Producer<PackedCollection> input,
									  Producer<PackedCollection> grain,
									  Producer<PackedCollection> wavelength,
									  Producer<PackedCollection> phase,
									  Producer<PackedCollection> amp) {
		CollectionProducer start = c(grain, 0).multiply(c(sampleRate()));
		CollectionProducer d = c(grain, 1).multiply(c(sampleRate()));
		CollectionProducer rate = c(grain, 2);
		CollectionProducer w = multiply(wavelength, c(sampleRate()));

		Producer<PackedCollection> series = frame();
//		Producer<PackedCollection> max = subtract(p(count), start);
//		Producer<PackedCollection> pos  = start.add(_mod(_mod(series, d), max));
		Producer<PackedCollection> pos  = start.add(mod(series, d));

		CollectionProducer generate = interpolate(input, pos, rate);
		return generate.multiply(sinw(series, w, phase, amp));
	}
}
