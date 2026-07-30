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

package org.almostrealism.hardware.kernel;

import io.almostrealism.code.ArgumentProvider;
import io.almostrealism.code.Computation;
import io.almostrealism.kernel.KernelSeriesProvider;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.kernel.KernelTraversalProvider;
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.relation.Countable;
import org.almostrealism.hardware.mem.KernelConstantProviderSupplier;

import java.util.OptionalLong;
import java.util.function.Supplier;

/**
 * The {@link KernelStructureContext} of a compiled instruction set, owning the
 * kernel structure resources its generated code references.
 *
 * <p>The {@link KernelSeriesCache} and {@link KernelTraversalOperationGenerator} are
 * materialized during compilation and referenced by the compiled code on every
 * execution — including executions by operations that reuse the instructions through
 * signature-based caching. They are therefore part of the compiled kernel, not of the
 * compiler that happened to produce it or of any one operation: this context is created
 * and destroyed by the instruction set manager, so the resources live exactly as long
 * as the instructions.</p>
 *
 * <p>{@link #prepareResources(ArgumentProvider)} materializes the resources once, when
 * compilation begins; later calls are no-ops. This is what makes scope preparation safe
 * to repeat — for example, replaying an already-compiled computation's argument fold to
 * verify reuse never manufactures a second set of resources.</p>
 *
 * @see org.almostrealism.hardware.instructions.ComputableInstructionSetManager#getKernelStructureContext
 * @see org.almostrealism.hardware.instructions.ComputationScopeCompiler
 */
public class CompiledKernelStructureContext implements KernelStructureContext, Destroyable {
	/** The computation the instructions were (or will be) compiled from. */
	private final Computation<?> computation;

	/** Cached kernel iteration bound, computed from the computation on first request. */
	private OptionalLong kernelMaximum;

	/** Series cache referenced by the compiled code, materialized at compilation. */
	private KernelSeriesCache seriesCache;

	/** Traversal generator referenced by the compiled code, materialized at compilation. */
	private KernelTraversalOperationGenerator traversalGenerator;

	/**
	 * Creates the structure context for instructions compiled from the given computation.
	 *
	 * @param computation The computation the instructions are compiled from
	 */
	public CompiledKernelStructureContext(Computation<?> computation) {
		this.computation = computation;
	}

	/**
	 * Checks if kernel structure optimization is supported for the computation.
	 * Returns {@code false} for {@link KernelTraversalOperation} to prevent recursive
	 * traversal generation, {@code true} for all other computations.
	 *
	 * @return {@code true} if kernel structure optimization is supported
	 */
	public boolean isKernelStructureSupported() {
		return !(computation instanceof KernelTraversalOperation);
	}

	/**
	 * Materializes the kernel structure resources for compilation, once.
	 *
	 * <p>The first call creates the series cache and traversal generator, wiring their
	 * kernel arguments through the given provider (series cache data is kernel-owned
	 * constant memory — see {@link KernelConstantProviderSupplier}). Later calls are
	 * no-ops: a compiled kernel has exactly one set of structure resources, whatever
	 * how many times its scope is prepared or its argument fold is replayed. Nothing
	 * is created when kernel structure is unsupported for the computation.</p>
	 *
	 * @param manager The argument provider of the compilation pass
	 */
	public void prepareResources(ArgumentProvider manager) {
		if (!isKernelStructureSupported() || seriesCache != null) {
			return;
		}

		seriesCache = KernelSeriesCache.create(computation,
				data -> manager.argumentForInput().apply((Supplier) new KernelConstantProviderSupplier(data)));
		traversalGenerator = KernelTraversalOperationGenerator.create(computation,
				data -> manager.argumentForInput().apply((Supplier) data));
	}

	/**
	 * Returns the iteration count for the compiled kernel, or
	 * {@link OptionalLong#empty()} if it is variable. Cached after the first call.
	 *
	 * <p>A present zero is forbidden by the
	 * {@link KernelStructureContext#getKernelMaximum()} contract. If the backing
	 * {@link Countable} advertises a fixed count of zero, that {@code Countable} is
	 * broken — there is no such thing as a fixed-count zero-iteration kernel. Rather
	 * than silently propagate the lie (and crash at scope compilation), this fails
	 * loudly with the identity of the offending computation, so the caller learns
	 * which {@code Countable} to fix.</p>
	 *
	 * @return the fixed kernel iteration count ({@code > 0}) if known, else empty
	 * @throws IllegalStateException if the backing computation reports
	 *         {@code isFixedCount() == true} with {@code getCountLong() == 0}
	 */
	@Override
	public OptionalLong getKernelMaximum() {
		if (kernelMaximum == null) {
			if (Countable.isFixedCount(computation)) {
				long count = Countable.countLong(computation);
				if (count <= 0) {
					throw new IllegalStateException(
							"Fixed-count computation " + computation
							+ " reports getCountLong() = " + count
							+ ". A kernel with " + count + " iterations "
							+ "cannot exist. Either the Countable should "
							+ "report isFixedCount() == false (count is "
							+ "variable), or it has been constructed with an "
							+ "invalid size. Fix the Countable; do not relax "
							+ "this check.");
				}
				kernelMaximum = OptionalLong.of(count);
			} else {
				kernelMaximum = OptionalLong.empty();
			}
		}

		return kernelMaximum;
	}

	/**
	 * Returns the kernel series provider for GPU optimization.
	 *
	 * @return the {@link KernelSeriesProvider} once resources are materialized and
	 *         kernel structure is supported, otherwise {@code null}
	 */
	@Override
	public KernelSeriesProvider getSeriesProvider() {
		return isKernelStructureSupported() ? seriesCache : null;
	}

	/**
	 * Returns the traversal provider for kernel index reordering.
	 *
	 * @return the {@link KernelTraversalProvider} once resources are materialized and
	 *         kernel structure is supported, otherwise {@code null}
	 */
	@Override
	public KernelTraversalProvider getTraversalProvider() {
		return isKernelStructureSupported() ? traversalGenerator : null;
	}

	/**
	 * Destroys the kernel structure resources.
	 *
	 * <p>Called by the instruction set manager that owns this context, when the
	 * compiled instructions themselves are released.</p>
	 */
	@Override
	public void destroy() {
		if (seriesCache != null) {
			seriesCache.destroy();
			seriesCache = null;
		}

		if (traversalGenerator != null) {
			traversalGenerator.destroy();
			traversalGenerator = null;
		}
	}
}
