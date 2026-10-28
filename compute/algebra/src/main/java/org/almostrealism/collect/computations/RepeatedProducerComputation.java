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

package org.almostrealism.collect.computations;

import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.Process;
import io.almostrealism.expression.Expression;
import io.almostrealism.sequence.DefaultIndex;
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Repeated;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.Variable;
import org.almostrealism.collect.PackedCollection;

import java.util.List;
import java.util.OptionalInt;

/**
 * A computation that performs repeated operations on {@link PackedCollection} data structures,
 * implementing iterative algorithms with initialization, condition checking, and expression evaluation.
 * 
 * <p>This class provides a framework for creating computations that need to iterate over data
 * with a specific pattern: initialize values, check continuation conditions, and apply expressions
 * repeatedly until the condition is no longer satisfied.
 * 
 * <p>The loop is defined by three steps, each a protected method that a concrete subclass
 * implements:
 * <ul>
 *   <li><strong>Initial:</strong> {@link #getInitialValue(TraversableExpression[], Expression)}
 *       defines the value of each output element before the first iteration</li>
 *   <li><strong>Condition:</strong> {@link #getCondition(TraversableExpression[], Expression)}
 *       determines whether to continue the iteration</li>
 *   <li><strong>Expression:</strong> {@link #getExpression(TraversableExpression[], Expression, Expression)}
 *       defines the computation performed in each iteration step</li>
 * </ul>
 * 
 * <p>This class is {@code abstract}, and the steps are methods rather than functions supplied
 * at construction, so that the behaviour of every instance is fixed by its class, which,
 * together with its name and inputs, is what its {@link #signature()} identifies. A step that
 * depends on configuration reads that configuration from the subclass's own fields, which
 * the subclass then includes in its signature.
 *
 * <p><strong>Subclass Implementation Pattern:</strong>
 * <pre>{@code
 * // A concrete repeated computation that accumulates values
 * public class AccumulateComputation extends RepeatedProducerComputation {
 *     public AccumulateComputation(TraversalPolicy shape, Producer<PackedCollection> input) {
 *         super("accumulate", shape, input);
 *     }
 *
 *     @Override
 *     protected Expression<?> getInitialValue(TraversableExpression[] args, Expression index) {
 *         return e(0.0);                                    // Initialize to zero
 *     }
 *
 *     @Override
 *     protected Expression<Boolean> getCondition(TraversableExpression[] args, Expression index) {
 *         return index.lessThan(e(5));                      // Continue for 5 iterations
 *     }
 *
 *     @Override
 *     protected Expression<?> getExpression(TraversableExpression[] args,
 *                                           Expression globalIndex, Expression localIndex) {
 *         return args[1].getValueAt(localIndex).add(e(1.0)); // Add 1 each iteration
 *     }
 *
 *     @Override
 *     public AccumulateComputation generate(List<Process<?, ?>> children) {
 *         return new AccumulateComputation(getShape(),
 *             (Producer<PackedCollection>) children.get(1));
 *     }
 * }
 * }</pre>
 *
 * <p><strong>Memory Management:</strong>
 * The {@code memLength} parameter controls how many elements are processed by each kernel thread,
 * which can significantly impact performance. A higher value reduces the number of kernel launches
 * but increases memory usage per thread.
 * 
 * <p><strong>Thread Safety:</strong>
 * This class is designed to be thread-safe for concurrent execution on GPU kernels, but individual
 * instances should not be modified after construction.
 * 
 * @see CollectionProducerComputationBase
 * @see ConstantRepeatedProducerComputation
 * @see TraversableRepeatedProducerComputation
 * @see TraversableExpression
 *
 * @author Michael Murray
 */
public abstract class RepeatedProducerComputation extends CollectionProducerComputationBase {
	/** The number of elements processed by each kernel thread for memory optimization. */
	private final int memLength;

	/**
	 * Creates a repeated computation with default memory length of 1.
	 * 
	 * @param name The name identifier for this computation, used in generated code
	 * @param shape The {@link TraversalPolicy} defining the multi-dimensional shape 
	 *              and access pattern of the output data
	 * @param args Variable number of input {@link Producer}s providing evaluable collections
	 * 
	 * @see #RepeatedProducerComputation(String, TraversalPolicy, int, Producer[])
	 */
	@SafeVarargs
	protected RepeatedProducerComputation(String name, TraversalPolicy shape,
									   Producer<PackedCollection>... args) {
		this(name, shape, 1, args);
	}

	/**
	 * Creates a repeated computation with specified memory length for optimization.
	 * 
	 * <p>This constructor allows control over the memory length, which determines
	 * how many elements are processed by each kernel thread. Higher values can
	 * improve performance by reducing kernel launch overhead but increase memory
	 * usage per thread.
	 * 
	 * @param name The name identifier for this computation, used in generated code
	 * @param shape The {@link TraversalPolicy} defining the multi-dimensional shape
	 *              and access pattern of the output data  
	 * @param size The number of elements processed by each kernel thread (memory length)
	 * @param args Variable number of input {@link Producer}s providing evaluable collections
	 */
	@SafeVarargs
	protected RepeatedProducerComputation(String name, TraversalPolicy shape, int size,
									   Producer<PackedCollection>... args) {
		super(name, shape, args);
		this.memLength = size;
	}

	/**
	 * Returns the memory length for this computation.
	 * 
	 * <p>The memory length represents the number of elements that are processed
	 * by a single kernel thread. This value affects both memory usage and 
	 * execution performance.
	 * 
	 * @return The number of elements processed per kernel thread
	 */
	@Override
	public int getMemLength() { return memLength; }

	/**
	 * Returns the optional index limit for iteration bounds.
	 * 
	 * <p>Subclasses can override this method to provide specific limits on
	 * iteration indices, which can be used for optimization or to enforce
	 * bounds checking.
	 * 
	 * @return {@link OptionalInt} containing the index limit if present,
	 *         or empty if no limit is set
	 */
	protected OptionalInt getIndexLimit() {
		return OptionalInt.empty();
	}

	/**
	 * Returns metadata describing this computation for profiling and debugging.
	 * 
	 * <p>The metadata includes the function name and type information that
	 * can be used by profiling tools and debugging systems to identify
	 * and categorize this computation.
	 * 
	 * @return {@link OperationMetadata} containing function name and type "Repeated"
	 */
	@Override
	public OperationMetadata getMetadata() {
		OperationMetadata metadata = super.getMetadata();
		if (metadata == null)
			metadata = new OperationMetadata(getFunctionName(), "Repeated");
		return metadata;
	}

	/**
	 * Generates the execution scope for this repeated computation.
	 * 
	 * <p>This method creates a {@link Repeated} scope that contains the initialization
	 * and iteration logic. The scope includes:
	 * <ul>
	 *   <li>Initialization statements from {@link #getInitialValue}</li>
	 *   <li>A condition check for continuation from {@link #getCondition}</li>
	 *   <li>The main computation body from {@link #getExpression(TraversableExpression[], Expression, Expression)}</li>
	 * </ul>
	 * 
	 * <p>The generated scope will be compiled into kernel code for execution
	 * on the target hardware platform.
	 * 
	 * @param context The {@link KernelStructureContext} providing information about
	 *                the kernel structure and execution environment
	 * @return A {@link Scope} containing the complete repeated computation logic
	 */
	@Override
	public Scope<PackedCollection> getScope(KernelStructureContext context) {
		Repeated<PackedCollection> scope = new Repeated<>(getFunctionName(), getMetadata());
		scope.setInterval(e(getMemLength()));

		String i = getVariablePrefix() + "_i";
		scope.setIndex(new Variable<>(i));

		OptionalInt limit = getIndexLimit();
		DefaultIndex ref = limit.isPresent() ? new DefaultIndex(i, limit.getAsInt()) : new DefaultIndex(i);
		scope.setCondition(getCondition(getTraversableArguments(ref), ref));

		Expression index = new KernelIndex(context).divide(e(getShape().getSize())).multiply(e(getShape().getSize()));

		TraversableExpression[] initialArgs = getTraversableArguments(index);
		for (int j = 0; j < getMemLength(); j++) {
			Expression<?> val = getInitialValue(initialArgs, ref.add(j));
			if (val == null) break;

			Expression<?> out = getDestination(new KernelIndex(context), e(0), e(j));
			scope.getStatements().add(out.assign(val));
		}

		OperationMetadata bodyMetadata = new OperationMetadata
				(getFunctionName() + "_body",
				"Repeated (Body)");

		Scope<PackedCollection> body = new Scope<>(getFunctionName() + "_body", bodyMetadata);
		for (int j = 0; j < getMemLength(); j++) {
			Expression<?> out = getDestination(new KernelIndex(context), ref, e(j));
			Expression<?> val = getExpression(index, ref.add(j));
			body.getStatements().add(out.assign(val));
		}

		scope.add(body);
		return scope;
	}

	/**
	 * Returns the value each output element holds before the first iteration.
	 *
	 * @param args  the traversable arguments, positioned at the start of the output block
	 * @param index the position within the output block
	 * @return the initial value, or {@code null} if the computation does not initialize its
	 *         output before the loop
	 */
	protected abstract Expression<?> getInitialValue(TraversableExpression[] args, Expression index);

	/**
	 * Returns the boolean expression that keeps the loop running.
	 *
	 * @param args  the traversable arguments
	 * @param index the loop index
	 * @return the continuation condition
	 */
	protected abstract Expression<Boolean> getCondition(TraversableExpression[] args, Expression index);

	/**
	 * Gets the expression for the computation at specified indices.
	 * 
	 * <p>This method provides a convenient overload that automatically obtains
	 * the traversable arguments for the given global index before calling the
	 * main expression function.
	 * 
	 * @param globalIndex The global index in the overall computation space
	 * @param localIndex The local index within the current iteration
	 * @return The computed {@link Expression} result
	 * 
	 * @see #getExpression(TraversableExpression[], Expression, Expression)
	 */
	protected Expression<?> getExpression(Expression globalIndex, Expression localIndex) {
		return getExpression(getTraversableArguments(globalIndex), globalIndex, localIndex);
	}

	/**
	 * Returns the expression for one iteration of the loop: the next value of the output
	 * element at the given position.
	 * 
	 * @param args Array of {@link TraversableExpression}s, where {@code args[0]} is the output
	 *             and the remainder are the inputs
	 * @param globalIndex The global index in the overall computation space
	 * @param localIndex The index of the iteration
	 * @return The computed {@link Expression} result
	 */
	protected abstract Expression<?> getExpression(TraversableExpression[] args, Expression globalIndex, Expression localIndex);

	/**
	 * Gets the destination expression for writing computation results.
	 * 
	 * <p>This method determines where to write the computation result based on
	 * the global index, local index, and offset. It handles both kernel-indexed
	 * and regular array variable references.
	 * 
	 * @param globalIndex The global index, potentially a {@link KernelIndex}
	 * @param localIndex The local index within the iteration  
	 * @param offset The offset within the memory block
	 * @return An {@link Expression} representing the destination memory location
	 */
	protected Expression<?> getDestination(Expression<?> globalIndex, Expression<?> localIndex, Expression<?> offset)	{
		Expression k = globalIndex instanceof KernelIndex ? globalIndex : new KernelIndex();
		ArrayVariable<?> out = getOutputVariable();
		return out.reference(k.multiply(out.length()).add(offset));
	}

	/**
	 * Generates a new instance of this computation with updated child processes.
	 * 
	 * <p>This method is used by the computation framework to create new instances
	 * when the computation graph is modified or optimized. The new instance
	 * maintains the same configuration
	 * but uses the provided child processes as inputs.
	 * 
	 * @param children List of {@link Process} instances to use as inputs for the new computation
	 * @return A new {@link RepeatedProducerComputation} instance of the same class with the
	 *         same configuration but updated inputs
	 */
	@Override
	public abstract RepeatedProducerComputation generate(List<Process<?, ?>> children);

	/**
	 * Extends the standard computation signature with the concrete class of this
	 * computation. The behaviour of every instance is fixed by its class, but the parent
	 * signature captures only the name, inputs, and shape. Appending the class ensures that
	 * two repeated computations which share a name, shape, and inputs but are different
	 * classes — and therefore generate different loop bodies — produce different signatures
	 * rather than sharing a cached kernel through the signature-keyed instruction cache.
	 * {@code getClass().getName()} is dynamic, so every concrete subclass contributes its own
	 * name. Subclasses whose behaviour is further parameterised (for example by a configuration
	 * flag) extend this with that state.
	 *
	 * @return the signature including the concrete class, or null when the parent signature
	 *         is unavailable
	 */
	@Override
	public String signature() {
		String signature = super.signature();
		if (signature == null) return null;
		return signature + "{" + getClass().getName() + "}";
	}
}
