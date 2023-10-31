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
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Variable;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/**
 * {@link InstanceReference} is used to reference a previously declared
 * {@link Variable}. {@link CodePrintWriter} implementations should
 * encode the data as a {@link String}, but unlike a normal {@link String}
 * {@link Variable} the text does not appear in quotes.
 */
public class InstanceReference<T> extends Expression<T> {
	public static BiFunction<String, String, String> dereference = (name, pos) -> name + "[" + pos + "]";

	private Variable<T, ?> var;
	private Expression<?> index;
	private Expression<?> offset;

	public InstanceReference(Variable<T, ?> v) {
		this(v, null, null);
	}

	public InstanceReference(Variable<T, ?> referent, Expression<?> index, Expression offset) {
		super(referent.getType(), referent, index == null ? offset : index.add(offset));
		this.var = referent;
		this.index = index;
		this.offset = offset;
	}

	public Variable<T, ?> getReferent() { return var; }
	public Expression<?> getIndex() { return index; }
	public Expression<?> getOffset() { return offset; }

	@Override
	public String getExpression(LanguageOperations lang) {
		return var.getName();
	}

	@Override
	public String getWrappedExpression(LanguageOperations lang) { return getExpression(lang); }

	@Override
	public Variable assign(Expression exp) {
		return new Variable(getSimpleExpression(null), false, exp, getReferent().getDelegate());
	}

	@Override
	public Expression delta(Predicate<Expression> target) {
		if (target.test(this)) {
			return new IntegerConstant(1);
		}

		return new IntegerConstant(0);
	}

	public InstanceReference<T> generate(List<Expression<?>> children) {
		if (children.size() > 1) {
			throw new UnsupportedOperationException();
		}

		return this;
	}

	public static <T> InstanceReference<T> create(ArrayVariable<T> var, Expression<?> index, boolean dynamic) {
		Expression<?> pos = index.toInt();
		if (dynamic) {
			pos = pos.divide(var.length()).multiply(var.getDimValue()).add(pos.mod(var.length(), false));
		}

		String name = dereference.apply(var.getName(), pos.add(var.getOffsetValue()).toInt().getSimpleExpression(var.getLanguage()));

		return new InstanceReference<>(
				new Variable<>(name, false, new Constant<>(var.getType()), var),
				index, var.getOffsetValue());
	}
}
