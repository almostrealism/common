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

package io.almostrealism.kernel;

import java.util.OptionalLong;

/**
 * A minimal {@link KernelStructureContext} that carries only an optional kernel maximum
 * and provides no {@link KernelSeriesProvider} or {@link KernelTraversalProvider}.
 *
 * <p>This is the default context used when no backend-specific optimisations are required,
 * and is also produced by {@link KernelStructureContext#asNoOp()}.</p>
 */
public class NoOpKernelStructureContext implements KernelStructureContext {
	/** The maximum valid kernel index, or empty if the kernel is unbounded. */
	private OptionalLong kernelMaximum;

	/**
	 * Creates a {@link NoOpKernelStructureContext} with no kernel maximum.
	 */
	public NoOpKernelStructureContext() { this.kernelMaximum = OptionalLong.empty(); }

	/**
	 * Creates a {@link NoOpKernelStructureContext} reporting the given kernel
	 * iteration count from {@link #getKernelMaximum()}.
	 *
	 * @param kernelMaximum the kernel iteration count; must be {@code > 0}.
	 *                      See {@link KernelStructureContext#getKernelMaximum()}
	 *                      for why {@code 0} is forbidden — a zero-iteration
	 *                      kernel does not exist, and propagating that lie
	 *                      crashes downstream scope compilers. If the bound is
	 *                      genuinely unknown, use the no-arg constructor
	 *                      instead so {@code getKernelMaximum()} returns
	 *                      {@link OptionalLong#empty()}.
	 * @throws IllegalArgumentException if {@code kernelMaximum <= 0}
	 */
	public NoOpKernelStructureContext(long kernelMaximum) {
		if (kernelMaximum <= 0) {
			throw new IllegalArgumentException(
					"NoOpKernelStructureContext requires kernelMaximum > 0 (got "
					+ kernelMaximum + "). A zero-iteration kernel does not "
					+ "exist — use the no-arg constructor for an unbounded "
					+ "context. See KernelStructureContext#getKernelMaximum().");
		}
		this.kernelMaximum = OptionalLong.of(kernelMaximum);
	}

	/** {@inheritDoc} */
	@Override
	public OptionalLong getKernelMaximum() { return kernelMaximum; }

	/**
	 * {@inheritDoc}
	 *
	 * @return {@code null} always — this context has no series provider
	 */
	@Override
	public KernelSeriesProvider getSeriesProvider() { return null; }

	/**
	 * {@inheritDoc}
	 *
	 * @return {@code null} always — this context has no traversal provider
	 */
	@Override
	public KernelTraversalProvider getTraversalProvider() { return null; }

	/**
	 * {@inheritDoc}
	 *
	 * @return {@code this}
	 */
	@Override
	public NoOpKernelStructureContext asNoOp() { return this; }
}
