/*
 * Copyright 2025 Michael Murray
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
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.scope.ScopeSettings;
import io.almostrealism.sequence.ArrayIndexSequence;
import io.almostrealism.sequence.DefaultIndex;
import io.almostrealism.sequence.Index;
import io.almostrealism.sequence.IndexSequence;
import io.almostrealism.sequence.IndexValues;
import io.almostrealism.sequence.KernelSeries;

import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * The primary index variable in a GPU/CPU kernel, representing the global thread identifier
 * along a specific axis.
 *
 * <p>Each kernel thread is identified by a {@code KernelIndex} whose value ranges from
 * {@code 0} to {@code kernelMaximum - 1}. The kernel axis allows multi-dimensional indexing
 * to be expressed when a backend supports multiple dispatch dimensions.</p>
 *
 * <p>During expression simplification, if the kernel maximum is known to be 1, the index
 * is replaced by the constant {@code 0}. If the context has changed, a new {@code KernelIndex}
 * bound to the new context is returned.</p>
 *
 * @see KernelIndexChild
 * @see KernelStructureContext
 */
public class KernelIndex extends DefaultIndex {
	/** Cached arithmetic sequence used when {@code enableArithmeticSequence} is disabled. */
	private static IndexSequence kernelSeq;

	/** The structure context that provides the kernel maximum for this index. */
	private KernelStructureContext context;

	/** The dispatch axis along which this index varies. */
	private int axis;

	/**
	 * Creates a {@link KernelIndex} with no context and axis 0.
	 */
	public KernelIndex() {
		this(null);
	}

	/**
	 * Creates a {@link KernelIndex} bound to the given context on axis 0.
	 *
	 * @param context the kernel structure context; may be {@code null}
	 */
	public KernelIndex(KernelStructureContext context) {
		this(context, 0);
	}

	/**
	 * Creates a {@link KernelIndex} bound to the given context and axis.
	 *
	 * @param context the kernel structure context; may be {@code null}
	 * @param axis    the dispatch axis index
	 */
	public KernelIndex(KernelStructureContext context, int axis) {
		super(null);
		this.context = context;
		this.axis = axis;
	}

	/** {@inheritDoc} */
	@Override
	public String getName() { return "kernel" + axis; }

	/** {@inheritDoc} */
	@Override
	public String getExpression(LanguageOperations lang) {
		return lang.kernelIndex(axis);
	}

	/** {@inheritDoc} */
	@Override
	public KernelStructureContext getStructureContext() {
		return context;
	}

	/**
	 * Returns the dispatch axis along which this index varies.
	 *
	 * @return the kernel axis index
	 */
	public int getKernelAxis() { return axis; }

	/** {@inheritDoc} */
	@Override
	public OptionalLong upperBound(KernelStructureContext context) {
		if (context == null) context = this.context;
		if (context == null) return OptionalLong.empty();
		return context.getKernelMaximum().stream().map(i -> i - 1).findFirst();
	}

	/**
	 * {@inheritDoc}
	 *
	 * @return zero (kernel indices always start at 0)
	 */
	@Override
	public OptionalLong lowerBound(KernelStructureContext context) {
		return OptionalLong.of(0);
	}

	/** {@inheritDoc} */
	@Override
	public boolean isValue(IndexValues values) { return values.getKernelIndex() != null; }

	/** {@inheritDoc} */
	@Override
	public Expression<Integer> withIndex(Index index, Expression<?> e) {
		OptionalInt v = e.intValue();
		if (v.isPresent()) return withIndex(index, v.getAsInt());
		return super.withIndex(index, e);
	}

	/** {@inheritDoc} */
	@Override
	public Expression<Integer> withIndex(Index index, int value) {
		if (index instanceof KernelIndexChild) {
			KernelIndexChild child = (KernelIndexChild) index;
			return new IntegerConstant(child.kernelIndex(value));
		}

		if (!(index instanceof KernelIndex)) return this;
		if (((KernelIndex) index).getKernelAxis() != getKernelAxis()) return this;
		return new IntegerConstant(value);
	}

	/** {@inheritDoc} */
	@Override
	public KernelIndex withLimit(long limit) {
		if (context != null) {
			throw new UnsupportedOperationException();
		}

		return new KernelIndex(new NoOpKernelStructureContext(limit), axis);
	}

	/** {@inheritDoc} */
	@Override
	public KernelSeries kernelSeries() {
		return KernelSeries.infinite(1);
	}

	/** {@inheritDoc} */
	@Override
	public Number value(IndexValues values) {
		Number idx = values.getKernelIndex();
		if (idx != null) return idx;
		throw new UnsupportedOperationException();
	}

	/** {@inheritDoc} */
	@Override
	public IndexSequence sequence(Index index, long len, long limit) {
		if (ScopeSettings.enableArithmeticSequence ||
				!(index instanceof KernelIndex) || len > Integer.MAX_VALUE) {
			return super.sequence(index, len, limit);
		}

		if (kernelSeq == null || kernelSeq.lengthLong() < len) {
			updateKernelSeq(len);
		}

		return kernelSeq.subset(len);
	}

	/** {@inheritDoc} */
	@Override
	public Expression<Integer> simplify(KernelStructureContext context, int depth) {
		if (context.getKernelMaximum().isPresent() && context.getKernelMaximum().getAsLong() == 1) {
			return new IntegerConstant(0);
		} else if (!Objects.equals(getStructureContext(), context)) {
			return new KernelIndex(context);
		}

		return super.simplify(context, depth);
	}

	/**
	 * Lazily initialises the shared kernel sequence cache to at least the specified length.
	 *
	 * @param len the required minimum sequence length
	 */
	protected synchronized static void updateKernelSeq(long len) {
		if (len > Integer.MAX_VALUE) return;

		if (kernelSeq == null || kernelSeq.lengthLong() < len) {
			Number seq[] = new Integer[(int) len];
			for (int i = 0; i < len; i++) {
				seq[i] = Integer.valueOf(i);
			}

			kernelSeq = ArrayIndexSequence.of(Integer.class, seq);
		}
	}
}
