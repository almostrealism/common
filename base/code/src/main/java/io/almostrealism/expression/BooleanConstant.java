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

import io.almostrealism.kernel.ArrayIndexSequence;
import io.almostrealism.kernel.Index;
import io.almostrealism.kernel.IndexSequence;
import io.almostrealism.lang.LanguageOperations;

import java.util.Optional;

/**
 * Represents a constant boolean expression in generated code.
 * <p>
 * This class extends {@link Constant} to provide a compile-time boolean value
 * that can be used in code generation. The value is rendered as "true" or "false"
 * in the output code.
 * </p>
 * <p>
 * When evaluated numerically, the boolean value is converted to 1 (true) or 0 (false),
 * which is consistent with C-style boolean semantics.
 * </p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * BooleanConstant trueConst = new BooleanConstant(true);
 * String expr = trueConst.getExpression(lang); // Returns "true"
 * Number num = trueConst.evaluate();           // Returns 1
 * }</pre>
 *
 * @see Constant
 * @see Expression
 */
public class BooleanConstant extends Constant<Boolean> {
	/** The constant boolean value. */
	private boolean value;

	/**
	 * Constructs a new boolean constant with the specified value.
	 *
	 * @param value the boolean value for this constant
	 */
	public BooleanConstant(Boolean value) {
		super(Boolean.class);
		this.value = value;
		init();
	}

	/**
	 * Generates the string representation of this boolean constant.
	 *
	 * @param lang the language operations context (unused for boolean constants)
	 * @return "true" or "false" as a string
	 */
	@Override
	public String getExpression(LanguageOperations lang) {
		return String.valueOf(value);
	}

	/**
	 * Returns the boolean value of this constant.
	 *
	 * @return an Optional containing the boolean value
	 */
	@Override
	public Optional<Boolean> booleanValue() {
		return Optional.of(value);
	}

	/**
	 * Generates an index sequence of constant values for kernel iteration.
	 * <p>
	 * Returns a sequence of length {@code len} where each element is
	 * 1 (if value is true) or 0 (if value is false).
	 * </p>
	 *
	 * @param index the kernel index variable (unused for constants)
	 * @param len   the length of the sequence to generate
	 * @param limit the upper limit for the index (unused for constants)
	 * @return an ArrayIndexSequence of constant 1 or 0 values
	 */
	@Override
	public IndexSequence sequence(Index index, long len, long limit) {
		return ArrayIndexSequence.of(value ? 1 : 0, len);
	}

	/**
	 * Evaluates this boolean constant as a numeric value.
	 * <p>
	 * Returns 1 for true, 0 for false, consistent with C-style boolean semantics.
	 * </p>
	 *
	 * @param children not used for constant expressions
	 * @return 1 if the value is true, 0 if false
	 */
	@Override
	public Number evaluate(Number... children) {
		return value ? 1 : 0;
	}
}
