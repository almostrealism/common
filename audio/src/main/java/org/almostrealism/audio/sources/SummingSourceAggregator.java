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

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.filter.AudioProcessingUtils;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DynamicCollectionProducer;

import java.util.List;
import java.util.stream.Stream;

public class SummingSourceAggregator implements SourceAggregator, CellFeatures {
	@Override
	public Producer<PackedCollection> aggregate(BufferDetails buffer,
												   Producer<PackedCollection> params,
												   Producer<PackedCollection> frequency,
												   Producer<PackedCollection>... sources) {
		return new DynamicCollectionProducer(shape(buffer.getFrames()), null) {
			public Evaluable<PackedCollection> get() {
				List<Evaluable<PackedCollection>> layerAudio =
						Stream.of(sources).map(Producer::get).toList();
				int[] frames = Stream.of(sources)
						.map(SummingSourceAggregator.this::shape)
						.map(shape -> shape.getCount() == 1 ? shape.traverse() : shape)
						.mapToInt(TraversalPolicy::getCount)
						.toArray();

				return args -> {
					int totalFrames = buffer.getFrames();

					PackedCollection dest = PackedCollection.factory().apply(totalFrames);

					for (int i = 0; i < layerAudio.size(); i++) {
						PackedCollection audio = layerAudio.get(i).evaluate(args);
						int f = frames[i] > 1 ? frames[i] : audio.getShape().getTotalSize();
						f = Math.min(f, totalFrames);

						AudioProcessingUtils.getSum().sum(dest.range(shape(f)), audio.range(shape(f)));
					}

					return dest;
				};
			}
		};
	}
}
