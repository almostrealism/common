/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.hardware.mem;

import io.almostrealism.profile.OperationInfo;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.relation.Delegated;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Provider;
import org.almostrealism.hardware.MemoryData;

import java.util.function.Supplier;

/**
 * Supplier that provides constant memory owned by a compiled kernel, such as the
 * {@link MemoryDataCacheManager} buffer backing a
 * {@link org.almostrealism.hardware.kernel.KernelSeriesCache}.
 *
 * <p>Memory supplied through this type is materialized by the compiler itself during
 * compilation, written once, and read by every execution of the compiled kernel — including
 * executions by operations that reuse the kernel through signature-based instruction caching.
 * It is part of the compiled kernel, not of any one operation, which has two consequences that
 * {@link MemoryDataArgumentMap} honors by recognizing this type:</p>
 * <ul>
 *   <li>It is never an argument-aggregation target. Folding it into an operation's aggregate
 *       would entangle kernel-owned constant data with the per-operation copy plan, producing
 *       an aggregate layout that only exists after full compilation — one that operations
 *       reusing the kernel (which do not compile) could never reproduce or rebind.</li>
 *   <li>It always becomes a standalone kernel argument, so reusing operations resolve it to
 *       the original buffer, which holds exactly the values their identical computation
 *       would have produced.</li>
 * </ul>
 *
 * @see MemoryDataCacheManager
 * @see MemoryDataArgumentMap
 * @see org.almostrealism.hardware.kernel.KernelSeriesCache
 */
public class KernelConstantProviderSupplier implements Supplier<Evaluable<? extends MemoryData>>,
		Delegated<Provider>, OperationInfo {
	/** Provider wrapping the kernel-owned constant memory. */
	private final Provider provider;
	/** Metadata for profiling and identification of this supplier. */
	private final OperationMetadata metadata;

	/**
	 * Creates a supplier for the given kernel-owned constant memory.
	 *
	 * @param data The compiler-materialized constant memory to provide
	 */
	public KernelConstantProviderSupplier(MemoryData data) {
		this.provider = new Provider<>(data);
		this.metadata = new OperationMetadata("kernelConstant", "KernelConstantProviderSupplier");
	}

	@Override
	public OperationMetadata getMetadata() { return metadata; }

	@Override
	public Evaluable<? extends MemoryData> get() { return provider; }

	@Override
	public Provider getDelegate() { return provider; }

	@Override
	public String describe() {
		return getMetadata().describe();
	}
}
