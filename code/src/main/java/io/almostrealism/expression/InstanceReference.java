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

import io.almostrealism.code.CodePrintWriter;
import io.almostrealism.code.ExpressionAssignment;
import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.kernel.KernelSeriesProvider;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Variable;
import org.almostrealism.io.ConsoleFeatures;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * {@link InstanceReference} is used to reference a previously declared
 * {@link Variable}. {@link CodePrintWriter} implementations should
 * encode the data as a {@link String}, but unlike a normal {@link String}
 * {@link Variable} the text does not appear in quotes.
 */
public class InstanceReference<T> extends Expression<T> implements ExpressionFeatures, ConsoleFeatures {
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
	public CollectionExpression delta(TraversalPolicy shape, Function<Expression, Predicate<Expression>> target) {
		return CollectionExpression.create(shape, idx -> {
			if (!target.apply(idx).test(this)) {
				return e(0);
			}

			return conditional(idx.eq(getIndex()), e(1), e(0));
		});
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

	public static <T> InstanceReference<T> create(ArrayVariable<T> var, Expression<?> index, boolean dynamic) {
		Expression<?> pos = index.toInt();
		if (dynamic) {
			index = pos.imod(var.length());
			pos = pos.divide(var.length()).multiply(var.getDimValue()).add(index);
		}

		return new InstanceReference<>(var, pos, index);
	}
}
