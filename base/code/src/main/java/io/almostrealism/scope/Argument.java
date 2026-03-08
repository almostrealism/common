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

package io.almostrealism.scope;

import io.almostrealism.relation.Delegated;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Sortable;
import io.almostrealism.uml.Named;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Represents an argument to a {@link Scope}, wrapping a {@link Variable} with metadata
 * about how the argument is expected to be used during scope execution.
 *
 * <p>{@link Argument} is used during scope compilation to track which variables are
 * inputs to a computation and what operations will be performed on them. The
 * {@link Expectation} indicates whether the argument will be evaluated, altered,
 * or simply passed through.</p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * Variable<Double, ?> inputVar = new ArrayVariable<>(...);
 * Argument<Double> arg = new Argument<>(inputVar, Expectation.EVALUATE_AHEAD);
 * scope.getArguments().add(arg);
 * }</pre>
 *
 * @param <T> the type of value held by the underlying variable
 * @see Variable
 * @see Scope
 * @see Expectation
 */
public class Argument<T> implements Named, Sortable, Delegated<Argument<T>> {
	/**
	 * Indicates how an argument is expected to be used during scope execution.
	 */
	public enum Expectation {
		/** The argument will be evaluated before scope execution. */
		EVALUATE_AHEAD,
		/** The argument will be evaluated during scope execution. */
		WILL_EVALUATE,
		/** The argument's value will be modified during scope execution. */
		WILL_ALTER,
		/** The argument will not be modified during scope execution. */
		NOT_ALTERED
	}

	/** The underlying variable being passed as an argument. */
	private final Variable<T, ?> variable;

	/** The expectation for how this argument will be used. */
	private final Expectation expect;

	/**
	 * Creates a new argument wrapping the specified variable with the given expectation.
	 *
	 * @param variable    the variable to wrap as an argument
	 * @param expectation how the argument is expected to be used
	 * @throws IllegalArgumentException if variable or expectation is null
	 */
	public Argument(Variable<T, ?> variable, Expectation expectation) {
		if (variable == null || expectation == null) throw new IllegalArgumentException();

		this.variable = variable;
		this.expect = expectation;
	}

	/**
	 * Returns the name of the underlying variable.
	 *
	 * @return the variable name
	 */
	@Override
	public String getName() { return getVariable().getName(); }

	/**
	 * Returns the sort hint from the underlying variable for ordering arguments.
	 *
	 * @return the sort hint value
	 */
	@Override
	public int getSortHint() { return getVariable().getSortHint(); }

	/**
	 * Returns the underlying variable wrapped by this argument.
	 *
	 * @return the variable
	 */
	public Variable<T, ?> getVariable() { return variable; }

	/**
	 * Returns the expectation for how this argument will be used during scope execution.
	 *
	 * @return the usage expectation
	 */
	public Expectation getExpectation() { return expect; }

	/**
	 * Returns the producer supplier from the underlying variable, if any.
	 *
	 * @return the producer supplier, or null if the variable has no producer
	 */
	public Supplier<Evaluable<? extends T>> getProducer() { return getVariable().getProducer(); }

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof Argument)) return false;
		Argument<?> argument = (Argument<?>) o;
		return Objects.equals(variable, argument.variable) && expect == argument.expect;
	}

	@Override
	public int hashCode() {
		return Objects.hash(variable);
	}

	/**
	 * Returns a delegate argument based on the delegate of the underlying variable.
	 *
	 * <p>If the underlying variable has a delegate, returns a new Argument wrapping
	 * that delegate variable with {@link Expectation#EVALUATE_AHEAD}. Returns null
	 * if there is no delegate.</p>
	 *
	 * @return the delegate argument, or null if no delegate exists
	 */
	@Override
	public Argument<T> getDelegate() {
		if (getVariable() == null) return null;
		if (((Delegated) getVariable()).getDelegate() == null) return null;
		return new Argument((Variable) ((Delegated) getVariable()).getDelegate(), Expectation.EVALUATE_AHEAD);
	}
}
