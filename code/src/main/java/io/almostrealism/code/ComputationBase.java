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

import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.compute.Process;
import io.almostrealism.compute.ProcessContext;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.profile.OperationInfo;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.relation.Countable;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.Argument;
import io.almostrealism.scope.Argument.Expectation;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.Variable;
import io.almostrealism.uml.Signature;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * {@link ComputationBase} is the primary abstract base class for implementing computations
 * in the Almost Realism framework. It extends {@link ComputableBase} with additional support
 * for scope lifecycle management, compute requirements, and optimization caching.
 *
 * <p>This class serves as the foundation for most computation implementations and provides:
 * <ul>
 *   <li>Compute requirement management (e.g., CPU, GPU preferences)</li>
 *   <li>Scope lifecycle integration for argument preparation and management</li>
 *   <li>Optimization caching to avoid redundant optimization passes</li>
 *   <li>Count-based iteration support via {@link io.almostrealism.relation.Countable}</li>
 *   <li>Language operations access for code generation</li>
 * </ul>
 *
 * <p>The class implements the {@link Signature} interface, allowing subclasses to provide
 * unique signatures that identify the computation's behavior. This is useful for caching
 * and optimization purposes.
 *
 * <h2>Usage Pattern</h2>
 * <pre>{@code
 * public class MyComputation extends ComputationBase<MyInput, MyOutput, Evaluable<MyOutput>> {
 *     public MyComputation(Producer<MyInput>... inputs) {
 *         super(inputs);
 *     }
 *
 *     @Override
 *     public Scope<MyOutput> getScope(KernelStructureContext context) {
 *         Scope<MyOutput> scope = super.getScope(context);
 *         // Add computation logic to scope
 *         return scope;
 *     }
 * }
 * }</pre>
 *
 * @param <I> the type of input data consumed by this computation
 * @param <O> the type of output data produced by this computation
 * @param <T> the type of evaluable result (typically {@code Evaluable<O>})
 *
 * @author Michael Murray
 * @see ComputableBase
 * @see Computation
 * @see Scope
 */
public abstract class ComputationBase<I, O, T>
					extends ComputableBase<I, T>
					implements Computation<O>, Signature {

	/**
	 * Language operations provider for code generation.
	 * Set during scope preparation and used for generating language-specific code.
	 */
	private LanguageOperations lang;

	/**
	 * List of compute requirements specifying execution preferences.
	 * These requirements indicate hardware preferences such as CPU or GPU execution.
	 */
	private List<ComputeRequirement> requirements;

	/**
	 * The process context used during optimization.
	 * Stored to detect when re-optimization might be needed due to context changes.
	 */
	private ProcessContext optimizationCtx;

	/**
	 * Cached optimized version of this computation.
	 * Once optimization is performed, the result is stored here to avoid redundant processing.
	 */
	protected ComputationBase<I, O, T> optimized;

	/**
	 * Prepares the operation metadata by incorporating process information and signature.
	 * This method extends the parent implementation to add signature information.
	 *
	 * @param metadata the initial metadata to prepare
	 * @return the prepared metadata with process information and signature
	 */
	@Override
	protected OperationMetadata prepareMetadata(OperationMetadata metadata) {
		metadata = OperationInfo.metadataForProcess(this, metadata);
		metadata.setSignature(signature());
		return metadata;
	}

	/**
	 * Returns the list of compute requirements for this computation.
	 * These requirements specify execution preferences such as CPU or GPU.
	 *
	 * @return the list of compute requirements, or {@code null} if none specified
	 */
	@Override
	public List<ComputeRequirement> getComputeRequirements() {
		return requirements;
	}

	/**
	 * Sets the compute requirements for this computation.
	 *
	 * @param requirements the list of compute requirements to set
	 */
	public void setComputeRequirements(List<ComputeRequirement> requirements) {
		this.requirements = requirements;
	}

	/**
	 * Returns the iteration count for this computation.
	 * The count is derived from the input producers and must be consistent
	 * across all inputs.
	 *
	 * @return the iteration count
	 * @throws UnsupportedOperationException if inputs have inconsistent counts
	 */
	@Override
	public long getCountLong() {
		long p = getInputs().stream().mapToLong(Countable::countLong).distinct().count();

		if (p == 1) {
			return getInputs().stream().mapToLong(Countable::countLong).distinct().sum();
		}

		throw new UnsupportedOperationException();
	}

	/**
	 * Determines if this computation has a fixed iteration count.
	 * A fixed count means the count will not change during execution.
	 *
	 * @return {@code true} if the count is fixed, {@code false} otherwise
	 */
	@Override
	public boolean isFixedCount() {
		List<Producer<I>> inputs = getInputs();
		if (inputs == null) return false;

		return inputs.stream().noneMatch(v -> v instanceof Countable && !((Countable) v).isFixedCount());
	}

	/**
	 * Returns the language operations provider for code generation.
	 *
	 * @return the language operations, or {@code null} if not yet set
	 */
	protected LanguageOperations getLanguage() { return lang; }

	/**
	 * Returns a name provider for generating variable names.
	 * The default implementation returns a {@link DefaultNameProvider} based on this computation.
	 *
	 * @return the name provider instance
	 */
	public NameProvider getNameProvider() { return new DefaultNameProvider(this); }

	/**
	 * Prepares the arguments for this computation using the provided argument map.
	 * This method is part of the {@link ScopeLifecycle} and is called during compilation.
	 *
	 * <p>If arguments have already been prepared (indicated by non-null argument variables),
	 * this method returns early to avoid redundant processing.
	 *
	 * @param map the argument map for resolving and registering arguments
	 */
	@Override
	public void prepareArguments(ArgumentMap map) {
		if (getArgumentVariables() != null) return;
		ScopeLifecycle.prepareArguments(getInputs().stream(), map);
		getInputs().forEach(map::add);
	}

	/**
	 * Prepares the scope for this computation using the provided scope input manager.
	 * This method is part of the {@link ScopeLifecycle} and sets up the language
	 * operations and argument variables.
	 *
	 * <p>If the scope has already been prepared, this method returns early.
	 *
	 * @param manager the scope input manager providing language and argument services
	 * @param context the kernel structure context for scope preparation
	 */
	@Override
	public void prepareScope(ScopeInputManager manager, KernelStructureContext context) {
		if (getArgumentVariables() != null) return;
		this.lang = manager.getLanguage();
		ScopeLifecycle.prepareScope(getInputs().stream(), manager, context);
		assignArguments(manager);
	}

	/**
	 * Resets the arguments for this computation and all input producers.
	 * This clears the compiled state, allowing the computation to be recompiled.
	 * Also clears the language operations reference.
	 */
	@Override
	public void resetArguments() {
		super.resetArguments();
		ScopeLifecycle.resetArguments(getInputs().stream());
		this.lang = null;
	}

	/**
	 * Generates {@link ArrayVariable}s for the values available via
	 * {@link #getInputs()} and stores them so they can be retrieved
	 * via {@link #getArguments()}.
	 *
	 * <p>Each input producer is mapped to an argument with
	 * {@link Expectation#EVALUATE_AHEAD} expectation, indicating that
	 * inputs should be evaluated before this computation executes.
	 *
	 * @param provider the argument provider for creating argument variables
	 */
	protected void assignArguments(ArgumentProvider provider) {
		setArguments(getInputs().stream()
				.map(provider.argumentForInput(getNameProvider()))
				.map(var ->
						Optional.ofNullable(var).map(v ->
								new Argument<>(v, Expectation.EVALUATE_AHEAD))
								.orElse(null))
				.map(arg -> (Argument<? extends I>) arg)
				.collect(Collectors.toList()));
	}

	/**
	 * Retrieves the argument variable for a specific input by index.
	 *
	 * @param index the zero-based index of the input
	 * @return the array variable for the specified input
	 * @throws IllegalArgumentException if the index is out of bounds or the
	 *         input does not have a corresponding argument
	 */
	public ArrayVariable getArgument(int index) {
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

	/**
	 * Returns the child processes (inputs) of this computation.
	 * Each input producer is wrapped in a {@link Process} if not already one.
	 *
	 * @return the collection of child processes, or {@code null} if inputs are not set
	 */
	@Override
	public Collection<Process<?, ?>> getChildren() {
		List<Producer<I>> inputs = getInputs();
		if (inputs == null) return null;

		return inputs.stream()
				.map(in -> in instanceof Process<?, ?> ? (Process<?, ?>) in : Process.of(in))
				.collect(Collectors.toList());
	}

	/**
	 * Returns the output variable for this computation.
	 * The default implementation returns {@code null}, indicating no explicit output variable.
	 * Subclasses may override to provide a specific output variable.
	 *
	 * @return {@code null} by default
	 */
	@Override
	public Variable getOutputVariable() { return null; }

	/**
	 * Returns a {@link Scope} containing the variables and methods necessary
	 * to compute the output of this computation.
	 *
	 * <p>This method creates a new scope with the function name and metadata,
	 * adds compute requirements if specified, and includes any intermediate
	 * variables. Subclasses should call this method and add their specific
	 * computation logic to the returned scope.
	 *
	 * @param context the kernel structure context for scope generation
	 * @return the scope for this computation
	 * @throws IllegalArgumentException if an optimized version exists and this
	 *         original computation is being used instead
	 */
	@Override
	public Scope<O> getScope(KernelStructureContext context) {
		if (optimized != null & optimized != this) {
			throw new IllegalArgumentException("This Computation should not be used, as an optimized version already exists");
		}

		Scope<O> scope = new Scope<>(getFunctionName(), getMetadata());
		if (getComputeRequirements() != null) {
			scope.setComputeRequirements(getComputeRequirements());
		}

		scope.getVariables().addAll(getVariables());
		return scope;
	}

	/**
	 * Returns a signature string that uniquely identifies the behavior of this computation.
	 * Signatures are used for caching and optimization purposes.
	 *
	 * <p>Subclasses should override this method to provide a meaningful signature.
	 * The default implementation returns {@code null}.
	 *
	 * @return {@code null} by default; subclasses should provide meaningful signatures
	 */
	@Override
	public String signature() { return null; }

	/**
	 * Optimizes this computation for the given process context.
	 *
	 * <p>This method extends {@link ComputableParallelProcess#optimize(ProcessContext)}
	 * to cache the optimized result and preserve compute requirements. If an optimized
	 * version already exists, it is returned directly. A warning is logged if the
	 * cached optimization was created for a different context count.
	 *
	 * @param ctx the process context for optimization
	 * @return the optimized computation (may be this instance or a new optimized version)
	 * @see ComputationBase#getComputeRequirements()
	 */
	@Override
	public ComputationBase<I, O, T> optimize(ProcessContext ctx) {
		if (optimized == null) {
			optimizationCtx = ctx;
			optimized = (ComputationBase<I, O, T>)
					super.optimize(ctx);
			optimized.setComputeRequirements(getComputeRequirements());
		} else if (Countable.countLong(ctx) != Countable.countLong(optimizationCtx)) {
			warn("Cached optimization may not be ideal for new ProcessContext count of " +
					Countable.countLong(ctx) + " compared to " +
					Countable.countLong(optimizationCtx) + ")");
		}

		return optimized;
	}

	/**
	 * Generates a replacement computation with new inputs while preserving
	 * compute requirements.
	 *
	 * <p>This method extends {@link ComputableParallelProcess#generateReplacement(List)}
	 * to ensure that the compute requirements from this computation are copied to
	 * the replacement.
	 *
	 * @param inputs the new inputs for the replacement computation
	 * @return the replacement computation with preserved compute requirements
	 * @see ComputationBase#getComputeRequirements()
	 */
	public ComputationBase<I, O, T> generateReplacement(List<Process<?, ?>> inputs) {
		ComputationBase<I, O, T> replacement = (ComputationBase<I, O, T>)
				super.generateReplacement(inputs);
		replacement.setComputeRequirements(getComputeRequirements());
		return replacement;
	}

	/**
	 * Returns a human-readable description of this computation.
	 * The description includes the parent description plus count information.
	 *
	 * @return a description string with count and fixed/variable status
	 */
	@Override
	public String describe() {
		return super.describe() + " | " +
				getCountLong() + "x" +
				(isFixedCount() ? " (fixed)" : " (variable)");
	}
}
