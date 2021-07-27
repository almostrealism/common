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

package io.almostrealism.code;

import io.almostrealism.code.Argument.Expectation;
import io.almostrealism.code.expressions.Expression;
import io.almostrealism.code.expressions.MultiExpression;
import io.almostrealism.relation.Compactable;
import io.almostrealism.relation.Delegated;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Provider;

import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public abstract class OperationComputationAdapter<I, O> extends OperationAdapter<I> implements Computation<O>, Compactable {

	public OperationComputationAdapter() {
		super(new Supplier[0]);
	}

	@Override
	public Scope compile() {
		System.out.println("WARN: Attempting to compile a Computation, " +
							"rather than an Evaluable container for one");
		return null;
	}

	@Override
	public void prepareArguments(ArgumentMap map) {
		if (getArgumentVariables() != null) return;
		ScopeLifecycle.prepareArguments(getInputs().stream(), map);
		getInputs().forEach(map::add);
	}

	@Override
	public void prepareScope(ScopeInputManager manager) {
		if (getArgumentVariables() != null) return;
		ScopeLifecycle.prepareScope(getInputs().stream(), manager);
		assignArguments(manager);
	}

	/**
	 * Generate {@link ArrayVariable}s for the values available via
	 * {@link #getInputs()} and store them so they can be retrieved
	 * via {@link #getArguments()}.
	 */
	protected void assignArguments(ArgumentProvider provider) {
		setArguments(getInputs().stream()
				.map(provider.argumentForInput(this))
				.map(var ->
						Optional.ofNullable(var).map(v ->
								new Argument<>(v, Expectation.EVALUATE_AHEAD))
								.orElse(null))
				.map(arg -> (Argument<? extends I>) arg)
				.collect(Collectors.toList()));
	}

	@Override
	public ArrayVariable getArgument(int index, Expression<Integer> size) {
		if (index >= getInputs().size()) {
			throw new IllegalArgumentException("Invalid input (" + index + ")");
		}

		ArrayVariable v = getArgumentForInput(getInputs().get(index));
		if (v == null) {
			throw new IllegalArgumentException("Input " + index +
					" does not appear to have a corresponding argument");
		}

		return v;
	}

	/** @return  null */
	@Override
	public Variable getOutputVariable() { return null; }

	public Expression<Double> getInputValue(int index, int pos) {
		if (getArgumentVariables() == null) {
			throw new IllegalArgumentException("Input value cannot be obtained before arguments are determined");
		}

		return getExpression(getArgumentForInput(getInputs().get(index)), pos);
	}

	@Override
	public Scope<O> getScope() {
		Scope<O> scope = new Scope<>(getFunctionName());
		scope.getVariables().addAll(getVariables());
		return scope;
	}

	public static Expression<Double> getExpression(ArrayVariable arg, int pos) {
		return getExpression(arg.getProducer()).getValue(pos);
	}

	public static <T> MultiExpression getExpression(Supplier<Evaluable<? extends T>> producer) {
		if (producer instanceof MultiExpression) {
			return (MultiExpression) producer;
		}

		if (producer instanceof Delegated && ((Delegated) producer).getDelegate() instanceof MultiExpression) {
			return (MultiExpression) ((Delegated) producer).getDelegate();
		}

		Evaluable<? extends T> evaluable = producer.get();
		if (evaluable instanceof Provider && ((Provider) evaluable).get() instanceof MultiExpression) {
			return (MultiExpression) ((Provider) evaluable).get();
		}

		throw new UnsupportedOperationException();
	}
}
