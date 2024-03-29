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

package io.almostrealism.expression;

import io.almostrealism.kernel.IndexSequence;
import io.almostrealism.kernel.KernelSeries;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.lang.LanguageOperations;

import java.util.OptionalInt;

public class KernelIndex extends DefaultIndex {
	private static IndexSequence kernelSeq;

	private int axis;

	public KernelIndex() {
		this(0);
	}

	public KernelIndex(int axis) {
		super(null);
		this.axis = axis;
	}

	@Override
	public String getName() { return "kernel" + axis; }

	@Override
	public String getExpression(LanguageOperations lang) {
		return lang.kernelIndex(axis);
	}

	public int getKernelAxis() { return axis; }

	@Override
	public OptionalInt upperBound(KernelStructureContext context) {
		return context.getKernelMaximum().stream().map(i -> i - 1).findFirst();
	}

	@Override
	public boolean isKernelValue(IndexValues values) { return true; }

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
	public IndexSequence sequence(Index index, int len) {
		if (!(index instanceof KernelIndex)) {
			return super.sequence(index, len);
		}

		if (kernelSeq == null || kernelSeq.length() < len) {
			updateKernelSeq(len);
		}

		return kernelSeq.subset(len);
	}

	protected synchronized static void updateKernelSeq(int len) {
		if (kernelSeq == null || kernelSeq.length() < len) {
			Number seq[] = new Integer[len];
			for (int i = 0; i < len; i++) {
				seq[i] = Integer.valueOf(i);
			}

			kernelSeq = IndexSequence.of(seq);
		}
	}
}
