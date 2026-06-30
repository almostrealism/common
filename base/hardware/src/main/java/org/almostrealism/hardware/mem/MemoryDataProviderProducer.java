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

import io.almostrealism.compute.Process;
import io.almostrealism.relation.Countable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Provider;
import io.almostrealism.uml.Signature;
import org.almostrealism.hardware.MemoryData;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * A {@link Producer} that provides a fixed {@link MemoryData} via a {@link Provider}.
 *
 * <p>This is the base-layer counterpart to {@code CollectionProviderProducer} (which lives in a
 * higher module): it lets code in the hardware layer describe a {@link Provider} input to a
 * computation. Recognising the input as a provider (it reports {@link #isProvider()} as
 * {@code true}) is what lets an {@code Assignment} between two of these be treated as a pure
 * memory-to-memory copy — run through {@code Assignment.Runner}, which the
 * {@link io.almostrealism.code.ComputeContext} resolves at run time into either a direct copy or a
 * queued kernel.</p>
 *
 * <p>It is a leaf {@link Process} (no children), which gives it a position in the computation tree.
 * That position is what lets the instruction cache rebind a reused copy kernel to this operation's
 * own memory: combined with the constant {@link #signature()} — a provider-to-provider assignment is
 * a single-statement kernel program {@code dest[i] = src[i]} dispatched over {@link #getCountLong()}
 * work items, the offsets bound from the actual memory at run time rather than baked in — one compiled
 * program serves every such copy regardless of its length, each dispatched over its own count.</p>
 *
 * <p>It reports a {@link Countable count} equal to the memory's element count
 * ({@link MemoryData#getMemLength()}), so the assignment it participates in is a flat per-element copy:
 * {@code memLength} (the unrolled statement count) stays {@code 1} and all of the size is carried by
 * the count. This keeps the generated {@link io.almostrealism.scope.Scope} to a single statement and
 * avoids the per-length unrolled kernels (and {@link io.almostrealism.scope.ScopeSettings} statement
 * limit) that a large {@code memLength} would produce.</p>
 */
public class MemoryDataProviderProducer implements Producer<MemoryData>,
		Process<Process<?, ?>, Evaluable<? extends MemoryData>>, Countable, Signature {
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

	/**
	 * Returns the number of elements in the provided memory, which is the count an {@code Assignment}
	 * over this producer dispatches across (with a {@code memLength} of {@code 1}).
	 *
	 * @return the element count of the provided memory
	 */
	@Override
	public long getCountLong() {
		return data.getMemLength();
	}

	@Override
	public Collection<Process<?, ?>> getChildren() {
		return Collections.emptyList();
	}

	@Override
	public Process<Process<?, ?>, Evaluable<? extends MemoryData>> generate(List<Process<?, ?>> children) {
		return this;
	}

	@Override
	public String signature() {
		return "memoryDataProvider";
	}
}
