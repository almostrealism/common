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

public class ModularSourceAggregator implements SourceAggregator, CodeFeatures {
	private static long count = 0;

	private final InputType[] inputs;

	private final SummingSourceAggregator sum;
	private final FrequencyRescalingSourceAggregator eqAdjust;
	private final VolumeRescalingSourceAggregator volumeAdjust;
	private final long index;

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

	public enum InputType {
		SOURCE, FREQUENCY, FREQUENCY_ENVELOPE, VOLUME_ENVELOPE
	}
}
