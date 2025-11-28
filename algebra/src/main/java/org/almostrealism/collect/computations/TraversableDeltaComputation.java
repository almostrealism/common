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

import io.almostrealism.code.ArgumentMap;
import io.almostrealism.code.ComputationBase;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.code.ScopeLifecycle;
import io.almostrealism.collect.CollectionVariable;
import io.almostrealism.expression.Expression;
import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.kernel.Index;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.compute.Process;
import io.almostrealism.compute.ProcessContext;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.AlgebraFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.hardware.HardwareFeatures;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A computation that represents the derivative (gradient) of an expression with respect to
 * a target variable in automatic differentiation.
 *
 * <p>This class extends {@link CollectionProducerComputationAdapter} to compute gradients
 * through the chain rule by evaluating the delta (derivative) of a {@link CollectionExpression}
 * with respect to a specified target {@link Producer}. It is a fundamental component of the
 * automatic differentiation system in Almost Realism.</p>
 *
 * <h2>Automatic Differentiation</h2>
 * <p>TraversableDeltaComputation implements reverse-mode automatic differentiation (backpropagation)
 * by:</p>
 * <ul>
 *   <li>Taking a forward expression and a target variable</li>
 *   <li>Computing the gradient dexpression/dtarget</li>
 *   <li>Propagating gradients through the computational graph</li>
 *   <li>Applying the chain rule automatically</li>
 * </ul>
 *
 * <h2>Mathematical Foundation</h2>
 * <p>For a composite function f(g(x)), the chain rule states:</p>
 * <pre>
 * df/dx = (df/dg) x (dg/dx)
 * </pre>
 * <p>This class computes such derivatives by recursively applying the delta operation
 * to the expression tree.</p>
 *
 * <h2>Usage in Neural Networks</h2>
 * <p>This computation is extensively used in training neural networks:</p>
 * <pre>{@code
 * // Forward pass: y = weights x input + bias
 * CollectionProducer<?> y = weights.multiply(input).add(bias);
 *
 * // Loss function: L = (y - target)^2
 * CollectionProducer<?> loss = y.subtract(target).pow(2);
 *
 * // Backward pass: compute dL/dweights
 * CollectionProducer<?> gradient = loss.delta(weights);
 * // Result: gradient for updating weights
 * }</pre>
 *
 * <h2>Optimization Features</h2>
 * <p>The class provides several optimization strategies:</p>
 * <ul>
 *   <li><strong>{@link #enableOptimization}:</strong> Enables general optimization of delta computations</li>
 *   <li><strong>{@link #enableStubOptimization}:</strong> Optimizes computations without TraversableExpression</li>
 *   <li><strong>{@link #enableAtomicScope}:</strong> Uses atomic scope for element-wise operations</li>
 *   <li><strong>{@link #enableIsolate}:</strong> Enables isolation for better parallelization</li>
 * </ul>
 *
 * <h2>Isolation Strategy</h2>
 * <p>The {@link #permitOptimization(Process)} method prevents optimization of processes that are
 * in the path between this delta computation and its target. This preserves gradient information
 * necessary for correct automatic differentiation.</p>
 *
 * <h2>Shape Handling</h2>
 * <p>The output shape of a delta computation is the concatenation of the expression shape
 * and the target shape:</p>
 * <pre>
 * output_shape = expression_shape.append(target_shape)
 * </pre>
 * <p>This represents the Jacobian matrix dimensions for the derivative.</p>
 *
 * <h2>Usage Examples</h2>
 *
 * <p><strong>Computing gradient of a simple operation:</strong></p>
 * <pre>{@code
 * CollectionProducer<?> x = v(shape(3), 0);  // Input variable
 * CollectionProducer<?> y = x.multiply(2).add(1);  // y = 2x + 1
 *
 * CollectionProducer<?> dy_dx = y.delta(x);  // Compute dy/dx = 2
 * // Result: gradient matrix with appropriate shape
 * }</pre>
 *
 * <p><strong>Factory method for creating delta computations:</strong></p>
 * <pre>{@code
 * TraversableDeltaComputation<?> delta = TraversableDeltaComputation.create(
 *     "customDelta",
 *     expressionShape,    // Shape of the expression being differentiated
 *     targetShape,        // Shape of the variable we're differentiating with respect to
 *     args -> myExpression(args),  // Expression function
 *     targetVariable,     // Variable to differentiate with respect to
 *     inputProducers      // Input arguments
 * );
 * }</pre>
 *
 * <h2>Performance Considerations</h2>
 * <ul>
 *   <li><strong>Complexity:</strong> Depends on expression complexity and chain rule depth</li>
 *   <li><strong>Memory:</strong> Output size is product of expression and target sizes (Jacobian)</li>
 *   <li><strong>Optimization:</strong> Selective isolation prevents unnecessary gradient computation</li>
 *   <li><strong>Caching:</strong> Results can be cached to avoid recomputation</li>
 * </ul>
 *
 * @see CollectionProducerComputationAdapter
 * @see CollectionExpression#delta(CollectionVariable)
 * @see org.almostrealism.calculus.DeltaFeatures
 *
 * @author Michael Murray
 */
// TODO  Should extend TraversableExpressionComputation
public class TraversableDeltaComputation
		extends CollectionProducerComputationAdapter
		implements HardwareFeatures {
	/**
	 * Enables general optimization of delta computations.
	 * When true, allows standard optimization passes to be applied.
	 * Default: {@code true}.
	 */
	public static boolean enableOptimization = true;

	/**
	 * Enables optimization for computations that don't expose TraversableExpression.
	 * When true, allows optimization of processes without Expression interfaces since
	 * no gradient information is hidden.
	 * Default: {@code false}.
	 */
	public static boolean enableStubOptimization = false;

	/**
	 * Enables atomic scope mode for element-wise delta operations.
	 * When true, uses element-wise traversal for more granular parallelization.
	 * Default: {@code false}.
	 */
	public static boolean enableAtomicScope = false;

	/**
	 * Enables isolation of delta computations for better parallelization.
	 * When true, delta computations are marked as isolation targets.
	 * Default: {@code false}.
	 */
	public static boolean enableIsolate = false;

	/**
	 * The expression function that computes the forward pass values.
	 * This is differentiated to produce the gradient.
	 */
	private Function<TraversableExpression[], CollectionExpression> expression;

	/**
	 * The target variable with respect to which we're computing the derivative.
	 * This is the variable whose gradient we want to find.
	 */
	private Producer<?> target;

	/**
	 * The collection variable representation of the target, used during gradient computation.
	 * Initialized during scope preparation.
	 */
	private CollectionVariable<?> targetVariable;

	/**
	 * Constructs a delta computation for automatic differentiation.
	 *
	 * <p>This constructor initializes a gradient computation that will calculate the derivative
	 * of the provided expression with respect to the specified target variable. The resulting
	 * computation can be used in backpropagation for training neural networks or in any
	 * context requiring automatic differentiation.</p>
	 *
	 * <p>The output shape is determined by the provided {@code shape} parameter, which should
	 * typically be the concatenation of the expression's shape and the target's shape to
	 * accommodate the Jacobian matrix dimensions.</p>
	 *
	 * @param name The operation identifier for this delta computation
	 * @param shape The {@link TraversalPolicy} defining output shape (typically expression_shape.append(target_shape))
	 * @param expression Function that computes the forward expression to be differentiated
	 * @param target The {@link Producer} representing the variable to differentiate with respect to
	 * @param args Input {@link Producer}s that provide data to the expression
	 */
	@SafeVarargs
	protected TraversableDeltaComputation(String name, TraversalPolicy shape,
										  Function<TraversableExpression[], CollectionExpression> expression,
										  Producer<?> target,
										  Producer<PackedCollection>... args) {
		super(name, shape, validateArgs(args));
		this.expression = expression;
		this.target = target;
		if (target instanceof ScopeLifecycle) addDependentLifecycle((ScopeLifecycle) target);
	}

	/**
	 * Returns the memory length for each computation iteration.
	 *
	 * <p>When {@link #enableAtomicScope} is true, this returns 1 to enable element-wise
	 * parallelization of the delta computation. Otherwise, delegates to the parent
	 * implementation for standard traversal memory requirements.</p>
	 *
	 * @return 1 if atomic scope is enabled, otherwise the standard memory length
	 */
	@Override
	public int getMemLength() { return enableAtomicScope ? 1 : super.getMemLength(); }

	/**
	 * Returns the total number of computation iterations required.
	 *
	 * <p>When {@link #enableAtomicScope} is true, this returns the count for element-wise
	 * traversal to match the atomic memory length. Otherwise, uses the standard count
	 * from the parent class.</p>
	 *
	 * @return Element-wise count if atomic scope is enabled, otherwise standard count
	 */
	@Override
	public long getCountLong() {
		return enableAtomicScope ? getShape().traverseEach().getCountLong() : super.getCountLong();
	}

	/**
	 * Prepares the argument mapping for kernel compilation.
	 *
	 * <p>Delegates to the parent implementation to prepare standard argument mappings
	 * for the input producers.</p>
	 *
	 * @param map The {@link ArgumentMap} for mapping arguments to kernel parameters
	 */
	@Override
	public void prepareArguments(ArgumentMap map) {
		super.prepareArguments(map);
	}

	/**
	 * Prepares the computation scope for kernel compilation.
	 *
	 * <p>In addition to standard scope preparation, this method initializes the
	 * {@link #targetVariable} by retrieving the {@link CollectionVariable} representation
	 * of the target {@link Producer}. This variable is used during gradient computation
	 * to identify which variable we're differentiating with respect to.</p>
	 *
	 * @param manager The {@link ScopeInputManager} for managing scope inputs
	 * @param context The {@link KernelStructureContext} providing kernel compilation context
	 */
	@Override
	public void prepareScope(ScopeInputManager manager, KernelStructureContext context) {
		super.prepareScope(manager, context);
		targetVariable = (CollectionVariable<?>) manager.argumentForInput(getNameProvider()).apply((Supplier) target);
	}

	/**
	 * Resets the computation arguments after kernel execution.
	 *
	 * <p>Clears the {@link #targetVariable} reference in addition to performing standard
	 * argument cleanup. This ensures proper resource management and prevents stale
	 * references between kernel invocations.</p>
	 */
	@Override
	public void resetArguments() {
		super.resetArguments();
		targetVariable = null;
	}

	/**
	 * Generates the delta expression for computing the gradient at a specific index.
	 *
	 * <p>This method applies the chain rule by calling {@code delta(targetVariable)} on the
	 * forward expression. The resulting {@link CollectionExpression} represents dexpression/dtarget
	 * at the specified index position.</p>
	 *
	 * <p>If this computation has a fixed count, the total shape is set on the expression
	 * to enable shape-based optimizations during compilation.</p>
	 *
	 * @param index The {@link Expression} representing the current index position
	 * @return A {@link CollectionExpression} computing the gradient at the given index
	 */
	protected CollectionExpression getExpression(Expression index) {
		CollectionExpression exp = expression.apply(getTraversableArguments(index)).delta(targetVariable);
		if (isFixedCount()) exp.setTotalShape(getShape());
		return exp;
	}

	/**
	 * Determines whether a process in the computational graph is permitted to be optimized.
	 *
	 * <p>This method implements the isolation strategy for preserving gradient information.
	 * Processes that lie on the path between this delta computation and its target must NOT
	 * be optimized, as optimization might eliminate gradient information necessary for
	 * correct automatic differentiation.</p>
	 *
	 * <p><strong>Optimization Rules:</strong></p>
	 * <ul>
	 *   <li>If {@link #enableStubOptimization} is true and the process does not implement
	 *       {@link TraversableExpression}, optimization is permitted (no gradient info to hide)</li>
	 *   <li>If the process is on the path from this computation to the target, optimization
	 *       is NOT permitted (must preserve gradient flow)</li>
	 *   <li>Otherwise, optimization is permitted</li>
	 * </ul>
	 *
	 * @param process The {@link Process} being considered for optimization
	 * @return {@code true} if optimization is permitted, {@code false} if it must be prevented
	 */
	protected boolean permitOptimization(Process<Process<?, ?>, Evaluable<? extends PackedCollection>> process) {
		if (enableStubOptimization && !(process instanceof TraversableExpression)) {
			// There is no harm in optimizing a process which will not reveal an Expression
			// because there is no information being hidden from the delta Expression due
			// to isolation if there is no Expression in the first place
			return true;
		}

		return !AlgebraFeatures.matchingInputs(this, target).contains(process);
	}

	/**
	 * Optimizes a child process within the computational graph.
	 *
	 * <p>This method checks whether the given process is on the gradient path using
	 * {@link #permitOptimization(Process)}. If optimization is not permitted (because the
	 * process is on the path to the target), the process is returned unchanged to preserve
	 * gradient information. Otherwise, standard optimization is applied.</p>
	 *
	 * @param ctx The {@link ProcessContext} providing optimization context
	 * @param process The child {@link Process} to potentially optimize
	 * @return The optimized process, or the original process if optimization is not permitted
	 */
	@Override
	public Process<Process<?, ?>, Evaluable<? extends PackedCollection>> optimize(ProcessContext ctx, Process<Process<?, ?>, Evaluable<? extends PackedCollection>> process) {
		if (!permitOptimization(process))
			return process;
		return super.optimize(ctx, process);
	}

	/**
	 * Isolates a child process for separate execution.
	 *
	 * <p>Similar to {@link #optimize(ProcessContext, Process)}, this method prevents isolation
	 * of processes on the gradient path. Isolation of such processes could break the gradient
	 * flow necessary for correct automatic differentiation.</p>
	 *
	 * @param process The child {@link Process} to potentially isolate
	 * @return The isolated process, or the original process if isolation is not permitted
	 */
	@Override
	public Process<Process<?, ?>, Evaluable<? extends PackedCollection>> isolate(Process<Process<?, ?>, Evaluable<? extends PackedCollection>> process) {
		if (!permitOptimization(process)) return process;
		return super.isolate(process);
	}

	/**
	 * Determines whether this delta computation should be treated as an isolation target.
	 *
	 * <p>Returns {@code true} if {@link #enableIsolate} is set, allowing this computation
	 * to be isolated for better parallelization. Otherwise, delegates to the parent
	 * implementation.</p>
	 *
	 * @param context The {@link ProcessContext} providing execution context
	 * @return {@code true} if this should be isolated, {@code false} otherwise
	 */
	@Override
	public boolean isIsolationTarget(ProcessContext context) {
		return enableIsolate || super.isIsolationTarget(context);
	}

	/**
	 * Optimizes this entire delta computation.
	 *
	 * <p>If {@link #enableOptimization} is {@code false}, returns this computation unchanged.
	 * Otherwise, applies standard optimization passes from the parent class.</p>
	 *
	 * @param ctx The {@link ProcessContext} providing optimization context
	 * @return The optimized computation, or this computation if optimization is disabled
	 */
	@Override
	public ComputationBase<PackedCollection, PackedCollection, Evaluable<? extends PackedCollection>> optimize(ProcessContext ctx) {
		if (!enableOptimization) return this;
		return super.optimize(ctx);
	}

	/**
	 * Generates a new delta computation with the specified child processes.
	 *
	 * <p>This method creates a new instance of {@link TraversableDeltaComputation} using the
	 * updated child processes while preserving the configuration of this computation (name,
	 * shape, expression, target, postprocessor, short-circuit, and dependent lifecycles).</p>
	 *
	 * <p>The child list is expected to start with the output destination at index 0, followed
	 * by the input producers. This method skips the first element and uses the remaining
	 * children as the new input arguments.</p>
	 *
	 * @param children List of child {@link Process} instances (first is output, rest are inputs)
	 * @return A new {@link TraversableDeltaComputation} with the specified children
	 */
	@Override
	public TraversableDeltaComputation generate(List<Process<?, ?>> children) {
		TraversableDeltaComputation result =
				(TraversableDeltaComputation) new TraversableDeltaComputation(getName(), getShape(), expression, target,
					children.stream().skip(1).toArray(Producer[]::new))
					.setPostprocessor(getPostprocessor()).setShortCircuit(getShortCircuit());
		getDependentLifecycles().forEach(result::addDependentLifecycle);
		return result;
	}

	/**
	 * Gets the gradient value at the specified position coordinates.
	 *
	 * <p>Converts the multi-dimensional position to a linear index using the shape's
	 * traversal policy, then delegates to {@link #getValueAt(Expression)}.</p>
	 *
	 * @param pos Variable number of {@link Expression}s representing position coordinates
	 * @return An {@link Expression} representing the gradient value at the position
	 */
	@Override
	public Expression<Double> getValue(Expression... pos) { return getValueAt(getShape().index(pos)); }

	/**
	 * Gets the gradient value at the specified linear index.
	 *
	 * <p>Generates the delta expression for the given index and retrieves the value at
	 * that index. This implements the core gradient computation for a single element.</p>
	 *
	 * @param index The {@link Expression} representing the linear index position
	 * @return An {@link Expression} representing the gradient value at the index
	 */
	@Override
	public Expression getValueAt(Expression index) {
		return getExpression(index).getValueAt(index);
	}

	/**
	 * Gets the gradient value at a position relative to the current output location.
	 *
	 * <p>Uses a zero index for the expression (representing the base position) and retrieves
	 * the value at the specified relative offset. This is used when the actual position
	 * is determined by the output buffer location rather than absolute indexing.</p>
	 *
	 * @param index The {@link Expression} representing the relative offset from output position
	 * @return An {@link Expression} representing the gradient value at the relative position
	 */
	@Override
	public Expression<Double> getValueRelative(Expression index) {
		return getExpression(new IntegerConstant(0)).getValueRelative(index);
	}

	/**
	 * Computes the unique non-zero offset for sparse gradient access.
	 *
	 * <p>For sparse gradients where only specific elements are non-zero, this method computes
	 * the offset to the single non-zero element at the given target index. This enables
	 * optimization of gradient access patterns in sparse automatic differentiation.</p>
	 *
	 * @param globalIndex The global {@link Index} for the overall computation
	 * @param localIndex The local {@link Index} within the current scope
	 * @param targetIndex The {@link Expression} representing the target element position
	 * @return An {@link Expression} representing the offset to the unique non-zero element,
	 *         or null if multiple elements are non-zero
	 */
	@Override
	public Expression uniqueNonZeroOffset(Index globalIndex, Index localIndex, Expression<?> targetIndex) {
		return getExpression(targetIndex).uniqueNonZeroOffset(globalIndex, localIndex, targetIndex);
	}

	/**
	 * Computes the gradient of this delta computation with respect to another target.
	 *
	 * <p>Computing the delta of a delta (second-order derivatives/Hessian) is not currently
	 * supported by this implementation.</p>
	 *
	 * @param target The {@link Producer} to differentiate with respect to
	 * @return Never returns (always throws exception)
	 * @throws UnsupportedOperationException Always thrown - second-order derivatives not supported
	 */
	@Override
	public CollectionProducer delta(Producer<?> target) {
		throw new UnsupportedOperationException();
	}

	/**
	 * Factory method for creating a delta computation with automatic shape configuration.
	 *
	 * <p>This static factory method simplifies the creation of {@link TraversableDeltaComputation}
	 * instances by automatically computing the output shape as the concatenation of the
	 * expression shape and target shape. This matches the Jacobian matrix dimensions for
	 * the gradient: each element of the expression has a derivative with respect to each
	 * element of the target.</p>
	 *
	 * <p><strong>Shape Calculation:</strong></p>
	 * <pre>
	 * output_shape = deltaShape.append(targetShape)
	 * </pre>
	 *
	 * <p><strong>Example:</strong></p>
	 * <pre>{@code
	 * // Expression shape: [3, 4], Target shape: [4, 5]
	 * // Resulting gradient shape: [3, 4, 4, 5] (Jacobian)
	 * TraversableDeltaComputation<?> delta = TraversableDeltaComputation.create(
	 *     "myGradient",
	 *     shape(3, 4),           // Expression shape
	 *     shape(4, 5),           // Target shape
	 *     args -> myExpression(args),
	 *     targetVariable,
	 *     inputProducers
	 * );
	 * }</pre>
	 *
	 * @param name The operation identifier for this delta computation
	 * @param deltaShape The {@link TraversalPolicy} defining the shape of the expression being differentiated
	 * @param targetShape The {@link TraversalPolicy} defining the shape of the target variable
	 * @param expression Function that computes the forward expression to be differentiated
	 * @param target The {@link Producer} representing the variable to differentiate with respect to
	 * @param args Input {@link Producer}s that provide data to the expression
	 * @return A new {@link TraversableDeltaComputation} configured for gradient computation
	 */
	public static TraversableDeltaComputation create(
			String name, TraversalPolicy deltaShape, TraversalPolicy targetShape,
			Function<TraversableExpression[], CollectionExpression> expression,
			Producer<?> target,
			Producer<PackedCollection>... args) {
		return new TraversableDeltaComputation(name, deltaShape.append(targetShape), expression, target, args);
	}
}
