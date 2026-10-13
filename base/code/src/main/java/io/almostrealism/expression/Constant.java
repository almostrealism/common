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

import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.ConstantCollectionExpression;
import io.almostrealism.sequence.ArithmeticIndexSequence;
import io.almostrealism.sequence.Index;
import io.almostrealism.sequence.IndexRange;
import io.almostrealism.sequence.IndexValues;
import io.almostrealism.lang.LanguageOperations;

import java.util.Collections;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Abstract base class for constant (literal) expression nodes.
 *
 * <p>A constant expression holds a fixed value that does not depend on any index variable.
 * It always answers {@code true} from {@link #isValue(io.almostrealism.sequence.IndexValues)},
 * has an empty index set, and has a zero delta (since it does not vary).</p>
 *
 * <p>Each concrete constant holds its value in its own representation, so each renders that
 * value ({@link #getExpression(LanguageOperations)}) and decides whether another expression
 * is the same constant ({@link #compare(Expression)}) for itself.</p>
 *
 * @param <T> the type of the constant value
 */
public abstract class Constant<T> extends Expression<T> {
	/**
	 * When {@code true}, {@link #minus()} on an {@link IntegerConstant}, {@link LongConstant}
	 * or {@link DoubleConstant} directly returns a constant of the same type holding the
	 * negated value, rather than going through {@link Minus#of(Expression)}.
	 */
	public static boolean enableNegationOptimization = true;

	/**
	 * Constructs a constant expression of the given type.
	 *
	 * @param type the Java type of the constant value
	 */
	public Constant(Class<T> type) {
		super(type);
	}

	@Override
	public boolean isValue(IndexValues values) { return true; }

	/**
	 * {@inheritDoc}
	 *
	 * <p>A constant takes its single value at every position of the range.</p>
	 */
	@Override
	protected double[] computeValues(IndexRange range) {
		return range.constant(value(new IndexValues()).doubleValue());
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>An integer constant is the progression with zero scale and itself as offset.</p>
	 */
	@Override
	public ArithmeticIndexSequence arithmeticSequence(Index index, long len) {
		if (isFP()) return null;

		OptionalLong v = longValue();
		return v.isPresent() ? new ArithmeticIndexSequence(v.getAsLong(), 0, 1, len, len) : null;
	}

	@Override
	public Set<Index> getIndices() {
		return Collections.emptySet();
	}

	@Override
	public boolean containsIndex(Index idx) { return false; }

	/**
	 * {@inheritDoc}
	 *
	 * <p>A constant does not vary, so a series could only reproduce it and it is never a
	 * target.</p>
	 */
	@Override
	public boolean isSeriesSimplificationTarget(int depth) { return false; }

	@Override
	public String getWrappedExpression(LanguageOperations lang) { return getExpression(lang); }

	/**
	 * Returns this constant, since constants have no children and cannot be recreated
	 * from a different child list.
	 *
	 * @param children must be empty; any non-empty list throws {@link UnsupportedOperationException}
	 * @return this constant expression
	 */
	@Override
	public Constant<T> recreate(List<Expression<?>> children) {
		if (children.size() > 0) {
			throw new UnsupportedOperationException();
		}

		return this;
	}

	@Override
	public CollectionExpression delta(CollectionExpression target) {
		return new ConstantCollectionExpression(target.getShape(), new IntegerConstant(0));
	}

	/**
	 * Compares this constant with another expression by value.
	 *
	 * <p>Each concrete constant holds its value in its own representation, so each decides
	 * for itself whether {@code e} is the same kind of constant holding the same value.</p>
	 *
	 * @param e the expression to compare against
	 * @return {@code true} if {@code e} is the same kind of constant holding the same value
	 */
	@Override
	public abstract boolean compare(Expression e);

	@Override
	public int hashCode() {
		return String.valueOf(getValue()).hashCode();
	}

	/**
	 * Creates the appropriate constant expression subtype for the given value.
	 *
	 * <p>Dispatches to {@link IntegerConstant}, {@link LongConstant}, {@link DoubleConstant},
	 * {@link BooleanConstant}, or {@link ConstantValue} depending on the runtime type
	 * of {@code value}.</p>
	 *
	 * @param value the literal value
	 * @param <T>   the type of the constant
	 * @return a typed constant expression
	 */
	public static <T> Constant<T> of(T value) {
		if (value instanceof Integer) {
			return (Constant<T>) new IntegerConstant((Integer) value);
		} else if (value instanceof Long) {
			return (Constant<T>) new LongConstant((Long) value);
		} else if (value instanceof Double) {
			return (Constant<T>) new DoubleConstant((Double) value);
		} else if (value instanceof Boolean) {
			return (Constant<T>) new BooleanConstant((Boolean) value);
		} else {
			return new ConstantValue(value.getClass(), value);
		}
	}
}
