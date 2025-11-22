/*
 * Copyright 2020 Michael Murray
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

package io.almostrealism.code;

import io.almostrealism.expression.Expression;
import io.almostrealism.expression.InstanceReference;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.relation.Delegated;
import io.almostrealism.uml.Plural;

/**
 * Represents an array data structure in the expression system.
 * <p>
 * This interface extends {@link Plural} to provide indexed access to elements
 * and {@link Delegated} to support delegation patterns. It defines the contract
 * for array-like structures that can be accessed by index and have a known length.
 * </p>
 * <p>
 * Arrays support two forms of element access:
 * <ul>
 *   <li>Constant integer index via {@link #valueAt(int)}</li>
 *   <li>Dynamic expression index via {@link #valueAt(Expression)}</li>
 * </ul>
 * </p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * Array<Double, ?> arr = ...;
 * Expression<Double> elem0 = arr.valueAt(0);  // Constant index
 * Expression<Double> elemN = arr.valueAt(indexExpr);  // Dynamic index
 * Expression<Integer> len = arr.length();
 * }</pre>
 *
 * @param <T> the element type of the array
 * @param <V> the concrete array implementation type (for self-referential typing)
 * @see Plural
 * @see Delegated
 * @see Expression
 */
public interface Array<T, V extends Array<T, ?>> extends Plural<Expression<T>>, Delegated<V> {
	/**
	 * Returns the element at the specified constant index position.
	 * <p>
	 * This is a convenience method that wraps the integer position in an
	 * {@link IntegerConstant} and delegates to {@link #valueAt(Expression)}.
	 * </p>
	 *
	 * @param pos the zero-based index position
	 * @return an expression representing the element at the specified position
	 */
	@Override
	default Expression<T> valueAt(int pos) {
		return valueAt(new IntegerConstant(pos));
	}

	/**
	 * Returns the element at the position specified by an expression.
	 * <p>
	 * This method supports dynamic indexing where the index may be computed
	 * at runtime or depend on kernel iteration variables.
	 * </p>
	 *
	 * @param exp the expression representing the index position
	 * @return an expression representing the element at the computed position
	 */
	Expression<T> valueAt(Expression<?> exp);

	/**
	 * Returns the length of this array.
	 *
	 * @return an expression representing the array length as an integer
	 */
	Expression<Integer> length();
}
