/*
 * Copyright 2023 Michael Murray
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

import io.almostrealism.kernel.KernelSeries;
import io.almostrealism.lang.LanguageOperations;

public class KernelIndex extends StaticReference<Integer> {
	private int axis;

	public KernelIndex() {
		this(0);
	}

	public KernelIndex(int axis) {
		super(Integer.class, null);
		this.axis = axis;
	}

	@Override
	public String getExpression(LanguageOperations lang) {
		return lang.kernelIndex(axis);
	}

	public int getKernelAxis() { return axis; }

	@Override
	public boolean isKernelValue() { return true; }

	@Override
	public Expression<Integer> withKernel(int index) {
		if (axis != 0) throw new UnsupportedOperationException();
		return new IntegerConstant(index);
	}

	@Override
	public KernelSeries kernelSeries() {
		return KernelSeries.infinite(1);
	}

	@Override
	public Number kernelValue(int kernelIndex) {
		return Integer.valueOf(kernelIndex);
	}
}
