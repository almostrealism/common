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

import io.almostrealism.expression.Expression;

import java.util.OptionalLong;

/**
 * Provides structural constraints and services for kernel expression simplification.
 *
 * <p>A {@code KernelStructureContext} is associated with a compiled kernel and exposes
 * the maximum kernel index, an optional {@link KernelSeriesProvider} for converting
 * expressions to series form, and an optional {@link KernelTraversalProvider} for
 * index reordering. These are used during expression simplification to produce
 * kernel-specific optimisations.</p>
 *
 * @see KernelIndex
 * @see KernelSeriesProvider
 * @see KernelTraversalProvider
 */
public interface KernelStructureContext {
	/**
	 * Returns the maximum valid kernel index for this context, or empty if unbounded.
	 *
	 * @return the kernel maximum as an {@link OptionalLong}
	 */
	OptionalLong getKernelMaximum();

	/**
	 * Returns the {@link KernelSeriesProvider} for this context, or {@code null} if none.
	 *
	 * @return the series provider, or {@code null}
	 */
	KernelSeriesProvider getSeriesProvider();

	/**
	 * Returns the {@link KernelTraversalProvider} for this context, or {@code null} if none.
	 *
	 * @return the traversal provider, or {@code null}
	 */
	KernelTraversalProvider getTraversalProvider();

	/**
	 * Returns {@code true} if the given kernel size is compatible with this context.
	 *
	 * <p>A size is valid when it matches the kernel maximum (if set) and the series
	 * provider's maximum length (if set).</p>
	 *
	 * @param size the kernel size to check
	 * @return {@code true} if the size is valid for this context
	 */
	default boolean isValidKernelSize(long size) {
		if ((getKernelMaximum().isPresent() && size !=
				getKernelMaximum().getAsLong()) ||
				(getSeriesProvider() != null && getSeriesProvider().getMaximumLength().isPresent() &&
						size != getSeriesProvider().getMaximumLength().getAsInt())) {
			return false;
		}

		return true;
	}

	/**
	 * Simplifies the given expression using this context, optionally converting the
	 * result to a series representation via the {@link KernelSeriesProvider}.
	 *
	 * @param expression the expression to simplify
	 * @return the simplified (and optionally series-converted) expression
	 */
	default Expression<?> simplify(Expression<?> expression) {
		Expression<?> e = expression.simplify(this);
		if (getSeriesProvider() != null) {
			e = getSeriesProvider().getSeries(e);
		}
		return e;
	}

	/**
	 * Returns a {@link NoOpKernelStructureContext} that carries the same kernel maximum
	 * but has no series or traversal providers.
	 *
	 * @return a no-op context with the same kernel maximum
	 */
	default NoOpKernelStructureContext asNoOp() {
		return getKernelMaximum().stream()
				.mapToObj(NoOpKernelStructureContext::new)
				.findFirst().orElse(new NoOpKernelStructureContext());
	}
}
