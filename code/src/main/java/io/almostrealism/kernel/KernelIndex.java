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

import java.util.OptionalInt;
import java.util.OptionalLong;

public class KernelIndex extends DefaultIndex {
	private static IndexSequence kernelSeq;

	private KernelStructureContext context;
	private int axis;

	public KernelIndex() {
		this(null);
	}

	public KernelIndex(KernelStructureContext context) {
		this(context, 0);
	}

	public KernelIndex(KernelStructureContext context, int axis) {
		super(null);
		this.context = context;
		this.axis = axis;
	}

	@Override
	public String getName() { return "kernel" + axis; }

	@Override
	public String getExpression(LanguageOperations lang) {
		return lang.kernelIndex(axis);
	}

	@Override
	public KernelStructureContext getStructureContext() {
		return context;
	}

	public int getKernelAxis() { return axis; }

	@Override
	public OptionalLong upperBound(KernelStructureContext context) {
		if (context == null) context = this.context;
		if (context == null) return OptionalLong.empty();
		return context.getKernelMaximum().stream().map(i -> i - 1).findFirst();
	}

	/** @return  zero */
	@Override
	public OptionalLong lowerBound(KernelStructureContext context) {
		return OptionalLong.of(0);
	}

	@Override
	public boolean isValue(IndexValues values) { return values.getKernelIndex() != null; }

	@Override
	public Expression<Integer> withIndex(Index index, Expression<?> e) {
		OptionalInt v = e.intValue();
		if (v.isPresent()) return withIndex(index, v.getAsInt());
		return super.withIndex(index, e);
	}

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

	@Override
	public KernelIndex withLimit(long limit) {
		if (context != null) {
			throw new UnsupportedOperationException();
		}

		return new KernelIndex(new NoOpKernelStructureContext(limit), axis);
	}

	@Override
	public KernelSeries kernelSeries() {
		return KernelSeries.infinite(1);
	}

	@Override
	public Number value(IndexValues values) {
		Number idx = values.getKernelIndex();
		if (idx != null) return idx;
		throw new UnsupportedOperationException();
	}

	@Override
	public IndexSequence sequence(Index index, long len, long limit) {
		if (!(index instanceof KernelIndex) || len > Integer.MAX_VALUE) {
			return super.sequence(index, len, limit);
		}

		if (kernelSeq == null || kernelSeq.lengthLong() < len) {
			updateKernelSeq(len);
		}

		return kernelSeq.subset(len);
	}

	@Override
	public Expression<Integer> simplify(KernelStructureContext context, int depth) {
		if (context.getKernelMaximum().isPresent() && context.getKernelMaximum().getAsLong() == 1) {
			return new IntegerConstant(0);
		}

		return super.simplify(context, depth);
	}

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
