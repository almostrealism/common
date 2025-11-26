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

package org.almostrealism.graph.temporal;

import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.hardware.MemoryData;

/**
 * Default implementation of {@link WaveCellData} backed by a {@link PackedCollection}.
 *
 * <p>{@code DefaultWaveCellData} provides concrete storage for wave cell audio
 * processing state. It allocates a packed collection organized as a 15x2 memory
 * structure (SIZE=15 slots, each with 2 elements).</p>
 *
 * <p>This class implements the memory layout defined by {@link BaseAudioData}
 * and {@link WaveCellData}, providing indexed access to:</p>
 * <ul>
 *   <li>Slots 0-2: Base audio data (position, length, amplitude)</li>
 *   <li>Slots 3-4: Wave cell data (wave index, wave count)</li>
 *   <li>Slot 9: Current output value</li>
 * </ul>
 *
 * @author Michael Murray
 * @see WaveCellData
 * @see BaseAudioData
 * @see WaveCell
 */
public class DefaultWaveCellData extends PackedCollection<PackedCollection<?>> implements WaveCellData {
	/** Total number of slots in the data structure. */
	public static final int SIZE = 15;

	/**
	 * Creates a new DefaultWaveCellData with its own memory allocation.
	 */
	public DefaultWaveCellData() {
		super(new TraversalPolicy(SIZE, 2), 1, delegateSpec ->
				new PackedCollection<>(new TraversalPolicy(2), 1, null,
						delegateSpec.getDelegate(), delegateSpec.getOffset()));
	}

	/**
	 * Creates a new DefaultWaveCellData that references existing memory.
	 *
	 * @param delegate       the memory data to use as backing storage
	 * @param delegateOffset the offset within the delegate memory
	 */
	public DefaultWaveCellData(MemoryData delegate, int delegateOffset) {
		super(new TraversalPolicy(SIZE, 2), 1, delegateSpec ->
				new PackedCollection<>(new TraversalPolicy(2), 1, null,
						delegateSpec.getDelegate(), delegateSpec.getOffset()),
				delegate, delegateOffset);
	}
}
