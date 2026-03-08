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
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.Argument;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.uml.Named;
import io.almostrealism.util.DescribableParent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * {@link ComputableBase} is a foundational abstract class that provides the core infrastructure
 * for computable operations in the Almost Realism computation framework. It serves as the base
 * class for operations that can be compiled into executable compute kernels.
 *
 * <p>This class manages:
 * <ul>
 *   <li>Input producers that supply data to the computation</li>
 *   <li>Arguments that represent compiled input variables</li>
 *   <li>Expression variables used during computation</li>
 *   <li>Operation metadata for profiling and identification</li>
 *   <li>Function naming for generated code</li>
 * </ul>
 *
 * <p>The lifecycle of a {@link ComputableBase} instance typically involves:
 * <ol>
 *   <li>Construction with input {@link Producer}s</li>
 *   <li>Initialization via {@link #init()} to set up metadata and function names</li>
 *   <li>Argument assignment during scope preparation</li>
 *   <li>Execution of the compiled operation</li>
 *   <li>Resource cleanup via {@link #destroy()}</li>
 * </ol>
 *
 * <p>Subclasses should implement the abstract methods to define specific computation logic
 * and how the operation should be compiled and executed.
 *
 * @param <I> the type of input data consumed by this computation
 * @param <T> the type of result produced by this computation
 *
 * @author Michael Murray
 * @see ComputableParallelProcess
 * @see ComputationBase
 * @see Producer
 */
public abstract class ComputableBase<I, T> implements
		ComputableParallelProcess<Process<?, ?>, T>,
		DescribableParent<Process<?, ?>>,
		Destroyable, OperationInfo, Named, NamedFunction {

	/**
	 * The name of the function that will be generated for this computation.
	 * This is used as an identifier in generated code and for debugging purposes.
	 */
	private String function;

	/**
	 * The list of input {@link Producer}s that supply data to this computation.
	 * These producers are evaluated to provide the input values when the computation executes.
	 */
	private List<Producer<I>> inputs;

	/**
	 * The list of {@link Argument}s corresponding to the compiled input variables.
	 * This is populated during scope preparation and represents the mapping between
	 * input producers and their corresponding variables in the generated code.
	 */
	private List<Argument<? extends I>> arguments;

	/**
	 * Intermediate expression variables used during the computation.
	 * These are temporary variables that may be needed for complex calculations.
	 */
	private List<ExpressionAssignment<?>> variables;

	/**
	 * Metadata about this operation, including its name, signature, and profiling information.
	 * This is used for debugging, logging, and performance analysis.
	 */
	private OperationMetadata metadata;

	/**
	 * Constructs a new {@link ComputableBase} with the specified input producers.
	 *
	 * @param input the input producers that will supply data to this computation
	 */
	@SafeVarargs
	public ComputableBase(Producer<I>... input) {
		setInputs(input);
	}

	/**
	 * Sets the function name for this computation.
	 * This name is used as an identifier in generated code.
	 *
	 * @param name the function name to set
	 */
	@Override
	public void setFunctionName(String name) { function = name; }

	/**
	 * Returns the function name for this computation.
	 *
	 * @return the function name, or {@code null} if not set
	 */
	@Override
	public String getFunctionName() { return function; }

	/**
	 * Sets the operation metadata for this computation.
	 *
	 * @param metadata the metadata to associate with this operation
	 */
	protected void setMetadata(OperationMetadata metadata) { this.metadata = metadata; }

	/**
	 * Returns the operation metadata for this computation.
	 *
	 * @return the operation metadata, or {@code null} if not initialized
	 */
	@Override
	public OperationMetadata getMetadata() { return metadata; }

	/**
	 * Returns the human-readable name of this operation.
	 * The name is derived from the class name and function name using {@link OperationAdapter}.
	 *
	 * @return the operation name
	 */
	@Override
	public String getName() { return OperationAdapter.operationName(null, getClass(), getFunctionName()); }

	/**
	 * Sets the input producers from a varargs array.
	 *
	 * @param input the input producers
	 */
	@SafeVarargs
	protected final void setInputs(Producer<I>... input) { setInputs(Arrays.asList(input)); }

	/**
	 * Sets the input producers from a list.
	 *
	 * @param inputs the list of input producers
	 */
	protected void setInputs(List<Producer<I>> inputs) { this.inputs = inputs; }

	/**
	 * Returns the list of input producers for this computation.
	 *
	 * @return the list of input producers
	 */
	public List<Producer<I>> getInputs() { return inputs; }

	/**
	 * Sets the compiled arguments for this computation.
	 * Arguments represent the mapping between input producers and generated code variables.
	 *
	 * @param arguments the list of arguments
	 */
	protected void setArguments(List<Argument<? extends I>> arguments) {
		this.arguments = arguments;
	}

	/**
	 * Returns the compiled arguments for this computation.
	 * This method is synchronized to ensure thread-safe access.
	 *
	 * @return the list of arguments, or {@code null} if not yet compiled
	 */
	public synchronized List<Argument<? extends I>> getArguments() {
		return arguments;
	}

	/**
	 * Returns the array variables corresponding to the compiled arguments.
	 * Each argument's variable represents an input array in the generated code.
	 *
	 * @return the list of argument variables, or {@code null} if arguments are not set
	 */
	public synchronized List<ArrayVariable<? extends I>> getArgumentVariables() {
		if (getArguments() == null) return null;

		return getArguments().stream()
				.map(arg -> Optional.ofNullable(arg).map(Argument::getVariable).orElse(null))
				.map(var -> (ArrayVariable<? extends I>) var)
				.collect(Collectors.toList());
	}

	/**
	 * Retrieves the argument variable corresponding to a specific input producer.
	 *
	 * @param input the input supplier to find the argument for
	 * @return the corresponding array variable
	 * @throws IllegalArgumentException if this computation is not compiled
	 */
	public ArrayVariable getArgumentForInput(Supplier<Evaluable<? extends I>> input) {
		if (getArgumentVariables() == null) {
			throw new IllegalArgumentException(getName() + " is not compiled");
		}

		return OperationAdapter.getArgumentForInput((List) getArgumentVariables(), (Supplier) input);
	}

	/**
	 * Resets the compiled arguments, clearing the argument list.
	 * This should be called when the computation needs to be recompiled.
	 */
	public void resetArguments() { this.arguments = null; }

	/**
	 * Initializes this computation by setting up the function name and metadata.
	 * This method should be called before the computation is compiled or executed.
	 *
	 * <p>If no function name has been set, a default name is generated based on the class.
	 * The metadata is created and prepared using {@link #prepareMetadata(OperationMetadata)}.
	 * Any existing intermediate variables are purged.
	 */
	public void init() {
		if (function == null) setFunctionName(OperationAdapter.functionName(getClass()));
		metadata = prepareMetadata(new OperationMetadata(getFunctionName(), getName()));

		purgeVariables();
	}

	/**
	 * Prepares and potentially modifies the operation metadata before it is finalized.
	 * Subclasses can override this method to add additional metadata.
	 *
	 * @param metadata the initial metadata to prepare
	 * @return the prepared metadata (may be the same instance or a modified version)
	 */
	protected OperationMetadata prepareMetadata(OperationMetadata metadata) {
		return metadata;
	}

	/**
	 * Adds an intermediate expression variable to this computation.
	 * These variables are used for temporary calculations during execution.
	 *
	 * @param v the expression assignment to add
	 */
	public void addVariable(ExpressionAssignment<?> v) {
		if (variables == null) {
			variables = new ArrayList<>();
		}

		variables.add(v);
	}

	/**
	 * Returns the list of intermediate expression variables.
	 *
	 * @return the list of variables, or an empty list if none have been added
	 */
	public List<ExpressionAssignment<?>> getVariables() { return variables == null ? Collections.emptyList() : variables; }

	/**
	 * Clears all intermediate expression variables.
	 * This is typically called during initialization or reset.
	 */
	public void purgeVariables() { this.variables = null; }

	/**
	 * Destroys this computation and releases associated resources.
	 * This method recursively destroys all input producers and resets the arguments.
	 *
	 * <p>After calling this method, the computation should not be used without
	 * re-initialization.
	 */
	@Override
	public void destroy() {
		if (getInputs() != null) {
			getInputs().stream().map(in -> in instanceof Producer ? (Producer) in : null)
					.filter(Objects::nonNull)
					.forEach(Producer::destroy);
		}

		resetArguments();
	}

	/**
	 * Returns a human-readable description of this computation including its children.
	 * The description includes the short description from metadata and a comma-separated
	 * list of child descriptions.
	 *
	 * @param children the list of child descriptions
	 * @return a formatted description string
	 */
	@Override
	public String description(List<String> children) {
		return Optional.ofNullable(getMetadata()).map(OperationMetadata::getShortDescription).orElse("null") +
				"(" + String.join(", ", children) + ")";
	}
}
