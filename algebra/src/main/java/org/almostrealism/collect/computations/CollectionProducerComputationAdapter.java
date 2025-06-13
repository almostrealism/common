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

package org.almostrealism.collect.computations;

import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.compute.Process;
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Computable;
import io.almostrealism.relation.Countable;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.expression.Expression;
import io.almostrealism.scope.Scope;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.scope.ScopeSettings;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.hardware.MemoryDataComputation;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Abstract adapter class that bridges the gap between collection-based computations and traversable expressions
 * in the Almost Realism framework. This class serves as a fundamental building block for implementing
 * computations that operate on {@link PackedCollection}s while providing the flexibility of traversable
 * expression evaluation patterns.
 * 
 * <p>The {@code CollectionProducerComputationAdapter} extends {@link CollectionProducerComputationBase}
 * to provide collection computation capabilities while implementing {@link TraversableExpression} to
 * enable element-wise access and mathematical operations. This dual nature makes it ideal for implementing
 * mathematical operations that need both efficient bulk computation and fine-grained element access.</p>
 * 
 * <h2>Key Features</h2>
 * <ul>
 *   <li><strong>Relative Output Support:</strong> Configurable output positioning strategy for flexible
 *       kernel compilation and argument handling</li>
 *   <li><strong>Adaptive Memory Management:</strong> Intelligent memory length calculation based on
 *       output strategy and kernel structure requirements</li>
 *   <li><strong>Kernel Structure Integration:</strong> Deep integration with kernel compilation systems
 *       for optimized hardware acceleration</li>
 *   <li><strong>Delta Computation:</strong> Built-in support for automatic differentiation and
 *       gradient calculations</li>
 *   <li><strong>Scope Generation:</strong> Automatic generation of computation scopes with proper
 *       statement count management</li>
 * </ul>
 * 
 * <h2>Relative vs Absolute Output</h2>
 * <p>One of the most important concepts in this class is the distinction between relative and absolute
 * output positioning:</p>
 * 
 * <ul>
 *   <li><strong>Relative Output ({@link #isOutputRelative()} = true):</strong> Uses variable-sized
 *       positioning that adapts to different argument sizes. This provides greater flexibility but
 *       requires the parallelism to match the traversal policy exactly.</li>
 *   <li><strong>Absolute Output ({@link #isOutputRelative()} = false):</strong> Uses fixed positioning
 *       with single-element operations. This is more predictable but less flexible for varying
 *       argument sizes.</li>
 * </ul>
 * 
 * <h2>Usage Examples</h2>
 * 
 * <h3>Basic Element-wise Operation</h3>
 * <pre>{@code
 * // Example implementation for element-wise addition
 * public class AdditionComputation extends CollectionProducerComputationAdapter<PackedCollection<?>, PackedCollection<?>> {
 *     public AdditionComputation(TraversalPolicy shape, 
 *                               Supplier<Evaluable<? extends PackedCollection<?>>> a,
 *                               Supplier<Evaluable<? extends PackedCollection<?>>> b) {
 *         super("addition", shape, a, b);
 *     }
 *     
 *     @Override
 *     public Expression<Double> getValueAt(Expression<?> index) {
 *         TraversableExpression[] args = getTraversableArguments(index);
 *         return args[0].getValueAt(index).add(args[1].getValueAt(index));
 *     }
 * }
 * 
 * // Usage
 * TraversalPolicy shape = new TraversalPolicy(100, 50); // 100x50 matrix
 * Producer<PackedCollection<?>> sourceA = ...;
 * Producer<PackedCollection<?>> sourceB = ...;
 * AdditionComputation computation = new AdditionComputation(shape, 
 *     () -> sourceA, () -> sourceB);
 * PackedCollection<?> result = computation.get().evaluate();
 * }</pre>
 * 
 * <h3>Complex Mathematical Expression</h3>
 * <pre>{@code
 * // Example implementation for dot product computation
 * public class DotProductComputation extends CollectionProducerComputationAdapter<PackedCollection<?>, PackedCollection<?>> {
 *     public DotProductComputation(TraversalPolicy outputShape,
 *                                 Supplier<Evaluable<? extends PackedCollection<?>>> vector1,
 *                                 Supplier<Evaluable<? extends PackedCollection<?>>> vector2) {
 *         super("dot_product", outputShape, vector1, vector2);
 *     }
 *     
 *     @Override
 *     protected boolean isOutputRelative() {
 *         return false; // Single scalar output
 *     }
 *     
 *     @Override
 *     public Expression<Double> getValueAt(Expression<?> index) {
 *         TraversableExpression[] args = getTraversableArguments(index);
 *         return args[0].getValueAt(index).multiply(args[1].getValueAt(index));
 *     }
 * }
 * }</pre>
 * 
 * <h2>Integration with Automatic Differentiation</h2>
 * <p>This class provides seamless integration with automatic differentiation systems through
 * the {@link #delta(Producer)} method. The delta computation automatically handles gradient
 * calculations for the implemented mathematical operations.</p>
 * 
 * <h2>Performance Considerations</h2>
 * <ul>
 *   <li><strong>Statement Count:</strong> The {@link #getStatementCount(KernelStructureContext)} method
 *       determines compilation efficiency. Large statement counts may hit limits defined by
 *       {@link io.almostrealism.scope.ScopeSettings#maxStatements}.</li>
 *   <li><strong>Memory Layout:</strong> The relative output strategy affects memory access patterns
 *       and can impact performance on different hardware architectures.</li>
 *   <li><strong>Kernel Compatibility:</strong> Statement count must align with kernel structure
 *       requirements for optimal hardware utilization.</li>
 * </ul>
 * 
 * <h2>Thread Safety</h2>
 * <p>This class is <strong>not thread-safe</strong>. Instances should not be shared across threads
 * without external synchronization. However, the {@link Evaluable} instances returned by {@link #get()}
 * can be safely used concurrently after compilation.</p>
 * 
 * <h2>Abstract Methods</h2>
 * <p>Subclasses must implement {@link TraversableExpression#getValueAt(Expression)} to define
 * the mathematical expression that will be applied to compute output values. This method receives
 * an index expression and should return the computed value at that position.</p>
 * 
 * @param <I> Input collection type, must extend {@link PackedCollection}. Represents the type
 *           of input data that this computation can process.
 * @param <O> Output collection type, must extend {@link PackedCollection}. Represents the type
 *           of output data that this computation produces.
 * 
 * @author Michael Murray
 * @since 0.69
 * 
 * @see CollectionProducerComputationBase
 * @see TraversableExpression
 * @see TraversalPolicy
 * @see PackedCollection
 * @see io.almostrealism.kernel.KernelStructureContext
 * @see io.almostrealism.scope.ScopeSettings
 */
public abstract class CollectionProducerComputationAdapter<I extends PackedCollection<?>, O extends PackedCollection<?>>
		extends CollectionProducerComputationBase<I, O>
		implements TraversableExpression<Double> {

	/**
	 * Constructs a new CollectionProducerComputationAdapter with the specified parameters.
	 * This constructor initializes the computation with a name, output shape, and input arguments
	 * that will be used during computation execution.
	 * 
	 * <p>The constructor delegates to the parent {@link CollectionProducerComputationBase} constructor
	 * to set up the fundamental computation infrastructure, including destination management,
	 * input validation, and shape configuration.</p>
	 * 
	 * <p><strong>Example Usage:</strong></p>
	 * <pre>{@code
	 * // Create a simple element-wise computation
	 * CollectionProducerComputationAdapter<PackedCollection<?>, PackedCollection<?>> computation = 
	 *     new MyComputationImplementation(
	 *         "my_operation",                    // Operation name for debugging
	 *         new TraversalPolicy(10, 20),      // 10x20 output shape
	 *         () -> inputProducer1,             // First input supplier
	 *         () -> inputProducer2              // Second input supplier
	 *     );
	 * }</pre>
	 * 
	 * @param name A human-readable name for this computation, used in debugging, profiling,
	 *             and error messages. If null, a default name will be generated based on the
	 *             class name. Recommended to use descriptive names like "matrix_multiply" or
	 *             "element_wise_add".
	 * @param outputShape The {@link TraversalPolicy} that defines the multi-dimensional shape
	 *                   and traversal pattern of the output collection. This determines how
	 *                   the output data is structured and accessed. Must have a total size
	 *                   greater than zero.
	 * @param arguments Variable argument list of {@link Supplier}s that provide the input
	 *                 {@link Evaluable}s. Each supplier must be non-null and produce a valid
	 *                 evaluable when called. The first argument position is reserved for the
	 *                 destination buffer and is automatically managed by the parent class.
	 * 
	 * @throws IllegalArgumentException if the output shape has a total size of zero or less
	 * @throws NullPointerException if any argument supplier is null
	 * 
	 * @see CollectionProducerComputationBase#CollectionProducerComputationBase(String, TraversalPolicy, Supplier[])
	 * @see TraversalPolicy#getTotalSizeLong()
	 */
	@SafeVarargs
	public CollectionProducerComputationAdapter(String name, TraversalPolicy outputShape,
												Supplier<Evaluable<? extends I>>... arguments) {
		super(name, outputShape, arguments);
	}

	/**
	 * True if this {@link io.almostrealism.code.Computation} should generate
	 * {@link io.almostrealism.code.ExpressionAssignment} statements that use
	 * relative {@link ArrayVariable} positions, false otherwise. When relative
	 * positions are used, the compiled program can have greater flexibility in
	 * how statements behave when used with arguments of varying size. However,
	 * the drawback is that this requires the parallelism implied by the
	 * {@link TraversalPolicy} returned via {@link #getShape()} is identical
	 * to the actual parallelism of the compiled
	 * {@link io.almostrealism.code.Computation}.
	 *
	 * @see  Countable#getCount()
	 * @see  TraversalPolicy#getSize()
	 * @see  MemoryDataComputation#getMemLength()
	 * @see  ArrayVariable#referenceRelative(Expression, KernelIndex)
	 */
	protected boolean isOutputRelative() { return true; }

	/**
	 * The expected number of {@link io.almostrealism.code.ExpressionAssignment}
	 * statements that will be included in the {@link Scope} generated by this
	 * {@link io.almostrealism.code.Computation}. This will either be the size of
	 * the {@link TraversalPolicy} returned by {@link #getShape()}, or 1 if
	 * relative output is not used. Note that the resulting {@link Scope} may not
	 * always adhere to this count if the {@link KernelStructureContext} indicates
	 * that the {@link Scope} is intended to become part of a larger program that
	 * expects a different size.
	 *
	 * @see  #isOutputRelative()
	 * @see  KernelStructureContext#getKernelMaximum()
	 */
	@Override
	public int getMemLength() { return isOutputRelative() ? super.getMemLength() : 1; }

	/**
	 * Returns the total number of kernel threads that will be used for this computation.
	 * This value depends on whether the computation uses relative output positioning and
	 * determines the parallelization strategy for kernel execution.
	 * 
	 * <p>The calculation follows this logic:</p>
	 * <ul>
	 *   <li><strong>Relative Output ({@link #isOutputRelative()} = true):</strong> Returns the
	 *       count from the parent class, which typically equals the memory length multiplied
	 *       by any batching factor.</li>
	 *   <li><strong>Absolute Output ({@link #isOutputRelative()} = false):</strong> Returns the
	 *       total size of the shape, representing the full collection size that needs to be
	 *       processed with single-element operations.</li>
	 * </ul>
	 * 
	 * <p>This count directly affects kernel launch parameters and memory allocation strategies.
	 * It should align with the expected workload distribution across available hardware resources.</p>
	 * 
	 * @return The number of kernel threads that will be used for parallel execution.
	 *         Returns 0 if the shape is null or has no elements.
	 * 
	 * @see #isOutputRelative()
	 * @see #getMemLength()
	 * @see TraversalPolicy#getTotalSizeLong()
	 * @see CollectionProducerComputationBase#getCountLong()
	 */
	@Override
	public long getCountLong() {
		return isOutputRelative() ? super.getCountLong() : getShape().getTotalSizeLong();
	}

	/**
	 * The actual number of {@link io.almostrealism.code.ExpressionAssignment}
	 * statements that will be included in the {@link Scope} generated by this
	 * {@link io.almostrealism.code.Computation}. This will either be the size of
	 * the value returned by {@link #getMemLength()}, or the size of the
	 * {@link TraversalPolicy} returned by {@link #getShape()} if the provided
	 * {@link KernelStructureContext} has a kernel maximum that is not equal to
	 * the value returned by {@link #getCountLong()}.
	 *
	 * @see  #getCountLong()
	 * @see  KernelStructureContext#getKernelMaximum()
	 */
	protected int getStatementCount(KernelStructureContext context) {
		if (context.getKernelMaximum().orElse(0) != getCountLong()) {
			return getShape().getSize();
		}

		return getMemLength();
	}

	/**
	 * Generates the computation scope containing the statements that implement this computation.
	 * This method creates the actual kernel code by generating assignment statements that
	 * populate the output collection based on the computation's mathematical expression.
	 * 
	 * <p>The scope generation process:</p>
	 * <ol>
	 *   <li>Determines the appropriate statement count based on kernel structure context</li>
	 *   <li>Chooses between relative and absolute output positioning strategies</li>
	 *   <li>Generates assignment statements for each output position</li>
	 *   <li>Configures proper index calculations for multi-dimensional access</li>
	 * </ol>
	 * 
	 * <p>The generated statements follow one of two patterns:</p>
	 * <ul>
	 *   <li><strong>Relative Output:</strong> {@code output[relative_index] = getValueAt(global_index)}</li>
	 *   <li><strong>Absolute Output:</strong> {@code output[absolute_index] = getValueAt(kernel_index)}</li>
	 * </ul>
	 * 
	 * <p>The choice between relative and absolute output affects memory access patterns,
	 * kernel performance, and compatibility with different argument sizes.</p>
	 * 
	 * @param context The kernel structure context providing information about the compilation
	 *               environment, including kernel maximums, threading constraints, and
	 *               optimization hints. This context influences statement generation strategy.
	 * @return A {@link io.almostrealism.scope.Scope} containing the generated computation statements.
	 *         The scope includes proper variable references and index calculations for the
	 *         specified kernel structure.
	 * 
	 * @see #getStatementCount(KernelStructureContext)
	 * @see #isOutputRelative()
	 * @see #getValueAt(Expression)
	 * @see io.almostrealism.scope.ArrayVariable#referenceRelative(Expression, io.almostrealism.kernel.KernelIndex)
	 * @see io.almostrealism.scope.ArrayVariable#referenceAbsolute(io.almostrealism.kernel.KernelIndex)
	 */
	@Override
	public Scope<O> getScope(KernelStructureContext context) {
		Scope<O> scope = super.getScope(context);
		ArrayVariable<Double> output = (ArrayVariable<Double>) getOutputVariable();

		int statementCount = getStatementCount(context);
		boolean relativeOutput = isOutputRelative() || statementCount != getMemLength();

		for (int i = 0; i < statementCount; i++) {
			KernelIndex kernelIndex = new KernelIndex(context);
			Expression index = kernelIndex;
			if (statementCount > 1) index = index.multiply(statementCount).add(i);

			if (relativeOutput) {
				scope.getStatements().add(output.referenceRelative(e(i), kernelIndex).assign(getValueAt(index)));
			} else {
				scope.getStatements().add(output.referenceAbsolute(kernelIndex).assign(getValueAt(index)));
			}
		}

		return scope;
	}

	/**
	 * Retrieves the computed value at the specified multi-dimensional position.
	 * This method provides a convenient interface for accessing computation results
	 * using multi-dimensional coordinates rather than linear indices.
	 * 
	 * <p>The method converts the multi-dimensional position coordinates into a linear
	 * index using the shape's indexing scheme, then delegates to {@link #getValueAt(Expression)}
	 * to perform the actual computation.</p>
	 * 
	 * <p><strong>Example Usage:</strong></p>
	 * <pre>{@code
	 * // For a 2D computation (matrix operation)
	 * Expression<Double> value = computation.getValue(e(2), e(3)); // Get value at row 2, column 3
	 * 
	 * // For a 3D computation (tensor operation)  
	 * Expression<Double> value = computation.getValue(e(1), e(2), e(3)); // Get value at position (1,2,3)
	 * }</pre>
	 * 
	 * @param pos Variable argument list of {@link Expression} objects representing the
	 *           multi-dimensional coordinates. The number of expressions should match
	 *           the dimensionality of the computation's shape. Each expression represents
	 *           a coordinate along one dimension.
	 * @return An {@link Expression} representing the computed value at the specified position.
	 *         The actual computation is performed by the {@link #getValueAt(Expression)} method
	 *         implemented by subclasses.
	 * 
	 * @see #getValueAt(Expression)
	 * @see TraversalPolicy#index(Expression...)
	 * @see TraversableExpression#getValue(Expression...)
	 */
	@Override
	public Expression<Double> getValue(Expression... pos) {
		return getValueAt(getShape().index(pos));
	}

	/**
	 * Determines whether this computation represents a diagonal matrix operation of the specified width.
	 * A computation is considered diagonal if it produces a diagonal matrix (all non-diagonal elements
	 * are zero) or if it represents a scalar value that can be treated as a diagonal matrix.
	 * 
	 * <p>This optimization check is important for linear algebra operations where diagonal matrices
	 * can be processed more efficiently than general matrices. The method provides a fast path
	 * for scalar computations (single-element shapes) that are inherently diagonal.</p>
	 * 
	 * <p><strong>Special Cases:</strong></p>
	 * <ul>
	 *   <li><strong>Scalar Values:</strong> Computations with a total size of 1 are always
	 *       considered diagonal, as they represent a constant diagonal matrix.</li>
	 *   <li><strong>General Case:</strong> Delegates to the parent interface for more complex
	 *       diagonal analysis based on the computation's mathematical properties.</li>
	 * </ul>
	 * 
	 * @param width The width of the target diagonal matrix. This parameter is used to determine
	 *             if the computation can be efficiently represented as a diagonal matrix of the
	 *             specified dimensions.
	 * @return {@code true} if this computation represents a diagonal matrix operation,
	 *         {@code false} otherwise. Always returns {@code true} for scalar computations.
	 * 
	 * @see #getDiagonalScalar(int)
	 * @see TraversableExpression#isDiagonal(int)
	 */
	@Override
	public boolean isDiagonal(int width) {
		if (getShape().getTotalSizeLong() == 1) return true;
		return TraversableExpression.super.isDiagonal(width);
	}

	/**
	 * Retrieves the scalar computation that represents the diagonal elements of this computation.
	 * This method is used in optimization scenarios where a computation can be simplified to
	 * a scalar operation applied to the diagonal of a matrix.
	 * 
	 * <p>This optimization is particularly important for operations like scalar multiplication
	 * of matrices, where the scalar value can be extracted and applied more efficiently than
	 * processing the entire matrix structure.</p>
	 * 
	 * <p><strong>Optimization Logic:</strong></p>
	 * <ul>
	 *   <li><strong>Scalar Computations:</strong> For computations with a single element,
	 *       returns this computation itself wrapped in an {@link Optional}, as the single
	 *       value represents the diagonal scalar.</li>
	 *   <li><strong>Matrix Computations:</strong> Delegates to the parent interface for
	 *       more sophisticated diagonal scalar extraction based on the computation's
	 *       mathematical structure.</li>
	 * </ul>
	 * 
	 * @param width The width of the target diagonal matrix. This parameter helps determine
	 *             the appropriate scalar representation for the diagonal elements.
	 * @return An {@link Optional} containing the {@link Computable} that represents the
	 *         diagonal scalar, or an empty Optional if no such scalar exists. For scalar
	 *         computations, returns this computation itself.
	 * 
	 * @see #isDiagonal(int)
	 * @see TraversableExpression#getDiagonalScalar(int)
	 * @see io.almostrealism.relation.Computable
	 */
	@Override
	public Optional<Computable> getDiagonalScalar(int width) {
		if (getShape().getTotalSizeLong() == 1) return Optional.of(this);
		return TraversableExpression.super.getDiagonalScalar(width);
	}

	/**
	 * Creates an isolated process for this computation if the statement count is within acceptable limits.
	 * Isolation is a performance optimization that allows computations to be compiled and executed
	 * independently, reducing compilation overhead and improving execution efficiency.
	 * 
	 * <p>This method checks whether the computation can be safely isolated by comparing the
	 * memory length (statement count) against the maximum allowed statements defined in
	 * {@link io.almostrealism.scope.ScopeSettings#maxStatements}. Computations that exceed
	 * this limit cannot be isolated and will return themselves unchanged.</p>
	 * 
	 * <p><strong>Isolation Benefits:</strong></p>
	 * <ul>
	 *   <li><strong>Compilation Efficiency:</strong> Isolated computations can be compiled
	 *       independently, reducing overall compilation time</li>
	 *   <li><strong>Memory Management:</strong> Better control over memory allocation and
	 *       deallocation for individual computation units</li>
	 *   <li><strong>Error Isolation:</strong> Failures in one computation don't affect others</li>
	 *   <li><strong>Optimization Opportunities:</strong> Specialized optimizations can be
	 *       applied to isolated computation units</li>
	 * </ul>
	 * 
	 * <p><strong>Example Usage:</strong></p>
	 * <pre>{@code
	 * CollectionProducerComputationAdapter<?, ?> computation = createComputation();
	 * Process<Process<?, ?>, Evaluable<? extends O>> isolatedProcess = computation.isolate();
	 * 
	 * if (isolatedProcess == computation) {
	 *     // Isolation failed due to size constraints
	 *     log("Computation too large for isolation, using direct execution");
	 * } else {
	 *     // Isolation successful, can use optimized execution path
	 *     Evaluable<? extends O> result = isolatedProcess.get().evaluate();
	 * }
	 * }</pre>
	 * 
	 * @return A {@link Process} that represents the isolated computation if isolation is possible,
	 *         or this computation instance if isolation fails due to size constraints. The returned
	 *         process can be executed independently of other computations.
	 * 
	 * @see #getMemLength()
	 * @see io.almostrealism.scope.ScopeSettings#maxStatements
	 * @see CollectionProducerComputationBase#isolate()
	 */
	@Override
	public Process<Process<?, ?>, Evaluable<? extends O>> isolate() {
		if (getMemLength() > ScopeSettings.maxStatements) {
			warn("Cannot isolate a ProducerComputation which would produce a Scope with "
					+ getMemLength() + " statements");
			return this;
		}

		return super.isolate();
	}

	/**
	 * Computes the derivative (delta) of this computation with respect to the specified target producer.
	 * This method implements automatic differentiation by creating a specialized delta computation
	 * that calculates gradients for the mathematical operations performed by this computation.
	 * 
	 * <p>The delta computation process follows these steps:</p>
	 * <ol>
	 *   <li>Attempts to use an optimized delta computation if available via {@link #attemptDelta(Producer)}</li>
	 *   <li>If no optimized version exists, creates a {@link TraversableDeltaComputation} that
	 *       implements the chain rule for automatic differentiation</li>
	 *   <li>Configures the delta computation with proper shape matching and expression handling</li>
	 * </ol>
	 * 
	 * <p>The resulting delta computation uses a {@link CollectionExpression} that accesses
	 * the target's gradient values at corresponding indices, enabling efficient gradient
	 * propagation through complex computation graphs.</p>
	 * 
	 * <p><strong>Automatic Differentiation Example:</strong></p>
	 * <pre>{@code
	 * // Original computation: f(x) = x^2 + 2x + 1
	 * CollectionProducerComputationAdapter<?, ?> computation = createQuadraticComputation();
	 * Producer<?> variable = createVariableProducer();
	 * 
	 * // Compute derivative: f'(x) = 2x + 2
	 * CollectionProducer<?> derivative = computation.delta(variable);
	 * 
	 * // The derivative can be evaluated to get gradient values
	 * PackedCollection<?> gradientValues = derivative.get().evaluate();
	 * }</pre>
	 * 
	 * <p><strong>Chain Rule Implementation:</strong></p>
	 * <p>The delta computation automatically handles the chain rule by creating expressions
	 * that access the target's gradient values using the same indexing scheme as the original
	 * computation. This ensures that gradients flow correctly through multi-dimensional
	 * computation graphs.</p>
	 * 
	 * @param target The {@link Producer} with respect to which the derivative should be computed.
	 *              This represents the variable in the differentiation process. The target's
	 *              shape must be compatible with the gradient computation requirements.
	 * @return A {@link CollectionProducer} that computes the derivative of this computation
	 *         with respect to the target. The returned producer has the same shape as this
	 *         computation and produces gradient values when evaluated.
	 * 
	 * @see #attemptDelta(Producer)
	 * @see TraversableDeltaComputation
	 * @see io.almostrealism.collect.CollectionExpression
	 * @see org.almostrealism.hardware.ComputerFeatures#shape(Producer)
	 */
	@Override
	public CollectionProducer<O> delta(Producer<?> target) {
		CollectionProducer<O> delta = attemptDelta(target);
		if (delta != null) return delta;

		delta = TraversableDeltaComputation.create("delta", getShape(), shape(target),
				args -> CollectionExpression.create(getShape(), idx -> args[1].getValueAt(idx)), target,
				(Supplier) this)
				.setDescription((Function<List<String>, String>) args -> "delta(" + description(args) + ")");
		return delta;
	}

	/**
	 * Converts this computation to a repeated computation adapter that can process multiple
	 * input sets in a single kernel invocation. This transformation is useful for batch
	 * processing scenarios where the same operation needs to be applied to multiple
	 * input collections simultaneously.
	 * 
	 * <p>The repeated computation adapter maintains the same mathematical operation but
	 * modifies the execution strategy to handle batched inputs efficiently. This can
	 * significantly improve performance for scenarios where many similar computations
	 * need to be performed.</p>
	 * 
	 * <p><strong>Transformation Process:</strong></p>
	 * <ol>
	 *   <li>Creates a {@link RepeatedProducerComputationAdapter} with the same shape</li>
	 *   <li>Transfers the computation logic to the repeated execution context</li>
	 *   <li>Configures input handling for batch processing (skips the first input which
	 *       is the destination buffer)</li>
	 *   <li>Establishes a dependent lifecycle relationship to ensure proper resource management</li>
	 * </ol>
	 * 
	 * <p><strong>Usage Example:</strong></p>
	 * <pre>{@code
	 * // Original computation for single operation
	 * CollectionProducerComputationAdapter<?, ?> singleComputation = createComputation();
	 * 
	 * // Convert to repeated computation for batch processing
	 * RepeatedProducerComputationAdapter<?> batchComputation = singleComputation.toRepeated();
	 * 
	 * // Now can process multiple input sets efficiently
	 * PackedCollection<?> results = batchComputation.get().evaluate(
	 *     inputBatch1, inputBatch2, inputBatch3);
	 * }</pre>
	 * 
	 * <p><strong>Performance Benefits:</strong></p>
	 * <ul>
	 *   <li><strong>Kernel Reuse:</strong> Single kernel launch handles multiple operations</li>
	 *   <li><strong>Memory Efficiency:</strong> Reduced memory allocation overhead</li>
	 *   <li><strong>Hardware Utilization:</strong> Better GPU occupancy through batching</li>
	 *   <li><strong>Compilation Efficiency:</strong> Single compilation for multiple operations</li>
	 * </ul>
	 * 
	 * @return A {@link RepeatedProducerComputationAdapter} that can efficiently process
	 *         multiple input sets using the same mathematical operation. The returned
	 *         adapter maintains a dependent lifecycle relationship with this computation
	 *         to ensure proper resource management.
	 * 
	 * @see RepeatedProducerComputationAdapter
	 * @see io.almostrealism.code.ScopeLifecycle#addDependentLifecycle(io.almostrealism.code.ScopeLifecycle)
	 */
	@Override
	public RepeatedProducerComputationAdapter<O> toRepeated() {
		RepeatedProducerComputationAdapter result = new RepeatedProducerComputationAdapter<>(getShape(), this,
				getInputs().stream().skip(1).toArray(Supplier[]::new));
		result.addDependentLifecycle(this);
		return result;
	}
}
