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

import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.relation.Countable;
import io.almostrealism.relation.Process;
import io.almostrealism.relation.ProcessContext;
import io.almostrealism.scope.Argument;
import io.almostrealism.scope.Argument.Expectation;
import io.almostrealism.expression.Expression;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.Variable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public abstract class ComputationBase<I, O, T> extends OperationAdapter<I, Process<?, ?>>
					implements Computation<O>, ComputableParallelProcess<Process<?, ?>, T> {
	private LanguageOperations lang;
	private List<ComputeRequirement> requirements;

	public ComputationBase() {
		super(new Supplier[0]);
	}

	@Override
	protected OperationMetadata prepareMetadata(OperationMetadata metadata) {
		return OperationInfo.metadataForProcess(this, metadata);
	}

	@Override
	public List<ComputeRequirement> getComputeRequirements() {
		return requirements;
	}

	public void setComputeRequirements(List<ComputeRequirement> requirements) {
		this.requirements = requirements;
	}

	@Override
	public long getCountLong() {
		long p = getInputs().stream().mapToLong(Countable::countLong).distinct().count();

		if (p == 1) {
			return getInputs().stream().mapToLong(Countable::countLong).distinct().sum();
		}

		throw new UnsupportedOperationException();
	}

	@Override
	public boolean isFixedCount() {
		return getInputs().stream().noneMatch(v -> v instanceof Countable && !((Countable) v).isFixedCount());
	}

	@Override
	public Scope compile() {
		System.out.println("WARN: Attempting to compile a Computation, " +
							"rather than an Evaluable container for one");
		return null;
	}

	@Override
	public boolean isCompiled() { return false; }

	protected LanguageOperations getLanguage() { return lang; }

	@Override
	public void prepareArguments(ArgumentMap map) {
		if (getArgumentVariables() != null) return;
		ScopeLifecycle.prepareArguments(getInputs().stream(), map);
		getInputs().forEach(map::add);
	}

	@Override
	public void prepareScope(ScopeInputManager manager, KernelStructureContext context) {
		if (getArgumentVariables() != null) return;
		this.lang = manager.getLanguage();
		ScopeLifecycle.prepareScope(getInputs().stream(), manager, context);
		assignArguments(manager);
	}

	@Override
	public void resetArguments() {
		super.resetArguments();
		ScopeLifecycle.resetArguments(getInputs().stream());
		this.lang = null;
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
	public Scope<O> getScope(KernelStructureContext context) {
		Scope<O> scope = new Scope<>(getFunctionName(), getMetadata());
		if (getComputeRequirements() != null) {
			scope.setComputeRequirements(getComputeRequirements());
		}

		scope.getVariables().addAll(getVariables());
		return scope;
	}

	/**
	 * Extends {@link ComputableParallelProcess#optimize(ProcessContext)} to ensure that
	 * the {@link ComputeRequirement}s are preserved.
	 *
	 * @see  ComputationBase#getComputeRequirements()
	 */
	@Override
	public ComputationBase<I, O, T> optimize(ProcessContext ctx) {
		ComputationBase<I, O, T> replacement = (ComputationBase<I, O, T>)
				ComputableParallelProcess.super.optimize(ctx);
		replacement.setComputeRequirements(getComputeRequirements());
		return replacement;
	}

	/**
	 * Extends to {@link ComputableParallelProcess#generateReplacement(List)} to ensure
	 * that the {@link ComputeRequirement}s are preserved.
	 *
	 * @see  ComputationBase#getComputeRequirements()
	 */
	public ComputationBase<I, O, T> generateReplacement(List<Process<?, ?>> inputs) {
		ComputationBase<I, O, T> replacement = (ComputationBase<I, O, T>)
				ComputableParallelProcess.super.generateReplacement(inputs);
		replacement.setComputeRequirements(getComputeRequirements());
		return replacement;
	}

	@Override
	public String describe() {
		return getMetadata().getShortDescription() + " " +
				getCountLong() + "x" +
				(isFixedCount() ? " (fixed)" : " (variable)");
	}
}
