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

package org.almostrealism.audio.data;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.audio.sources.SineWaveCellData;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.temporal.DefaultWaveCellData;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.mem.Heap;

import java.util.function.IntFunction;
import java.util.function.Supplier;

public class PolymorphicAudioData extends DefaultWaveCellData implements SineWaveCellData, AudioFilterData {
	public PolymorphicAudioData() {
		super();
	}

	public PolymorphicAudioData(MemoryData delegate, int delegateOffset) {
		super(delegate, delegateOffset);
	}

	@Override
	public Heap getDefaultDelegate() { return Heap.getDefault(); }

	public static PackedCollection bank(int count) {
		return new PackedCollection(new TraversalPolicy(count, SIZE * 2), 1, delegateSpec ->
			new PolymorphicAudioData(delegateSpec.getDelegate(), delegateSpec.getOffset()));
	}

	public static Supplier<PolymorphicAudioData> supply(IntFunction<PackedCollection> supply) {
		return () -> new PolymorphicAudioData(supply.apply(2 * SIZE), 0);
	}
}
