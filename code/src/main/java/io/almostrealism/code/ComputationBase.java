/*
 * Copyright 2022 Michael Murray
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

import io.almostrealism.relation.ParallelProcess;
import io.almostrealism.relation.Process;
import io.almostrealism.scope.Argument;
import io.almostrealism.scope.Argument.Expectation;
import io.almostrealism.expression.Expression;
import io.almostrealism.relation.Compactable;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.Variable;

import java.util.Collection;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public abstract class ComputationBase<I, O, T> extends OperationAdapter<I> implements Computation<O>, ParallelProcess<Process<?, ?>, T>, Compactable {

	public ComputationBase() {
		super(new Supplier[0]);
	}

	@Override
	public int getCount() {
		long p = getInputs().stream().mapToInt(ParallelProcess::count).distinct().count();

		if (p == 1) {
			return getInputs().stream().mapToInt(ParallelProcess::count).distinct().sum();
		}

		throw new UnsupportedOperationException();
	}

	@Override
	public Scope compile() {
		System.out.println("WARN: Attempting to compile a Computation, " +
							"rather than an Evaluable container for one");
		return null;
	}

	@Override
	public boolean isCompiled() { return false; }

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

	@Override
	public void resetArguments() {
		super.resetArguments();
		ScopeLifecycle.resetArguments(getInputs().stream());
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

	@Override
	public Collection<Process<?, ?>> getChildren() {
		return getInputs().stream()
				.map(in -> in instanceof Process<?, ?> ? (Process<?, ?>) in : Process.of(in))
				.collect(Collectors.toList());
	}

	/** @return  null */
	@Override
	public Variable getOutputVariable() { return null; }

	@Override
	public Scope<O> getScope() {
		Scope<O> scope = new Scope<>(getFunctionName(), getMetadata());
		scope.getVariables().addAll(getVariables());
		return scope;
	}
}
