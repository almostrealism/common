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

package io.almostrealism.code;

import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.scope.ScopeSettings;
import io.almostrealism.scope.Variable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public class ExpressionAssignment<T> implements Statement<ExpressionAssignment<T>> {
	private final boolean declaration;
	private final Expression<T> destination;
	private final Expression<T> expression;


	public ExpressionAssignment(Expression<T> destination, Expression<T> expression) {
		this(false, destination, expression);
	}

	public ExpressionAssignment(boolean declaration, Expression<T> destination, Expression<T> expression) {
		if (expression == null) {
			throw new IllegalArgumentException();
		}

		this.declaration = declaration;
		this.destination = destination;
		this.expression = expression;
	}

	public boolean isDeclaration() { return declaration; }

	public Expression<T> getDestination() { return destination; }

	public Expression<T> getExpression() { return expression; }

	public PhysicalScope getPhysicalScope() {
		if (getDestination() == null) return null;

		return getDestination().getDependencies()
				.stream().map(Variable::getPhysicalScope).filter(Objects::nonNull)
				.findFirst().orElse(null);
	}

	public Expression<Integer> getArraySize() {
		if (getDestination() == null) return null;

		return getDestination().getDependencies()
				.stream().map(Variable::getArraySize).filter(Objects::nonNull)
				.findFirst().orElse(null);
	}

	public Supplier getProducer() {
		throw new UnsupportedOperationException();
	}

	public List<Variable<?, ?>> getDependencies() {
		List<Variable<?, ?>> deps = new ArrayList<>();
		if (destination != null) deps.addAll(destination.getDependencies());
		if (expression != null) deps.addAll(expression.getDependencies());
		return deps;
	}

	@Override
	public String getStatement(LanguageOperations lang) {
		if (declaration) {
			return lang.declaration(destination.getType(), destination.getExpression(lang), expression.getExpression(lang));
		} else {
			return lang.assignment(destination.getExpression(lang), expression.getExpression(lang));
		}
	}

	@Override
	public ExpressionAssignment<T> simplify(KernelStructureContext context, int depth) {
		return new ExpressionAssignment<>(declaration,
				ScopeSettings.reviewSimplification(destination, destination.simplify(context, depth + 1)),
				ScopeSettings.reviewSimplification(expression, expression.simplify(context, depth + 1)));
	}
}
