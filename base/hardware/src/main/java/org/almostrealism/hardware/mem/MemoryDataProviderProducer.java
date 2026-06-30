/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.hardware.mem;

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Provider;
import org.almostrealism.hardware.MemoryData;

/**
 * A {@link Producer} that provides a fixed {@link MemoryData} via a {@link Provider}.
 *
 * <p>This is the base-layer counterpart to {@code CollectionProviderProducer} (which lives in a
 * higher module): it exists so that code in the hardware layer can describe a {@link Provider} input
 * to a computation. Recognising the input as a provider (it reports {@link #isProvider()} as
 * {@code true}) is what lets an {@code Assignment} between two of these be treated as a pure
 * memory-to-memory copy — run through {@code Assignment.Runner}, which the
 * {@link io.almostrealism.code.ComputeContext} resolves at run time into either a direct copy or a
 * queued kernel.</p>
 */
public class MemoryDataProviderProducer implements Producer<MemoryData> {
	/** The fixed memory this producer provides. */
	private final MemoryData data;

	/**
	 * Creates a producer of the given memory.
	 *
	 * @param data the memory to provide
	 */
	public MemoryDataProviderProducer(MemoryData data) {
		this.data = data;
	}

	@Override
	public Evaluable<MemoryData> get() {
		return new Provider<>(data);
	}

	@Override
	public boolean isProvider() {
		return true;
	}
}
