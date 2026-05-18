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
	 * Returns the upper bound on kernel iterations for this context, or
	 * {@link OptionalLong#empty()} if the bound is not known.
	 *
	 * <h2>Contract — read this before returning anything</h2>
	 *
	 * <p>The returned value, if present, is the loop bound {@code N} in
	 * {@code for (long i = 0; i < N; i++)} — the number of times the compiled
	 * kernel will execute. This is the ONLY valid interpretation.</p>
	 *
	 * <p><strong>A present zero is nonsense and is forbidden.</strong> A kernel
	 * with zero iterations does not exist; it is not a program that "runs and
	 * does nothing," it is a program that was never built. Every caller that
	 * consumes this value — {@link io.almostrealism.expression.Expression}
	 * simplification, {@link KernelIndex} bound inference, and native scope
	 * compilers — assumes the number is positive. Silently tolerating a zero
	 * downstream only hides the defect and invites the next caller to produce
	 * the same broken state. If this method ever returns
	 * {@code OptionalLong.of(0)}, the implementation is wrong.</p>
	 *
	 * <p><strong>If you do not know the bound, return
	 * {@link OptionalLong#empty()}.</strong> That is what "unknown" looks like.
	 * Never invent a placeholder value and never pass {@code 0} to an
	 * {@link OptionalLong#of(long)} call wrapped around a count that might be
	 * zero. If a {@link io.almostrealism.relation.Countable} backs your
	 * implementation and its {@code isFixedCount()} is true with
	 * {@code getCountLong() == 0}, the {@code Countable} itself is lying about
	 * being a fixed-count producer — fix the {@code Countable}, do not paper
	 * over it here.</p>
	 *
	 * <h3>Examples</h3>
	 *
	 * <pre>{@code
	 * // Correct: known positive bound.
	 * return OptionalLong.of(bufferSize);
	 *
	 * // Correct: bound genuinely unknown (e.g. computed dynamically at run time).
	 * return OptionalLong.empty();
	 *
	 * // WRONG — silently forbidden. Will throw IllegalStateException at compile.
	 * return OptionalLong.of(0);
	 *
	 * // WRONG — unchecked propagation of a 0 from upstream.
	 * return OptionalLong.of(countable.getCountLong());  // might be 0!
	 *
	 * // Correct — gate against the zero at construction time.
	 * long n = countable.getCountLong();
	 * return n > 0 ? OptionalLong.of(n) : OptionalLong.empty();
	 * }</pre>
	 *
	 * @return the kernel iteration count ({@code > 0}) if known, else empty
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
