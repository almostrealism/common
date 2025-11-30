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

import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Factor;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.mem.Heap;

@Deprecated
public class AudioProcessingUtils {
	public static final int MAX_SECONDS = 180;
	public static final int MAX_SECONDS_FILTER = 90;

	public static boolean enableMultiOrderFilter = true;

	private static final AudioSumProvider sum;
	private static final Evaluable<PackedCollection> reverse;
	private static final Evaluable<PackedCollection> layerEnv;
	private static final EnvelopeProcessor filterEnv;
	private static final Evaluable<PackedCollection> volumeEnv;

	static {
		sum = new AudioSumProvider();

		EnvelopeFeatures o = EnvelopeFeatures.getInstance();

		if (Heap.getDefault() != null) {
			throw new RuntimeException();
		}

		reverse = o.c(o.cv(o.shape(-1), 0),
					o.sizeOf(o.cv(o.shape(-1), 0)).subtract(o.integers()))
				.get();

		CollectionProducer mainDuration = o.cv(o.shape(1), 1);
		CollectionProducer duration0 = mainDuration.multiply(o.cv(o.shape(1), 2));
		CollectionProducer duration1 = mainDuration.multiply(o.cv(o.shape(1), 3));
		CollectionProducer duration2 = mainDuration.multiply(o.cv(o.shape(1), 4));
		CollectionProducer volume0 = o.cv(o.shape(1), 5);
		CollectionProducer volume1 = o.cv(o.shape(1), 6);
		CollectionProducer volume2 = o.cv(o.shape(1), 7);
		CollectionProducer volume3 = o.cv(o.shape(1), 8);

		Factor<PackedCollection> volumeFactor =
				o.envelope(o.v(1, 1),
						o.v(1, 2), o.v(1, 3),
						o.v(1, 4), o.v(1, 5)).get();
		volumeEnv = o.sampling(OutputLine.sampleRate, MAX_SECONDS,
				() -> volumeFactor.getResultant(o.v(o.shape(-1), 0))).get();

		Factor<PackedCollection> layerFactor =
				o.envelope(o.linear(o.c(0.0), duration0, volume0, volume1))
						.andThenRelease(duration0, volume1, duration1.subtract(duration0), volume2)
						.andThenRelease(duration1, volume2, duration2.subtract(duration1), volume3).get();
		layerEnv = o.sampling(OutputLine.sampleRate, MAX_SECONDS,
				() -> layerFactor.getResultant(o.v(o.shape(-1), 0))).get();

		if (enableMultiOrderFilter) {
			filterEnv = new MultiOrderFilterEnvelopeProcessor(OutputLine.sampleRate, MAX_SECONDS_FILTER);
		} else {
			filterEnv = new FilterEnvelopeProcessor(OutputLine.sampleRate, MAX_SECONDS_FILTER);
		}
	}

	public static AudioSumProvider getSum() {
		return sum;
	}

	public static Evaluable<PackedCollection> getReverse() { return reverse; }

	public static Evaluable<PackedCollection> getLayerEnv() {
		return layerEnv;
	}

	public static Evaluable<PackedCollection> getVolumeEnv() {
		return volumeEnv;
	}

	public static EnvelopeProcessor getFilterEnv() {
		return filterEnv;
	}

	public static void init() { }

	public static void destroy() {
		if (filterEnv instanceof Destroyable) {
			((Destroyable) filterEnv).destroy();
		}
	}
}
