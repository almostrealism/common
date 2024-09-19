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

import io.almostrealism.code.ExpressionAssignment;
import io.almostrealism.kernel.Index;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.scope.Variable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class StaticReference<T> extends Expression<T> {
	private String expression;
	private Variable referent;

	public StaticReference(Class<T> type, String expression) {
		super(type);
		this.expression = expression;
		init();
	}

	public StaticReference(Class<T> type, String expression, Variable referent) {
		super(type);
		this.expression = expression;
		this.referent = referent;
		init();
	}

	public String getName() { return expression; }

	@Override
	public Expression<T> withValue(String name, Number value) {
		if (!Objects.equals(name, expression)) return this;

		if (value instanceof Integer) {
			return (Expression) new IntegerConstant((Integer) value);
		} else if (value != null) {
			return (Expression) new DoubleConstant(value.doubleValue());
		} else {
			return null;
		}
	}

	@Override
	public Set<Index> getIndices() {
		if (this instanceof Index) return Set.of((Index) this);
		return Collections.emptySet();
	}

	public String getExpression(LanguageOperations lang) { return expression; }

	public String getWrappedExpression(LanguageOperations lang) { return getExpression(lang); }

	@Override
	public List<Variable<?, ?>> getDependencies() {
		if (referent == null) return super.getDependencies();

		ArrayList<Variable<?, ?>> dependencies = new ArrayList<>();
		dependencies.add(referent);
		dependencies.addAll(super.getDependencies());
		return dependencies;
	}

	@Override
	public ExpressionAssignment<T> assign(Expression exp) {
		return new ExpressionAssignment<>(this, exp);
	}

	@Override
	public Expression<T> recreate(List<Expression<?>> children) {
		if (children.size() > 0) throw new UnsupportedOperationException();
		return this;
	}

	@Override
	public boolean equals(Object expression) {
		if (expression instanceof StaticReference) {
			return ((StaticReference) expression).expression.equals(this.expression);
		}

		return false;
	}

	@Override
	public String toString() {
		return expression;
	}
}
