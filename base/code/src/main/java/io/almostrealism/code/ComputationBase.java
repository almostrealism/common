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

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
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
	private ComputationBase<I, O, T> optimized;

	/**
	 * Weak reference to the {@link ArgumentProvider} that last drove
	 * {@link #prepareScope(ArgumentProvider, KernelStructureContext)} for this
	 * computation. Used to detect when a subsequent compilation is operating
	 * against a different manager, in which case any cached argument variables
	 * on this node refer to the previous compilation and must be discarded.
	 * Held weakly so that retaining this reference does not pin the previous
	 * manager in memory.
	 */
	private WeakReference<ArgumentProvider> lastArgumentProvider;

	/**
	 * Whether every input of this computation has a fixed count, once {@link #isFixedCount()}
	 * has determined it, or {@code null} before then. Each input's count is fixed or variable
	 * from its construction (see {@link Countable#isFixedCount()}), so the answer is determined
	 * once instead of walking the whole input graph on every call. Setting the inputs clears it.
	 */
	private volatile Boolean fixedCountInputs;

	/**
	 * The child processes presented by {@link #getChildren()}, built from the inputs on the
	 * first request, or {@code null} before then. Setting the inputs clears it.
	 */
	private volatile List<Process<?, ?>> children;

	/**
	 * Prepares the operation metadata by incorporating process information and signature.
	 * This method extends the parent implementation to add signature information. The signature
	 * hashes the signatures of every input, so it is deferred until it is first requested (see
	 * {@link OperationMetadata#deferSignature}) rather than computed for every operation built.
	 *
	 * @param metadata the initial metadata to prepare
	 * @return the prepared metadata with process information and signature
	 */
	@Override
	protected OperationMetadata prepareMetadata(OperationMetadata metadata) {
		metadata = OperationInfo.metadataForProcess(this, metadata);
		metadata.deferSignature(this::signature);
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
	 * <p>The count is fixed when the count of every input is fixed, as each input reports
	 * it through {@link Countable#isFixedCount(Object)}. Because that is decided when the
	 * inputs are constructed, it is determined on the first call and remembered until the
	 * inputs are set again. Optimization asks for the count of each node of a graph many
	 * times, and every one of those questions would otherwise walk the entire input graph
	 * below the node.</p>
	 *
	 * @return {@code true} if the count is fixed, {@code false} otherwise
	 */
	@Override
	public boolean isFixedCount() {
		Boolean fixed = fixedCountInputs;
		if (fixed != null) return fixed;

		List<Producer<I>> inputs = getInputs();
		if (inputs == null) return false;

		fixed = inputs.stream().allMatch(Countable::isFixedCount);
		fixedCountInputs = fixed;
		return fixed;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>The inputs are stored as an unmodifiable snapshot of the given list, so that
	 * {@link #getInputs()} cannot be used to replace an input in place: replacing the
	 * inputs always goes through this method, which forgets whether the previous inputs
	 * all had a fixed count, and the child processes built from them.</p>
	 */
	@Override
	protected void setInputs(List<Producer<I>> inputs) {
		super.setInputs(inputs == null ? null : Collections.unmodifiableList(new ArrayList<>(inputs)));
		fixedCountInputs = null;
		children = null;
	}

	/**
	 * Prepares the scope for this computation using the provided scope input manager.
	 * This method is part of the {@link ScopeLifecycle} and sets up the argument variables.
	 *
	 * <p>If the scope has already been prepared by the same {@link ArgumentProvider}
	 * as on the previous call, returns early to avoid redundant processing. If the
	 * manager differs (for example, because this node is being recompiled into a new
	 * kernel), the cached argument state is invalidated via {@link #resetArguments()}
	 * before being repopulated. This is necessary because cached
	 * {@link ArrayVariable}s carry delegate pointers into the previous manager's
	 * underlying argument map (and its aggregate buffer); reusing them under a
	 * different manager would cause the new kernel to read or write stale memory.
	 *
	 * @param manager the scope input manager providing argument services
	 * @param context the kernel structure context for scope preparation
	 */
	@Override
	public void prepareScope(ArgumentProvider manager, KernelStructureContext context) {
		ArgumentProvider previous = lastArgumentProvider == null ? null : lastArgumentProvider.get();
		if (getArgumentVariables() != null && previous == manager) return;
		if (getArgumentVariables() != null) resetArguments();
		this.lastArgumentProvider = new WeakReference<>(manager);
		ScopeLifecycle.prepareScope(getInputs().stream(), manager, context);
		assignArguments(manager);
	}

	/**
	 * Resets the arguments for this computation and all input producers.
	 * This clears the compiled state, allowing the computation to be recompiled.
	 *
	 * <p>This is the entry point of a reset pass: it seeds an identity-based visited set,
	 * marks this node, and delegates to {@link #resetArguments(Set)}. Seeding the set here
	 * means a deeply shared (diamond-shaped) input graph is walked once per node instead of
	 * once per path to it, which the un-memoized descent made exponential in the number of
	 * shared sub-graphs.</p>
	 */
	@Override
	public void resetArguments() {
		ScopeLifecycle.resetArguments(this);
	}

	/**
	 * Resets this computation's cached arguments and descends into its input producers,
	 * skipping any node already in {@code visited}.
	 *
	 * <p>The single-node clear is performed by {@code super.resetArguments()}; the descent
	 * threads {@code visited} through {@link ScopeLifecycle#resetArguments(java.util.stream.Stream, Set)}
	 * so each shared input is reset at most once per pass.</p>
	 *
	 * @param visited the nodes already reset on this pass
	 */
	@Override
	public void resetArguments(Set<ScopeLifecycle> visited) {
		super.resetArguments();
		ScopeLifecycle.resetArguments(getInputs().stream(), visited);
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
				.map(provider.argumentForInput())
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
	 * Each input is presented as a {@link Process} by
	 * {@link Process#asProcess(java.util.function.Supplier)}: an input that is a process is
	 * its own child, and any other input is wrapped.
	 *
	 * <p>The children are built once from the inputs and the same unmodifiable list is
	 * returned until the inputs are set again. Optimization and the traversals of the
	 * process tree ask every node for its children many times, so rebuilding the list
	 * (and a new wrapper for each input that is not a {@link Process}) on every call is
	 * a cost paid for each visit rather than once per node.</p>
	 *
	 * @return the collection of child processes, or {@code null} if inputs are not set
	 */
	@Override
	public Collection<Process<?, ?>> getChildren() {
		List<Process<?, ?>> result = children;
		if (result != null) return result;

		List<Producer<I>> inputs = getInputs();
		if (inputs == null) return null;

		result = Collections.unmodifiableList(inputs.stream()
				.map(Process::asProcess)
				.collect(Collectors.toList()));
		children = result;
		return result;
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
		return createScope(Scope::new);
	}

	/**
	 * Creates the kernel scope of this computation as a scope of the kind
	 * {@code kind} constructs, configured the way every kernel scope must be: named
	 * by {@link #getFunctionName()} with this computation's {@link #getMetadata()},
	 * carrying its {@link #getComputeRequirements() compute requirements} (which
	 * {@link Scope#convertArgumentsToRequiredScopes} consults when deciding whether
	 * one kernel may absorb another) and its intermediate {@link #getVariables()
	 * variables}. A subclass whose kernel is not a plain {@link Scope} (a
	 * {@code Repeated} loop, for example) builds it here rather than by hand, so it
	 * cannot drop any of that configuration.
	 *
	 * @param kind constructs an unconfigured scope from a name and metadata, such
	 *             as {@code Scope::new} or {@code Repeated::new}
	 * @param <S>  the kind of scope
	 * @return the configured scope
	 * @throws IllegalArgumentException if an optimized version has superseded this
	 *         computation, which must then not be compiled itself
	 */
	protected <S extends Scope<O>> S createScope(BiFunction<String, OperationMetadata, S> kind) {
		if (isSupersededByOptimizedVersion()) {
			throw new IllegalArgumentException("This Computation should not be used, as an optimized version already exists");
		}

		S scope = kind.apply(getFunctionName(), getMetadata());
		if (getComputeRequirements() != null) {
			scope.setComputeRequirements(getComputeRequirements());
		}

		scope.getVariables().addAll(getVariables());
		return scope;
	}

	/**
	 * Returns the optimized version of this computation produced by
	 * {@link #optimize(ProcessContext)}, or {@code null} when it has not been
	 * optimized. It may be this computation itself.
	 *
	 * @return the cached optimized version, or {@code null}
	 */
	protected ComputationBase<I, O, T> getOptimizedVersion() {
		return optimized;
	}

	/**
	 * Returns whether optimization produced a different computation that should be
	 * used in place of this one.
	 *
	 * @return {@code true} when an optimized version other than this one exists
	 */
	protected boolean isSupersededByOptimizedVersion() {
		return optimized != null && optimized != this;
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
	@Override
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
