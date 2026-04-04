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
import io.almostrealism.sequence.Index;
import io.almostrealism.sequence.IndexValues;
import io.almostrealism.lang.LanguageOperations;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Abstract base class for constant (literal) expression nodes.
 *
 * <p>A constant expression holds a fixed value that does not depend on any index variable.
 * It always answers {@code true} from {@link #isValue(io.almostrealism.sequence.IndexValues)},
 * has an empty index set, and has a zero delta (since it does not vary).</p>
 *
 * @param <T> the type of the constant value
 */
public abstract class Constant<T> extends Expression<T> {
	/**
	 * When {@code true}, {@link #minus()} on a {@link DoubleConstant} returns a new constant
	 * with the negated value instead of wrapping it in a {@link Negation} node.
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

	@Override
	public Set<Index> getIndices() {
		return Collections.emptySet();
	}

	@Override
	public boolean containsIndex(Index idx) { return false; }

	@Override
	public String getExpression(LanguageOperations lang) { return null; }

	public String getWrappedExpression(LanguageOperations lang) { return getExpression(lang); }

	/**
	 * Returns this constant, since constants have no children and cannot be recreated
	 * from a different child list.
	 *
	 * @param children must be empty; any non-empty list throws {@link UnsupportedOperationException}
	 * @return this constant expression
	 */
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

	@Override
	public boolean compare(Expression e) {
		if (!(e instanceof Constant)) {
			return false;
		}

		if (getValue() == null || ((Constant<?>) e).getValue() == null) {
			throw new UnsupportedOperationException(
					"It is not possible to compare Constant implementation(s)" +
					" which do not use the value field");
		}

		return Objects.equals(((Constant<?>) e).getType(), getType()) &&
				Objects.equals(((Constant<?>) e).getValue(), getValue());
	}

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

	/**
	 * Creates a typed {@link ConstantValue} with a {@code null} value, useful as a typed
	 * placeholder during scope or argument construction.
	 *
	 * @param type the Java type of the placeholder constant
	 * @param <T>  the type of the constant
	 * @return a constant expression with a {@code null} value
	 */
	public static <T> Constant<T> forType(Class<T> type) {
		return new ConstantValue<>(type, null);
	}
}
