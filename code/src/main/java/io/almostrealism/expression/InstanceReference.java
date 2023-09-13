/*
 * Copyright 2021 Michael Murray
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
import io.almostrealism.scope.Variable;

import java.util.List;

/**
 * {@link InstanceReference} is used to reference a previously declared
 * {@link Variable}. {@link CodePrintWriter} implementations should
 * encode the data as a {@link String}, but unlike a normal {@link String}
 * {@link Variable} the text does not appear in quotes.
 */
public class InstanceReference<T> extends Expression<T> {
	private Variable<T, ?> var;

	public InstanceReference(Variable<T, ?> v) {
		this(v, null);
	}

	public InstanceReference(Variable<T, ?> referent, Expression<?> argument) {
		super(referent.getType(), referent, argument);
		this.var = referent;
	}

	public Variable<T, ?> getReferent() { return var; }

	@Override
	public String getExpression() {
		return var.getName();
	}

	@Override
	public Variable assign(Expression exp) {
		return new Variable(getSimpleExpression(), false, exp, getReferent().getDelegate());
	}

	public InstanceReference<T> generate(List<Expression<?>> children) {
		if (children.size() > 1) {
			throw new UnsupportedOperationException();
		}

		return this;
	}
}
