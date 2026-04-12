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
import io.almostrealism.scope.Argument;
import io.almostrealism.scope.ArgumentList;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.uml.Named;
import io.almostrealism.util.DescribableParent;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Abstract base for compiled operation adapters that manage function names, inputs, and arguments.
 *
 * <p>{@code OperationAdapter} implements the common lifecycle for operations that are compiled
 * to native code: it tracks the function name, input suppliers, and the resolved argument variables
 * after scope compilation. It also provides utilities for argument lookup and standardized
 * function name generation.</p>
 *
 * @param <T> the memory type shared by all arguments and inputs of this operation
 *
 * @see io.almostrealism.scope.Argument
 * @see io.almostrealism.scope.ArrayVariable
 * @see NamedFunction
 */
public abstract class OperationAdapter<T> implements
											ArgumentList<T>,
											DescribableParent<Argument<? extends T>>,
											Destroyable, OperationInfo,
											NamedFunction, Named {

	/** Global counter for generating unique function IDs across all operation instances. */
	private static long functionId = 0;

	/** The generated function name for this operation. */
	private String function;

	/** The list of input producers that provide data to this operation. */
	private List<Supplier<Evaluable<? extends T>>> inputs;
	/** The list of compiled argument descriptors, set after scope compilation. */
	private List<Argument<? extends T>> arguments;

	/**
	 * Creates a new operation adapter with no pre-configured function name or arguments.
	 */
	public OperationAdapter() { }

	@Override
	public void setFunctionName(String name) { function = name; }

	@Override
	public String getFunctionName() { return function; }

	@Override
	public int getArgsCount() { return getArguments().size(); }

	/**
	 * Sets the input producers for this operation.
	 *
	 * @param inputs the list of input producers
	 */
	protected void setInputs(List<Supplier<Evaluable<? extends T>>> inputs) { this.inputs = inputs; }

	/**
	 * Returns the input producers for this operation.
	 *
	 * @return the list of input producers, or {@code null} if not yet configured
	 */
	public List<Supplier<Evaluable<? extends T>>> getInputs() { return inputs; }

	/**
	 * Sets the compiled argument list for this operation.
	 *
	 * @param arguments the list of compiled argument descriptors
	 */
	protected void setArguments(List<Argument<? extends T>> arguments) {
		this.arguments = arguments;
	}

	/**
	 * Returns the compiled argument list for this operation.
	 *
	 * @return the list of arguments, or {@code null} if not yet compiled
	 */
	public List<Argument<? extends T>> getArguments() { return arguments; }

	/**
	 * Returns the argument variables extracted from the argument list.
	 *
	 * <p>This method is synchronized to prevent concurrent modification of the argument list.
	 *
	 * @return the list of array variables, or {@code null} if arguments have not been set
	 */
	public synchronized List<ArrayVariable<? extends T>> getArgumentVariables() {
		if (getArguments() == null) return null;

		return getArguments().stream()
				.map(arg -> Optional.ofNullable(arg).map(Argument::getVariable).orElse(null))
				.map(var -> (ArrayVariable<? extends T>) var)
				.collect(Collectors.toList());
	}

	/**
	 * Clears the argument list, allowing this operation to be recompiled with a fresh argument map.
	 */
	public void resetArguments() { this.arguments = null; }

	/**
	 * Waits for the given semaphore to complete before proceeding.
	 *
	 * @param semaphore the semaphore to wait for, or {@code null} to return immediately
	 */
	protected void waitFor(Semaphore semaphore) {
		if (semaphore == null) return;
		semaphore.waitFor();
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Destroys all input producers and resets the argument list.
	 */
	@Override
	public void destroy() {
		if (getInputs() != null) {
			getInputs().forEach(Destroyable::destroy);
		}

		resetArguments();
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Returns the metadata short description followed by the child descriptions in parentheses.
	 *
	 * @param children the rendered descriptions of the child operations
	 * @return the description string
	 */
	@Override
	public String description(List<String> children) {
		return Optional.ofNullable(getMetadata()).map(OperationMetadata::getShortDescription).orElse("null") +
				"(" + String.join(", ", children) + ")";
	}

	/**
	 * Finds the argument variable in {@code vars} whose producer matches the given {@code input}.
	 *
	 * <p>First checks for a direct producer match; if that fails, checks whether any
	 * argument's producer delegates to the specified input.
	 *
	 * @param vars the list of array variables to search
	 * @param input the input producer to match
	 * @return the matching array variable, or {@code null} if not found
	 * @throws IllegalArgumentException if more than one argument matches
	 */
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

	/**
	 * Generates a unique function name for the given class using the global function ID counter.
	 *
	 * <p>The format is {@code f_<SimpleName>_<id>} where the simple name is camel-cased.
	 *
	 * @param c the class whose simple name is used as the function name base
	 * @return a unique function name
	 * @throws IllegalArgumentException if the class's simple name is too short to use
	 */
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

	/**
	 * Returns the operation name, preferring the named object's name over the class simple name.
	 *
	 * @param named the named object to try first, or {@code null}
	 * @param c the fallback class whose simple name is used if the named object has no name
	 * @param functionName unused parameter retained for API compatibility
	 * @return the operation name
	 */
	public static String operationName(Named named, Class c, String functionName) {
		if (named != null && named.getName() != null) {
			return named.getName();
		}

		String name = c.getSimpleName();
		if (name.trim().length() <= 0) name = "anonymous";
		return name;
	}
}
