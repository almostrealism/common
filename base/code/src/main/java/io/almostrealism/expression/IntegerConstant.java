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

import io.almostrealism.sequence.ArrayIndexSequence;
import io.almostrealism.sequence.Index;
import io.almostrealism.sequence.IndexSequence;
import io.almostrealism.sequence.IndexValues;
import io.almostrealism.sequence.KernelSeries;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.lang.LanguageOperations;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * A constant {@link Integer} expression node that holds a literal integer value.
 *
 * <p>Renders using the precision-specific integer formatter. The value is also exposed
 * as an {@link java.util.OptionalInt} and a {@link io.almostrealism.sequence.KernelSeries}
 * with constant periodicity.</p>
 */
public class IntegerConstant extends Constant<Integer> {
	/** The literal integer value held by this constant. */
	private int value;

	/**
	 * Constructs a constant expression for the given integer value.
	 *
	 * @param value the literal integer value
	 */
	public IntegerConstant(Integer value) {
		super(Integer.class);
		this.value = value;
		init();
	}

	@Override
	public String getExpression(LanguageOperations lang) {
		return lang.getPrecision().stringForInt(value);
	}

	@Override
	public Optional<Boolean> booleanValue() {
		return Optional.of(value != 0);
	}

	@Override
	public OptionalInt intValue() {
		return OptionalInt.of(value);
	}

	@Override
	public OptionalLong upperBound(KernelStructureContext context) { return OptionalLong.of(value); }

	@Override
	public KernelSeries kernelSeries() { return KernelSeries.constant(value); }

	@Override
	public Number value(IndexValues indexValues) { return value; }

	@Override
	public IndexSequence sequence(Index index, long len, long limit) {
		return ArrayIndexSequence.of(value, len);
	}

	@Override
	public Number evaluate(Number... children) {
		return value;
	}

	@Override
	public Expression minus() {
		if (enableNegationOptimization) {
			return new IntegerConstant(-value);
		}

		return super.minus();
	}

	@Override
	public boolean compare(Expression e) {
		if (!(e instanceof IntegerConstant)) {
			return false;
		}

		return ((IntegerConstant) e).value == value;
	}
}
