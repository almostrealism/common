/*
 * Copyright 2025 Michael Murray
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

import io.almostrealism.compute.Process;
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.profile.OperationInfo;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.uml.Named;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.Argument;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.util.DescribableParent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public abstract class ComputableBase<I, T> implements
		ComputableParallelProcess<Process<?, ?>, T>,
		NameProvider, DescribableParent<Process<?, ?>>,
		Destroyable, OperationInfo, Named {

	private String function;

	private List<Supplier<Evaluable<? extends I>>> inputs;
	private List<Argument<? extends I>> arguments;

	private List<ExpressionAssignment<?>> variables;
	private OperationMetadata metadata;

	@SafeVarargs
	public ComputableBase(Supplier<Evaluable<? extends I>>... input) {
		setInputs(input);
	}

	public void setFunctionName(String name) { function = name; }

	@Override
	public String getFunctionName() { return function; }

	protected void setMetadata(OperationMetadata metadata) { this.metadata = metadata; }

	@Override
	public OperationMetadata getMetadata() { return metadata; }

	@Override
	public String getName() { return OperationAdapter.operationName(null, getClass(), getFunctionName()); }

	@SafeVarargs
	protected final void setInputs(Supplier<Evaluable<? extends I>>... input) { setInputs(Arrays.asList(input)); }
	protected void setInputs(List<Supplier<Evaluable<? extends I>>> inputs) { this.inputs = inputs; }

	public List<Supplier<Evaluable<? extends I>>> getInputs() { return inputs; }

	protected void setArguments(List<Argument<? extends I>> arguments) {
		this.arguments = arguments;
	}

	public synchronized List<Argument<? extends I>> getArguments() {
		return arguments;
	}

	public synchronized List<ArrayVariable<? extends I>> getArgumentVariables() {
		if (getArguments() == null) return null;

		return getArguments().stream()
				.map(arg -> Optional.ofNullable(arg).map(Argument::getVariable).orElse(null))
				.map(var -> (ArrayVariable<? extends I>) var)
				.collect(Collectors.toList());
	}

	public ArrayVariable getArgumentForInput(Supplier<Evaluable<? extends I>> input) {
		if (getArgumentVariables() == null) {
			throw new IllegalArgumentException(getName() + " is not compiled");
		}

		return OperationAdapter.getArgumentForInput((List) getArgumentVariables(), (Supplier) input);
	}

	public void resetArguments() { this.arguments = null; }

	public void init() {
		if (function == null) setFunctionName(OperationAdapter.functionName(getClass()));
		metadata = prepareMetadata(new OperationMetadata(getFunctionName(), getName()));

		purgeVariables();
	}

	protected OperationMetadata prepareMetadata(OperationMetadata metadata) {
		return metadata;
	}

	public void addVariable(ExpressionAssignment<?> v) {
		if (variables == null) {
			variables = new ArrayList<>();
		}

		variables.add(v);
	}

	public List<ExpressionAssignment<?>> getVariables() { return variables == null ? Collections.emptyList() : variables; }

	public void purgeVariables() { this.variables = null; }

	@Override
	public void destroy() {
		if (getInputs() != null) {
			getInputs().stream().map(in -> in instanceof Producer ? (Producer) in : null)
					.filter(Objects::nonNull)
					.forEach(Producer::destroy);
		}

		resetArguments();
	}

	@Override
	public String description(List<String> children) {
		return Optional.ofNullable(getMetadata()).map(OperationMetadata::getShortDescription).orElse("null") +
				"(" + String.join(", ", children) + ")";
	}
}
