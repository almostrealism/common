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
import io.almostrealism.sequence.Index;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.scope.Variable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * An expression that renders as a pre-formed code string or variable reference.
 *
 * <p>Holds a raw expression string that is emitted verbatim by the code generator.
 * Optionally tracks a {@link Variable} referent so that variable dependencies are
 * correctly reported during scope building.</p>
 *
 * @param <T> the type of the referenced value
 */
public class StaticReference<T> extends Expression<T> {
	/** The raw code string emitted for this reference. */
	private String expression;

	/**
	 * The variable this reference points to, or {@code null} if it is a free-form
	 * code string rather than a scoped variable reference.
	 */
	private Variable referent;

	/**
	 * Constructs a static reference that renders as the given expression string.
	 *
	 * @param type       the type of the referenced value
	 * @param expression the raw code string to emit
	 */
	public StaticReference(Class<T> type, String expression) {
		super(type);
		this.expression = expression;
		init();
	}

	/**
	 * Constructs a static reference that renders as the given expression string and
	 * tracks the given variable as a dependency.
	 *
	 * @param type       the type of the referenced value
	 * @param expression the raw code string to emit
	 * @param referent   the variable this reference depends on
	 */
	public StaticReference(Class<T> type, String expression, Variable referent) {
		super(type);
		this.expression = expression;
		this.referent = referent;
		init();
	}

	public String getName() { return expression; }

	protected Variable getReferent() { return referent; }

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

	@Override
	public String getExpression(LanguageOperations lang) { return expression; }

	@Override
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
	public boolean compare(Expression expression) {
		if (expression instanceof StaticReference) {
			if (((StaticReference) expression).expression == null || this.expression == null) {
				return Objects.equals(expression.getExpression(lang), getExpression(lang));
			}

			return Objects.equals(((StaticReference) expression).expression, this.expression);
		}

		return false;
	}

	@Override
	public String toString() {
		return expression;
	}
}
