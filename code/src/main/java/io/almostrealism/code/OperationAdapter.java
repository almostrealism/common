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

import io.almostrealism.concurrent.Semaphore;
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.profile.OperationInfo;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.relation.Delegated;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.scope.ArgumentList;
import io.almostrealism.uml.Named;
import io.almostrealism.scope.Argument;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.util.DescribableParent;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public abstract class OperationAdapter<T> implements
											ArgumentList<T>,
											DescribableParent<Argument<? extends T>>,
											Destroyable, OperationInfo,
											NamedFunction, Named {

	private static long functionId = 0;

	private String function;

	private List<Supplier<Evaluable<? extends T>>> inputs;
	private List<Argument<? extends T>> arguments;

	public OperationAdapter() { }

	@Override
	public void setFunctionName(String name) { function = name; }

	@Override
	public String getFunctionName() { return function; }

	@Override
	public int getArgsCount() { return getArguments().size(); }

	protected void setInputs(List<Supplier<Evaluable<? extends T>>> inputs) { this.inputs = inputs; }

	public List<Supplier<Evaluable<? extends T>>> getInputs() { return inputs; }

	protected void setArguments(List<Argument<? extends T>> arguments) {
		this.arguments = arguments;
	}

	public List<Argument<? extends T>> getArguments() { return arguments; }

	public synchronized List<ArrayVariable<? extends T>> getArgumentVariables() {
		if (getArguments() == null) return null;

		return getArguments().stream()
				.map(arg -> Optional.ofNullable(arg).map(Argument::getVariable).orElse(null))
				.map(var -> (ArrayVariable<? extends T>) var)
				.collect(Collectors.toList());
	}

	public void resetArguments() { this.arguments = null; }

	protected void waitFor(Semaphore semaphore) {
		if (semaphore == null) return;
		semaphore.waitFor();
	}

	@Override
	public void destroy() {
		if (getInputs() != null) {
			getInputs().stream().map(in -> in instanceof Destroyable ? (Destroyable) in : null)
					.filter(Objects::nonNull)
					.forEach(Destroyable::destroy);
		}

		resetArguments();
	}

	@Override
	public String description(List<String> children) {
		return Optional.ofNullable(getMetadata()).map(OperationMetadata::getShortDescription).orElse("null") +
				"(" + String.join(", ", children) + ")";
	}

	public static ArrayVariable getArgumentForInput(List<ArrayVariable> vars, Supplier<Evaluable> input) {
		if (input == null) return null;

		// Check for argument variables for which the original producer is
		// the specified input
		Set<ArrayVariable> var = vars.stream()
				.filter(arg -> arg != null && input.equals(arg.getProducer()))
				.collect(Collectors.toSet());
		if (var.size() == 1) return var.iterator().next();
		if (var.size() > 1) {
			throw new IllegalArgumentException("Multiple arguments match input");
		}

		// Additionally, check for variables for which the original producer
		// delegates to the specified input
		var = vars.stream()
				.filter(Objects::nonNull)
				.filter(arg -> arg.getProducer() instanceof Delegated)
				.filter(arg -> input.equals(((Delegated) arg.getProducer()).getDelegate()))
				.collect(Collectors.toSet());
		if (var.size() == 1) return var.iterator().next();
		if (var.size() > 1) {
			throw new IllegalArgumentException("Multiple arguments match input");
		}

		return null;
	}

	protected static String functionName(Class c) {
		String s = c.getSimpleName();
		if (s.length() == 0) {
			s = "anonymous";
		}

		if (s.length() < 2) {
			throw new IllegalArgumentException(c.getName() + " has too short of a simple name to use for a function");
		}

		return "f_" + s.substring(0, 1).toLowerCase() + s.substring(1) + "_" + functionId++;
	}

	public static String operationName(Named named, Class c, String functionName) {
		if (named != null && named.getName() != null) {
			return named.getName();
		}

		String name = c.getSimpleName();
		if (name.trim().length() <= 0) name = "anonymous";
		return name;
	}
}
