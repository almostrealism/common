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

package org.almostrealism.audio.sequence;

import io.almostrealism.relation.Producer;
import org.almostrealism.audio.SamplingFeatures;
import org.almostrealism.audio.data.PolymorphicAudioData;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.temporal.BaseAudioData;
import org.almostrealism.graph.temporal.CollectionTemporalCellAdapter;
import org.almostrealism.hardware.OperationList;

import java.util.List;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class ValueSequenceCell extends CollectionTemporalCellAdapter implements SamplingFeatures {
	private final BaseAudioData data;
	private final List<Producer<PackedCollection>> values;
	private final Producer<PackedCollection> durationFrames;

	public ValueSequenceCell(IntFunction<Producer<PackedCollection>> values,
							 Producer<PackedCollection> duration, int steps) {
		this(new PolymorphicAudioData(), values, duration, steps);
	}

	public ValueSequenceCell(BaseAudioData data,
							 IntFunction<Producer<PackedCollection>> values,
							 Producer<PackedCollection> duration, int steps) {
		this.data = data;
		this.values = IntStream.range(0, steps).mapToObj(values).collect(Collectors.toList());
		this.durationFrames = toFrames(duration);
		addSetup(a(data.getWaveLength(), c(1)));
	}

	@Override
	public Supplier<Runnable> push(Producer<PackedCollection> protein) {
		PackedCollection value = new PackedCollection(1);
		OperationList push = new OperationList("ValueSequenceCell Push");
		push.add(new ValueSequencePush(data, durationFrames, value, values.toArray(Producer[]::new)));
		push.add(super.push(p(value)));
		return push;
	}

	@Override
	public Supplier<Runnable> tick() {
		OperationList tick = new OperationList("ValueSequenceCell Tick");
		tick.add(new ValueSequenceTick(data, durationFrames, values.toArray(Producer[]::new)));
		tick.add(super.tick());
		return tick;
	}
}
