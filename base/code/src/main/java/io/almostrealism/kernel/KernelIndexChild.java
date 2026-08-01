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

import io.almostrealism.sequence.Index;
import io.almostrealism.sequence.IndexChild;
import io.almostrealism.expression.Expression;

import java.util.OptionalLong;

/**
 * A composite index formed by pairing a {@link KernelIndex} (outer dimension) with
 * a child {@link Index} (inner dimension).
 *
 * <p>The combined flat index interleaves the outer kernel thread identifier with the
 * inner index, enabling structured access patterns across multi-dimensional data.
 * The upper bound is {@code kernelMaximum * childLimit - 1}.</p>
 *
 * @see KernelIndex
 * @see IndexChild
 */
public class KernelIndexChild extends IndexChild {
	/** The kernel structure context used to determine the outer (kernel) index maximum. */
	private KernelStructureContext context;

	/**
	 * Creates a {@link KernelIndexChild} that pairs a {@link KernelIndex} (derived from
	 * the given context) with the provided child index.
	 *
	 * @param context    the kernel structure context for the outer dimension
	 * @param childIndex the inner index whose limit determines the inner dimension size
	 */
	public KernelIndexChild(KernelStructureContext context, Index childIndex) {
		super(new KernelIndex(context), childIndex);
		this.context = context;
	}

	/** {@inheritDoc} */
	@Override
	public String initName() {
		return "k" + getChildIndex().getName();
	}

	/**
	 * Sets this index to emit its value as an alias variable in generated code and
	 * returns a new {@link KernelIndexChild} that shares the same context and child.
	 *
	 * @return a new {@link KernelIndexChild} with alias rendering enabled
	 * @deprecated Use explicit alias management instead.
	 */
	@Deprecated
	public KernelIndexChild renderAlias() {
		setRenderAlias(true);
		return new KernelIndexChild(context, getChildIndex());
	}

	/**
	 * Returns the Java numeric type used for the alias variable, chosen based on whether
	 * the upper bound fits in an {@code int}.
	 *
	 * @return {@link Long} if the upper bound exceeds {@link Integer#MAX_VALUE}; {@link Integer} otherwise
	 */
	public Class<? extends Number> getAliasType() {
		if (upperBound().orElse(Long.MAX_VALUE) > Integer.MAX_VALUE) {
			return Long.class;
		} else {
			return Integer.class;
		}
	}

	/**
	 * Converts a flat combined index to the corresponding outer kernel thread index.
	 *
	 * @param index the flat combined index
	 * @return the outer kernel thread index
	 */
	public int kernelIndex(int index) {
		return Math.toIntExact(index / getChildIndex().getLimit().getAsLong());
	}

	/** {@inheritDoc} */
	@Override
	public OptionalLong upperBound(KernelStructureContext context) {
		if (context == null) context = this.context;
		if (context == null) return OptionalLong.empty();

		OptionalLong max = context.getKernelMaximum();
		if (!max.isPresent()) return OptionalLong.empty();

		OptionalLong limit = getChildIndex().getLimit();
		if (!limit.isPresent()) return OptionalLong.empty();

		return OptionalLong.of(max.getAsLong() * limit.getAsLong() - 1);
	}

	/** {@inheritDoc} */
	@Override
	public boolean isPossiblyNegative() {
		return getChildren().get(1).isPossiblyNegative();
	}

	/** {@inheritDoc} */
	@Override
	public boolean compare(Expression o) {
		if (!(o instanceof KernelIndexChild)) return false;
		return ((KernelIndexChild) o).getChildIndex().equals(getChildIndex());
	}
}
