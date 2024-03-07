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

import io.almostrealism.code.CodePrintWriter;
import io.almostrealism.code.ExpressionAssignment;
import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.DefaultCollectionExpression;
import io.almostrealism.collect.ExpressionMatchingCollectionExpression;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Variable;
import org.almostrealism.io.ConsoleFeatures;

import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * {@link InstanceReference} is used to reference a previously declared
 * {@link Variable}. {@link CodePrintWriter} implementations should
 * encode the data as a {@link String}, but unlike a normal {@link String}
 * {@link Variable} the text does not appear in quotes.
 */
public class InstanceReference<T> extends Expression<T> implements ExpressionFeatures, ConsoleFeatures {
	public static boolean enableMask = false;

	public static BiFunction<String, String, String> dereference = (name, pos) -> name + "[" + pos + "]";

	private Variable<T, ?> var;
	private Expression<?> pos;
	private Expression<?> index;

	public InstanceReference(Variable<T, ?> v) {
		this(v, null, null);
	}

	public InstanceReference(Variable<T, ?> referent, Expression<?> pos, Expression<?> index) {
		super(referent.getType(), referent, pos);
		this.var = referent;
		this.pos = pos;
		this.index = index;
	}

	public Variable<T, ?> getReferent() { return var; }
	public Expression<?> getIndex() { return index; }

	@Override
	public String getExpression(LanguageOperations lang) {
		if (var instanceof ArrayVariable) {
			ArrayVariable v = (ArrayVariable) var;

			if (pos == null) {
				// Reference to the whole array
				return var.getName();
			} else if (v.isDisableOffset()) {
				// Reference to a specific element
				return dereference.apply(var.getName(), pos.toInt().getExpression(lang));
			} else {
				// Reference to a specific element, with offset
				return dereference.apply(var.getName(), pos.add(v.getOffsetValue()).toInt().getExpression(lang));
			}
		} else {
			// warn("Reference to value which is not an ArrayVariable");
			return var.getName();
		}
	}

	@Override
	public String getWrappedExpression(LanguageOperations lang) { return getExpression(lang); }

	@Override
	public ExpressionAssignment<T> assign(Expression exp) {
		return new ExpressionAssignment<>(this, exp);
	}

	@Override
	public CollectionExpression delta(CollectionExpression target) {
		if (getReferent() instanceof CollectionExpression) {
			return ExpressionMatchingCollectionExpression.create(
					target, (CollectionExpression) getReferent(),
					getIndex(), e(1), e(0));
		} else {
			return DefaultCollectionExpression.create(target.getShape(), idx -> e(0));
		}
	}

	public InstanceReference<T> generate(List<Expression<?>> children) {
		if (children.size() == 0) {
			return new InstanceReference<>(var);
		} else if (children.size() == 1) {
			return new InstanceReference<>(var, children.get(0), index);
		} else {
			throw new UnsupportedOperationException();
		}
	}

	public static boolean compareExpressions(Expression<?> a, Expression<?> b) {
		if (a == null && b == null) return true;
		if (a == null || b == null) return false;
		if (a.getClass() != b.getClass()) return false;

		if (a instanceof InstanceReference) {
			InstanceReference ra = (InstanceReference) a;
			InstanceReference rb = (InstanceReference) b;
			return Objects.equals(ra.getReferent().getName(), rb.getReferent().getName()) &&
					compareExpressions(ra.getIndex(), rb.getIndex());
		} else if (a.getChildren().size() == b.getChildren().size()) {
			if (a.getChildren().isEmpty()) return Objects.equals(a, b);

			for (int i = 0; i < a.getChildren().size(); i++) {
				if (!compareExpressions(a.getChildren().get(i), b.getChildren().get(i))) {
					return false;
				}
			}

			return true;
		} else {
			return false;
		}
	}

	public static <T> Expression<T> create(ArrayVariable<T> var, Expression<?> index, boolean dynamic) {
		Expression<Boolean> condition = index.greaterThanOrEqual(new IntegerConstant(0));

		Expression<?> pos = index.toInt();
		if (dynamic) {
			index = pos.imod(var.length());
			pos = pos.divide(var.length()).multiply(var.getDimValue()).add(index);
		}

		if (enableMask) {
			return Mask.of(condition, new InstanceReference<>(var, pos, index));
		} else {
			return new InstanceReference<>(var, pos, index);
		}
	}
}
