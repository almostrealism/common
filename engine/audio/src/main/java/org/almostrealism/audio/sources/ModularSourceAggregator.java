/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.audio.sources;

import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Console;

import java.util.stream.IntStream;

/**
 * Routes multiple sources through a processing chain based on input types.
 * Separates inputs into categories (SOURCE, FREQUENCY, VOLUME_ENVELOPE) and
 * applies appropriate aggregation strategies (summing, frequency rescaling,
 * volume rescaling) based on the declared input types. This enables flexible
 * audio mixing with dynamic signal routing.
 *
 * @see SourceAggregator
 * @see SummingSourceAggregator
 * @see FrequencyRescalingSourceAggregator
 * @see VolumeRescalingSourceAggregator
 */
public class ModularSourceAggregator implements SourceAggregator, CodeFeatures {
	/** Running counter used to assign a unique index to each instance for diagnostic purposes. */
	private static long count = 0;

	/** Declared types for each input slot; determines how each source is routed. */
	private final InputType[] inputs;

	/** Aggregator for summing SOURCE-type inputs into a combined audio signal. */
	private final SummingSourceAggregator sum;

	/** Aggregator that applies frequency-domain (EQ) rescaling using FREQUENCY-type inputs. */
	private final FrequencyRescalingSourceAggregator eqAdjust;

	/** Aggregator that applies volume scaling using VOLUME_ENVELOPE-type inputs. */
	private final VolumeRescalingSourceAggregator volumeAdjust;

	/** Unique index assigned at construction time, used for diagnostic logging. */
	private final long index;

	/**
	 * Creates a ModularSourceAggregator with the specified input type declarations.
	 *
	 * @param inputs declared type for each input slot; length must match the number of sources passed to {@link #aggregate}
	 */
	public ModularSourceAggregator(InputType... inputs) {
		this.inputs = inputs;
		this.sum = new SummingSourceAggregator();
		this.eqAdjust = new FrequencyRescalingSourceAggregator();
		this.volumeAdjust = new VolumeRescalingSourceAggregator();
		this.index = count++;
	}

	@Override
	public Producer<PackedCollection> aggregate(BufferDetails buffer,
												   Producer<PackedCollection> params,
												   Producer<PackedCollection> frequency,
												   Producer<PackedCollection>... sources) {
		Producer[] producers = new Producer[sources.length + 1];
		producers[0] = frequency;
		System.arraycopy(sources, 0, producers, 1, sources.length);

		Producer<PackedCollection>[] src = new Producer[sources.length];
		System.arraycopy(producers, 1, src, 0, sources.length);

		Producer<PackedCollection>[] eq = extractInputs(InputType.FREQUENCY, src);
		Producer<PackedCollection>[] volume = extractInputs(InputType.VOLUME_ENVELOPE, src);
		src = extractInputs(InputType.SOURCE, src);

		Producer<PackedCollection> input = sum.aggregate(buffer, null, producers[0], src);
		Producer<PackedCollection> eqInput = eq.length > 0 ? sum.aggregate(buffer, null, producers[0], eq) : null;
		Producer<PackedCollection> volumeInput = volume.length > 0 ? sum.aggregate(buffer, null, producers[0], volume) : null;

		Producer<PackedCollection> out = input;
		out = eqInput == null ? out :
				eqAdjust.aggregate(buffer, null, producers[0], input, eqInput);
		out = volumeInput == null ? out :
				volumeAdjust.aggregate(buffer, null, producers[0], out, volumeInput);
		return out;
	}

	/**
	 * Filters the given sources array to those whose declared input type matches the specified type.
	 *
	 * @param type    the input type to extract
	 * @param sources the full source array to filter
	 * @return a new array containing only the sources whose corresponding declared type matches
	 */
	protected Producer<PackedCollection>[] extractInputs(InputType type, Producer<PackedCollection>... sources) {
		int index = 0;
		int tot = Math.toIntExact(IntStream.range(0, sources.length)
				.filter(i -> i < inputs.length && inputs[i] == type).count());
		Producer<PackedCollection>[] result = new Producer[tot];

		for (int i = 0; i < sources.length; i++) {
			if (inputs[i] == type) {
				result[index++] = sources[i];
			}
		}

		return result;
	}

	@Override
	public Console console() { return CellFeatures.console; }

	/** Declares how a given input slot is interpreted during aggregation. */
	public enum InputType {
		/** A raw audio signal to be summed with other audio sources. */
		SOURCE,
		/** A frequency-domain (EQ) signal for rescaling the summed audio. */
		FREQUENCY,
		/** A frequency envelope signal (reserved for future use). */
		FREQUENCY_ENVELOPE,
		/** A volume envelope signal for amplitude scaling of the summed audio. */
		VOLUME_ENVELOPE
	}
}
