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

import io.almostrealism.lang.CodePrintWriter;
import io.almostrealism.code.ExpressionAssignment;
import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.ConstantCollectionExpression;
import io.almostrealism.collect.ExpressionMatchingCollectionExpression;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Variable;
import org.almostrealism.io.ConsoleFeatures;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * {@link InstanceReference} is used to reference a previously declared
 * {@link Variable}. {@link CodePrintWriter} implementations should
 * encode the data as a {@link String}, but unlike a normal {@link String}
 * {@link Variable} the text does not appear in quotes.
 */
public class InstanceReference<T, V> extends Expression<V> implements ExpressionFeatures, ConsoleFeatures {
	public static boolean enableMask = false;

	public static BiFunction<String, String, String> dereference = (name, pos) -> name + "[" + pos + "]";

	private Variable<T, ?> var;
	private Expression<?> pos;
	private Expression<?> index;

	public InstanceReference(Variable<T, ?> referent) {
		super((Class) referent.getType());
		this.var = referent;
		init();
	}

	public InstanceReference(Variable<T, ?> referent, Expression<?> pos, Expression<?> index) {
		super((Class) referent.getType(), false, pos);
		this.var = referent;
		this.pos = pos;
		this.index = index;
		init();
	}

	@Override
	protected void init() {
		if (getReferent() == null ||
				(getReferent().getDelegate() == null && getReferent().getName() == null)) {
			throw new UnsupportedOperationException();
		}

		super.init();
	}

	public Variable<T, ?> getReferent() { return var; }
	public Expression<?> getIndex() { return index; }

	@Override
	public String getExpression(LanguageOperations lang) {
		if (var instanceof ArrayVariable) {
			ArrayVariable v = (ArrayVariable) var;

			if (v.getDelegate() == null) {
				if (pos == null) {
					// Reference to the whole array
					return var.getName();
				} else if (v.isDisableOffset() || !lang.isVariableOffsetSupported()) {
					// Reference to a specific element
					return dereference.apply(var.getName(), pos.toInt().getExpression(lang));
				} else {
					// Reference to a specific element, with offset
					return dereference.apply(var.getName(), pos.add(v.getOffsetValue()).toInt().getExpression(lang));
				}
			 } else {
				throw new UnsupportedOperationException();
			}
		} else {
			// warn("Reference to value which is not an ArrayVariable");
			return var.getName();
		}
	}

	@Override
	public String getWrappedExpression(LanguageOperations lang) { return getExpression(lang); }

	@Override
	public List<Variable<?, ?>> getDependencies() {
		if (var == null) return super.getDependencies();

		ArrayList<Variable<?, ?>> dependencies = new ArrayList<>();
		dependencies.add(var);
		dependencies.addAll(super.getDependencies());
		return dependencies;
	}

	@Override
	public boolean containsReference(Variable var) {
		return Objects.equals(getReferent().getName(), var.getName());
	}

	@Override
	public ExpressionAssignment<V> assign(Expression exp) {
		return new ExpressionAssignment<>(this, exp);
	}

	@Override
	public CollectionExpression delta(CollectionExpression target) {
		return ExpressionMatchingCollectionExpression.create(
				new ConstantCollectionExpression(target.getShape(), this),
				target, e(1), e(0));
	}

	public InstanceReference<T, V> recreate(List<Expression<?>> children) {
		if (children.size() == 0) {
			return new InstanceReference<>(var);
		} else if (children.size() == 1) {
			return new InstanceReference<>(var, children.get(0), index);
		} else {
			throw new UnsupportedOperationException();
		}
	}

	@Override
	public boolean compare(Expression e) {
		if (this == e) return true;
		if (!(e instanceof InstanceReference)) return false;

		InstanceReference<?, ?> alt = (InstanceReference<?, ?>) e;
		return Objects.equals(var, alt.var) && Objects.equals(pos, alt.pos) && Objects.equals(index, alt.index);
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
