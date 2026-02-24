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

/**
 * Multi-purpose audio data storage combining wave cell, sine wave, and filter data.
 *
 * <p>PolymorphicAudioData provides unified storage for various audio processing needs,
 * implementing {@link SineWaveCellData} for oscillators and {@link AudioFilterData}
 * for filters. This allows a single data structure to be used with different types
 * of audio processors, useful in polymorphic audio cells.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Create a bank of audio data for multiple voices
 * PackedCollection bank = PolymorphicAudioData.bank(8);
 *
 * // Access individual data instances
 * PolymorphicAudioData data = new PolymorphicAudioData(bank, 0);
 * data.setFrequency(440.0);
 * data.setResonance(0.7);
 * }</pre>
 *
 * @see SineWaveCellData
 * @see AudioFilterData
 * @see org.almostrealism.audio.PolymorphicAudioCell
 */
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
