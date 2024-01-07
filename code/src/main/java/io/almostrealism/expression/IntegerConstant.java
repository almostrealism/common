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

import io.almostrealism.kernel.IndexSequence;
import io.almostrealism.kernel.KernelSeries;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.lang.LanguageOperations;

import java.util.OptionalInt;
import java.util.stream.IntStream;

public class IntegerConstant extends Constant<Integer> {
	private int value;

	public IntegerConstant(Integer value) {
		super(Integer.class);
		this.value = value;
	}

	@Override
	public String getExpression(LanguageOperations lang) {
		return String.valueOf(value);
	}

	@Override
	public OptionalInt intValue() {
		return OptionalInt.of(value);
	}

	@Override
	public OptionalInt upperBound(KernelStructureContext context) { return OptionalInt.of(value); }

	@Override
	public boolean isKernelValue(IndexValues values) { return true; }

	@Override
	public KernelSeries kernelSeries() { return KernelSeries.constant(value); }

	@Override
	public Number value(IndexValues indexValues) { return value; }

	@Override
	public IndexSequence sequence(Index index, int len) {
		return IndexSequence.of(value, len);
	}

	@Override
	public Number evaluate(Number... children) {
		return value;
	}
}
