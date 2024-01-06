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
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.lang.LanguageOperations;

import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.stream.IntStream;

public class DoubleConstant extends Constant<Double> {
	private double value;

	public DoubleConstant(Double value) {
		super(Double.class);
		this.value = value;
	}

	@Override
	public String getExpression(LanguageOperations lang) { return lang.getPrecision().stringForDouble(value); }

	@Override
	public OptionalDouble doubleValue() {
		return OptionalDouble.of(value);
	}

	@Override
	public boolean isKernelValue(IndexValues values) { return true; }

	@Override
	public KernelSeries kernelSeries() {
		return KernelSeries.constant(value);
	}

	@Override
	public Number kernelValue(IndexValues indexValues) {
		return value;
	}

	@Override
	public Number[] kernelSeq(int len) {
		return IntStream.range(0, len).mapToObj(i -> value).toArray(Number[]::new);
	}

	@Override
	public OptionalInt upperBound(KernelStructureContext context) {
		return OptionalInt.of((int) Math.ceil(value));
	}

	@Override
	public Number evaluate(Number... children) {
		return value;
	}
}
