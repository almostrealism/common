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

import io.almostrealism.kernel.ArrayIndexSequence;
import io.almostrealism.kernel.Index;
import io.almostrealism.kernel.IndexSequence;
import io.almostrealism.kernel.IndexValues;
import io.almostrealism.kernel.KernelSeries;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.lang.LanguageOperations;

import java.util.OptionalDouble;
import java.util.OptionalLong;

public class DoubleConstant extends Constant<Double> {
	private double value;

	public DoubleConstant(Double value) {
		super(Double.class);
		this.value = value;
		init();
	}

	@Override
	public String getExpression(LanguageOperations lang) { return lang.getPrecision().stringForDouble(value); }

	@Override
	public OptionalDouble doubleValue() {
		return OptionalDouble.of(value);
	}

	@Override
	public KernelSeries kernelSeries() {
		return KernelSeries.constant(value);
	}

	@Override
	public Number value(IndexValues indexValues) {
		return value;
	}

	@Override
	public IndexSequence sequence(Index index, long len, long limit) {
		return ArrayIndexSequence.of(value, len);
	}

	@Override
	public OptionalLong upperBound(KernelStructureContext context) {
		return OptionalLong.of((long) Math.ceil(value));
	}

	@Override
	public Number evaluate(Number... children) {
		return value;
	}

	@Override
	public Expression minus() {
		if (enableNegationOptimization) {
			return new DoubleConstant(-value);
		}

		return super.minus();
	}

	@Override
	public boolean compare(Expression e) {
		if (!(e instanceof DoubleConstant)) {
			return false;
		}

		return Double.compare(((DoubleConstant) e).value, value) == 0;
	}
}
