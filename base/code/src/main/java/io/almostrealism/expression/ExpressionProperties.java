/*
 * Copyright 2026 Michael Murray
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

import io.almostrealism.sequence.IndexValues;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.OptionalDouble;

/**
 * Type predicates and compile-time constant-value queries for {@link Expression}.
 *
 * <p>These default methods report what an expression <em>is</em> — its result type, whether it
 * is masked or a single index, and any value it folds to at compile time. They are co-located
 * on this mixin to keep {@link Expression} focused on structure, evaluation, and simplification;
 * concrete subtypes override the ones they can answer more precisely (e.g. constants override
 * {@link #intValue()}). {@link Expression} implements this interface, so every expression
 * exposes these queries.</p>
 *
 * @param <T> the numeric result type of the implementing {@link Expression}
 */
public interface ExpressionProperties<T> {

	/**
	 * Returns this query mixin as the {@link Expression} it is mixed into.
	 *
	 * @return this, viewed as an {@link Expression}
	 */
	private Expression<T> self() {
		return (Expression<T>) this;
	}

	/**
	 * Checks if this expression produces an integer result.
	 *
	 * @return {@code true} if the result type is {@link Integer}
	 */
	public default boolean isInt() { return self().getType() == Integer.class; }

	/**
	 * Checks if this expression produces a floating-point result.
	 *
	 * @return {@code true} if the result type is {@link Double}
	 */
	public default boolean isFP() { return self().getType() == Double.class; }

	/**
	 * Checks if this expression represents a null value.
	 *
	 * @return {@code true} if this is a null expression; default implementation returns {@code false}
	 */
	public default boolean isNull() { return false; }

	/**
	 * Checks if this expression is masked (e.g., wrapped in a conditional or guard).
	 *
	 * @return {@code true} if this expression is masked; default implementation returns {@code false}
	 */
	public default boolean isMasked() { return false; }

	/**
	 * Checks if this expression consists of exactly one index reference.
	 *
	 * @return {@code true} if this is a single index expression; default implementation returns {@code false}
	 */
	public default boolean isSingleIndex() { return false; }

	/**
	 * Checks if this expression is a masked single index (a guard around a single index reference).
	 *
	 * @return {@code true} if this is a masked expression whose first child is a single index
	 */
	public default boolean isSingleIndexMasked() { return isMasked() && self().getChildren().get(0).isSingleIndex(); }

	/**
	 * Checks if this expression can produce a concrete value given the specified index assignments.
	 *
	 * <p>This is used to determine if the expression can be evaluated with the given
	 * index values, which is important for sequence generation and optimization.</p>
	 *
	 * @param values the index value assignments to check against
	 * @return {@code true} if this expression can produce a value with these assignments;
	 *         default implementation returns {@code false}
	 */
	public default boolean isValue(IndexValues values) { return false; }

	/**
	 * Returns the compile-time boolean value of this expression, if known.
	 *
	 * <p>This method enables constant folding for boolean expressions. Subclasses
	 * representing constant boolean values should override this method.</p>
	 *
	 * @return an {@link Optional} containing the boolean value if this expression
	 *         represents a compile-time constant; empty otherwise
	 */
	public default Optional<Boolean> booleanValue() { return Optional.empty(); }

	/**
	 * Returns the compile-time integer value of this expression, if known.
	 *
	 * <p>This method enables constant folding for integer expressions. Subclasses
	 * representing constant integer values should override this method.</p>
	 *
	 * @return an {@link OptionalInt} containing the integer value if this expression
	 *         represents a compile-time constant; empty otherwise
	 */
	public default OptionalInt intValue() { return OptionalInt.empty(); }

	/**
	 * Returns the compile-time long value of this expression, if known.
	 *
	 * <p>By default, this returns the integer value promoted to long. Subclasses
	 * representing constant long values should override this method.</p>
	 *
	 * @return an {@link OptionalLong} containing the long value if this expression
	 *         represents a compile-time constant; empty otherwise
	 */
	public default OptionalLong longValue() {
		OptionalInt intValue = intValue();
		return intValue.isPresent() ? OptionalLong.of(intValue.getAsInt()) : OptionalLong.empty();
	}

	/**
	 * Returns the compile-time double value of this expression, if known.
	 *
	 * <p>By default, this returns the integer value promoted to double. Subclasses
	 * representing constant floating-point values should override this method.</p>
	 *
	 * @return an {@link OptionalDouble} containing the double value if this expression
	 *         represents a compile-time constant; empty otherwise
	 */
	public default OptionalDouble doubleValue() {
		OptionalInt intValue = intValue();
		return intValue.isPresent() ? OptionalDouble.of(intValue.getAsInt()) : OptionalDouble.empty();
	}

}
