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

import io.almostrealism.compute.PhysicalScope;
import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.ScopeSettings;
import io.almostrealism.scope.Variable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * A statement that assigns an expression value to a destination expression.
 *
 * <p>An {@code ExpressionAssignment} represents either a simple assignment ({@code dest = expr})
 * or a declaration-plus-assignment ({@code type dest = expr}) in the generated code. It implements
 * {@link Statement} so it can be included directly in a {@link io.almostrealism.scope.Scope}.</p>
 *
 * <p>The destination must be a reference expression (such as an {@link io.almostrealism.expression.InstanceReference}
 * or {@link io.almostrealism.expression.StaticReference}), and the expression must be non-null.</p>
 *
 * @param <T> the type of the assigned value
 *
 * @see Statement
 * @see io.almostrealism.scope.Scope
 */
public class ExpressionAssignment<T> implements Statement<ExpressionAssignment<T>> {
	/** Whether this assignment also declares the destination variable. */
	private final boolean declaration;
	/** The destination expression that receives the assigned value. */
	private final Expression<T> destination;
	/** The source expression whose value is assigned to the destination. */
	private final Expression<T> expression;

	/**
	 * Creates a simple assignment (without variable declaration).
	 *
	 * @param destination the destination expression
	 * @param expression the source expression (must not be {@code null})
	 * @throws IllegalArgumentException if {@code expression} is {@code null}
	 */
	public ExpressionAssignment(Expression<T> destination, Expression<T> expression) {
		this(false, destination, expression);
	}

	/**
	 * Creates an assignment, optionally also declaring the destination variable.
	 *
	 * @param declaration {@code true} to emit a declaration; {@code false} for a plain assignment
	 * @param destination the destination expression
	 * @param expression the source expression (must not be {@code null})
	 * @throws IllegalArgumentException if {@code expression} is {@code null}
	 */
	public ExpressionAssignment(boolean declaration, Expression<T> destination, Expression<T> expression) {
		if (expression == null) {
			throw new IllegalArgumentException();
		}

		this.declaration = declaration;
		this.destination = destination;
		this.expression = expression;
	}

	/**
	 * Returns whether this assignment also declares the destination variable.
	 *
	 * @return {@code true} if this is a declaration-assignment, {@code false} if plain assignment
	 */
	public boolean isDeclaration() { return declaration; }

	/**
	 * Returns the destination expression that receives the assigned value.
	 *
	 * @return the destination expression
	 */
	public Expression<T> getDestination() { return destination; }

	/**
	 * Returns the source expression whose value is assigned to the destination.
	 *
	 * @return the source expression
	 */
	public Expression<T> getExpression() { return expression; }

	/**
	 * Returns the physical scope of the destination variable, if any.
	 *
	 * @return the physical scope, or {@code null} if the destination has no physical scope
	 */
	public PhysicalScope getPhysicalScope() {
		if (getDestination() == null) return null;

		return getDestination().getDependencies()
				.stream().map(Variable::getPhysicalScope).filter(Objects::nonNull)
				.findFirst().orElse(null);
	}

	/**
	 * Returns the array size expression for the destination array variable, if present.
	 *
	 * @return the array size expression, or {@code null} if the destination is not an array variable
	 */
	public Expression<Integer> getArraySize() {
		if (getDestination() == null) return null;

		return getDestination().getDependencies()
				.stream().map(v -> v instanceof ArrayVariable ? (ArrayVariable) v : null)
				.filter(Objects::nonNull)
				.map(ArrayVariable::getArraySize)
				.filter(Objects::nonNull)
				.findFirst().orElse(null);
	}

	/**
	 * This method is not supported for expression assignments.
	 *
	 * @return never returns normally
	 * @throws UnsupportedOperationException always
	 */
	public Supplier getProducer() {
		throw new UnsupportedOperationException();
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Returns the union of the destination's and expression's dependencies.
	 *
	 * @return a mutable list of all variable dependencies
	 */
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
