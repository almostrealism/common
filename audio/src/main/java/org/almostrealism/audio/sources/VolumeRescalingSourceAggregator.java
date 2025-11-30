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
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.filter.VolumeEnvelopeExtraction;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

public class VolumeRescalingSourceAggregator implements SourceAggregator, CellFeatures {
	private final VolumeEnvelopeExtraction envExtract;

	public VolumeRescalingSourceAggregator() {
		envExtract = new VolumeEnvelopeExtraction();
	}

	@Override
	public Producer<PackedCollection> aggregate(BufferDetails buffer,
												   Producer<PackedCollection> params,
												   Producer<PackedCollection> frequency,
												   Producer<PackedCollection>... sources) {
		CollectionProducer input = c(sources[0]);
		CollectionProducer filter = c(sources[1]);

		Producer<PackedCollection> inputEnv = envExtract.filter(buffer, params, input);
		Producer<PackedCollection> filterEnv = envExtract.filter(buffer, params, filter);
		filterEnv = pad(shape(inputEnv), filterEnv, 0);

		return multiply(input, divide(filterEnv, inputEnv));
	}
}
