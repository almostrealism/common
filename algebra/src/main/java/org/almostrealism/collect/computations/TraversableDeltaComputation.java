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
 * A specialized computation class that implements automatic differentiation (gradient computation)
 * for traversable expressions operating on multi-dimensional collections. This class enables
 * the calculation of partial derivatives of complex mathematical expressions with respect to
 * a target {@link Producer}, forming the foundation for gradient-based optimization and
 * machine learning algorithms.
 * 
 * <p>{@code TraversableDeltaComputation} extends {@link CollectionProducerComputationAdapter}
 * to provide automatic differentiation capabilities for operations on {@link PackedCollection}
 * objects. It computes the gradient (partial derivatives) of an expression function with respect
 * to a specified target producer, enabling efficient backpropagation and optimization workflows.</p>
 * 
 * <h2>Mathematical Background</h2>
 * <p>The computation implements automatic differentiation using the chain rule of calculus.
 * Given an expression f(x) and a target variable x, it computes ∂f/∂x by applying symbolic
 * differentiation rules to the expression tree. The result is a new computation that produces
 * the gradient of the original expression.</p>
 * 
 * <h2>Key Features</h2>
 * <ul>
 *   <li><strong>Automatic Differentiation:</strong> Computes gradients automatically using
 *       symbolic differentiation rules applied to expression trees</li>
 *   <li><strong>Multi-dimensional Support:</strong> Handles complex tensor operations and
 *       multi-dimensional array computations</li>
 *   <li><strong>Optimization Control:</strong> Configurable optimization and isolation strategies
 *       for performance tuning</li>
 *   <li><strong>Parallel Processing:</strong> Leverages traversable expressions for efficient
 *       parallel computation on hardware accelerators</li>
 *   <li><strong>Memory Management:</strong> Intelligent memory allocation strategies with
 *       optional atomic scope support</li>
 * </ul>
 * 
 * <h2>Usage Examples</h2>
 * 
 * <h3>Basic Polynomial Differentiation</h3>
 * <pre>{@code
 * // Compute derivative of f(x) = x^2 + 3x + 1
 * CollectionProducer<PackedCollection<?>> x = x(); // input variable
 * CollectionProducer<PackedCollection<?>> f = x.sq().add(x.mul(3)).add(1);
 * 
 * // Compute df/dx = 2x + 3
 * CollectionProducer<PackedCollection<?>> gradient = f.delta(x);
 * PackedCollection<?> result = gradient.evaluate(pack(1, 2, 3, 4, 5).traverseEach());
 * // Result: [5, 7, 9, 11, 13] (2*x + 3 for each input value)
 * }</pre>
 * 
 * <h3>Matrix Operations</h3>
 * <pre>{@code
 * // Compute gradient of matrix multiplication: f(W) = W * x
 * PackedCollection<?> weights = new PackedCollection<>(shape(3, 3)).randFill();
 * PackedCollection<?> input = pack(1, 2, 3);
 * 
 * CollectionProducer<PackedCollection<?>> W = cp(weights);
 * CollectionProducer<PackedCollection<?>> x = cp(input);
 * CollectionProducer<PackedCollection<?>> f = W.mul(x);
 * 
 * // Compute df/dW - gradient with respect to weights
 * CollectionProducer<PackedCollection<?>> weightGradient = f.delta(W);
 * }</pre>
 * 
 * <h3>Complex Mathematical Expressions</h3>
 * <pre>{@code
 * // Gradient of normalization: f(x) = (x - mean(x)) / sqrt(variance(x) + eps)
 * CollectionProducer<PackedCollection<?>> input = x(10);
 * double eps = 1e-5;
 * 
 * CollectionProducer<PackedCollection<?>> normalized = input.subtractMean()
 *     .divide(input.variance().add(c(eps)).sqrt());
 * 
 * // Compute gradient for backpropagation
 * CollectionProducer<PackedCollection<?>> gradient = normalized.delta(input);
 * }</pre>
 * 
 * <h2>Performance Considerations</h2>
 * <ul>
 *   <li><strong>Optimization:</strong> Use {@link #enableOptimization} to control computational
 *       graph optimization. Disable for debugging or when precise expression structure is needed.</li>
 *   <li><strong>Isolation:</strong> Configure {@link #enableIsolate} to control process isolation
 *       strategies for better parallel performance.</li>
 *   <li><strong>Memory:</strong> Enable {@link #enableAtomicScope} for reduced memory usage when
 *       processing large tensors with atomic operations.</li>
 *   <li><strong>Stub Optimization:</strong> Use {@link #enableStubOptimization} for optimizing
 *       non-expression processes in the computation graph.</li>
 * </ul>
 * 
 * <h2>Configuration Options</h2>
 * <p>The class provides several static configuration flags that affect behavior globally:</p>
 * <ul>
 *   <li>{@link #enableOptimization} - Controls general optimization strategies</li>
 *   <li>{@link #enableStubOptimization} - Enables optimization of non-expression processes</li>
 *   <li>{@link #enableAtomicScope} - Reduces memory footprint for atomic operations</li>
 *   <li>{@link #enableIsolate} - Controls process isolation for performance</li>
 * </ul>
 * 
 * @param <T> the type of {@link PackedCollection} this computation operates on
 * 
 * @see CollectionProducer#delta(Producer)
 * @see TraversableExpression
 * @see CollectionExpression#delta(io.almostrealism.collect.CollectionVariable)
 * @see PackedCollection
 * 
 * @author Michael Murray
 */
// TODO  Should extend TraversableExpressionComputation
public class TraversableDeltaComputation<T extends PackedCollection<?>>
		extends CollectionProducerComputationAdapter<T, T>
		implements HardwareFeatures {
	
	/**
	 * Global flag controlling whether optimization strategies are applied to delta computations.
	 * When enabled, the computation may apply various optimization techniques to improve performance,
	 * including expression simplification and computational graph optimization.
	 * 
	 * <p>Disable this flag when precise control over the computation structure is needed for
	 * debugging or when specific expression patterns must be preserved.</p>
	 * 
	 * @see #optimize(io.almostrealism.compute.ProcessContext)
	 */
	public static boolean enableOptimization = true;
	
	/**
	 * Controls optimization of non-expression processes in the computation graph.
	 * When enabled, processes that do not implement {@link TraversableExpression} can be
	 * optimized without affecting the delta computation's ability to track gradients.
	 * 
	 * <p>This is safe because non-expression processes do not expose internal mathematical
	 * structures that could interfere with gradient computation.</p>
	 * 
	 * @see #permitOptimization(io.almostrealism.compute.Process)
	 */
	public static boolean enableStubOptimization = false;
	
	/**
	 * Enables atomic scope mode for reduced memory usage during computation.
	 * When enabled, each computation uses minimal memory allocation (1 unit) instead of
	 * the full shape size, which is beneficial for large tensor operations where
	 * atomic memory access patterns are sufficient.
	 * 
	 * <p>This mode affects both {@link #getMemLength()} and {@link #getCountLong()} calculations,
	 * trading memory efficiency for potential performance considerations.</p>
	 * 
	 * @see #getMemLength()
	 * @see #getCountLong()
	 */
	public static boolean enableAtomicScope = false;
	
	/**
	 * Controls whether delta computations are treated as isolation targets for
	 * parallel processing optimization. When enabled, the computation may be
	 * isolated from other processes to improve parallel execution characteristics.
	 * 
	 * @see #isIsolationTarget(io.almostrealism.compute.ProcessContext)
	 */
	public static boolean enableIsolate = false;

	
	/**
	 * The mathematical expression function that defines the computation to be differentiated.
	 * This function takes an array of {@link TraversableExpression} arguments and produces
	 * a {@link CollectionExpression} result. The delta operation is applied to this expression
	 * to compute gradients.
	 */
	private Function<TraversableExpression[], CollectionExpression> expression;
	
	/**
	 * The target producer with respect to which the gradient is computed.
	 * This represents the variable in the mathematical expression that we want to
	 * differentiate with respect to (e.g., the 'x' in df/dx).
	 */
	private Producer<?> target;
	
	/**
	 * Runtime variable representing the target producer within the computation scope.
	 * This is created during scope preparation and used for delta computation execution.
	 * It is reset after each computation to ensure proper cleanup.
	 */
	private CollectionVariable<?> targetVariable;

	/**
	 * Constructs a new {@code TraversableDeltaComputation} with the specified parameters.
	 * This constructor is typically called through the {@link #create} factory method rather
	 * than directly.
	 * 
	 * <p>The computation will differentiate the given expression function with respect to
	 * the target producer. The shape parameter defines the output dimensionality and
	 * traversal characteristics of the resulting gradient computation.</p>
	 * 
	 * @param name a descriptive name for this computation, used for debugging and profiling
	 * @param shape the {@link TraversalPolicy} defining the output shape and traversal pattern.
	 *              This should typically be the combination of the delta shape and target shape
	 * @param expression the mathematical expression function to differentiate. Takes traversable
	 *                   expression arguments and produces a collection expression result
	 * @param target the producer representing the variable to differentiate with respect to
	 * @param args input arguments to the computation, typically other producers or evaluables
	 * 
	 * @throws IllegalArgumentException if any parameters are invalid or incompatible
	 */
	@SafeVarargs
	protected TraversableDeltaComputation(String name, TraversalPolicy shape,
										  Function<TraversableExpression[], CollectionExpression> expression,
										  Producer<?> target,
										  Supplier<Evaluable<? extends PackedCollection<?>>>... args) {
		super(name, shape, validateArgs(args));
		this.expression = expression;
		this.target = target;
		if (target instanceof ScopeLifecycle) addDependentLifecycle((ScopeLifecycle) target);
	}

	/**
	 * Returns the memory length required for this computation.
	 * When {@link #enableAtomicScope} is true, returns 1 for minimal memory usage.
	 * Otherwise, delegates to the parent implementation for full memory allocation.
	 * 
	 * @return the memory length required for computation execution
	 */
	@Override
	public int getMemLength() { return enableAtomicScope ? 1 : super.getMemLength(); }

	/**
	 * Returns the total number of elements this computation will process.
	 * When {@link #enableAtomicScope} is true, returns the traversal count for
	 * element-wise processing. Otherwise, uses the standard count calculation.
	 * 
	 * @return the total count of elements to be processed
	 */
	@Override
	public long getCountLong() {
		return enableAtomicScope ? getShape().traverseEach().getCountLong() : super.getCountLong();
	}

	/**
	 * {@inheritDoc}
	 * 
	 * <p>Prepares the computation arguments by delegating to the parent implementation.
	 * This method is part of the standard computation lifecycle.</p>
	 */
	@Override
	public void prepareArguments(ArgumentMap map) {
		super.prepareArguments(map);
	}

	/**
	 * {@inheritDoc}
	 * 
	 * <p>Prepares the computation scope by setting up the target variable for delta computation.
	 * The target variable is created from the target producer and will be used in the
	 * {@link #getExpression(Expression)} method to compute gradients.</p>
	 * 
	 * @param manager the scope input manager for handling computation inputs
	 * @param context the kernel structure context for compilation optimization
	 */
	@Override
	public void prepareScope(ScopeInputManager manager, KernelStructureContext context) {
		super.prepareScope(manager, context);
		targetVariable = (CollectionVariable<?>) manager.argumentForInput(getNameProvider()).apply((Supplier) target);
	}

	/**
	 * {@inheritDoc}
	 * 
	 * <p>Resets computation arguments and clears the target variable to ensure proper
	 * cleanup between computation executions.</p>
	 */
	@Override
	public void resetArguments() {
		super.resetArguments();
		targetVariable = null;
	}

	/**
	 * Constructs the differential expression for gradient computation at the specified index.
	 * This is the core method that implements automatic differentiation by applying the
	 * delta operation to the mathematical expression.
	 * 
	 * <p>The method works by:</p>
	 * <ol>
	 *   <li>Obtaining traversable arguments for the given index position</li>
	 *   <li>Applying the expression function to these arguments</li>
	 *   <li>Computing the delta (gradient) with respect to the target variable</li>
	 *   <li>Setting the total shape if using fixed count mode</li>
	 * </ol>
	 * 
	 * @param index the expression representing the current computation index or position
	 * @return a {@link CollectionExpression} representing the gradient at the specified index
	 * 
	 * @see CollectionExpression#delta(io.almostrealism.collect.CollectionVariable)
	 */
	protected CollectionExpression getExpression(Expression index) {
		CollectionExpression exp = expression.apply(getTraversableArguments(index)).delta(targetVariable);
		if (isFixedCount()) exp.setTotalShape(getShape());
		return exp;
	}

	/**
	 * Determines whether a given process should be permitted for optimization.
	 * This method implements a key optimization strategy by preventing optimization
	 * of processes that are involved in the gradient computation path.
	 * 
	 * <p>The logic works as follows:</p>
	 * <ul>
	 *   <li>If {@link #enableStubOptimization} is true and the process is not a
	 *       {@link TraversableExpression}, optimization is permitted since non-expression
	 *       processes cannot hide information needed for delta computation</li>
	 *   <li>Otherwise, optimization is only permitted if the process is not part of
	 *       the input dependency chain of the target variable</li>
	 * </ul>
	 * 
	 * @param process the process to evaluate for optimization eligibility
	 * @return true if the process can be safely optimized without affecting gradient computation
	 * 
	 * @see org.almostrealism.algebra.AlgebraFeatures#matchingInputs
	 */
	protected boolean permitOptimization(Process<Process<?, ?>, Evaluable<? extends T>> process) {
		if (enableStubOptimization && !(process instanceof TraversableExpression)) {
			// There is no harm in optimizing a process which will not reveal an Expression
			// because there is no information being hidden from the delta Expression due
			// to isolation if there is no Expression in the first place
			return true;
		}

		return !AlgebraFeatures.matchingInputs(this, target).contains(process);
	}

	/**
	 * {@inheritDoc}
	 * 
	 * <p>Applies optimization to child processes while respecting gradient computation requirements.
	 * Only processes that pass the {@link #permitOptimization(Process)} check are optimized
	 * to ensure gradient information is preserved.</p>
	 * 
	 * @param ctx the process context for optimization decisions
	 * @param process the child process to potentially optimize
	 * @return the original process if optimization is not permitted, otherwise the optimized process
	 */
	@Override
	public Process<Process<?, ?>, Evaluable<? extends T>> optimize(ProcessContext ctx, Process<Process<?, ?>, Evaluable<? extends T>> process) {
		if (!permitOptimization(process))
			return process;
		return super.optimize(ctx, process);
	}

	/**
	 * {@inheritDoc}
	 * 
	 * <p>Isolates processes for parallel execution while preserving gradient computation integrity.
	 * Only processes that do not interfere with delta computation are isolated.</p>
	 * 
	 * @param process the process to potentially isolate
	 * @return the original process if isolation would interfere with gradient computation,
	 *         otherwise the isolated process
	 */
	@Override
	public Process<Process<?, ?>, Evaluable<? extends T>> isolate(Process<Process<?, ?>, Evaluable<? extends T>> process) {
		if (!permitOptimization(process)) return process;
		return super.isolate(process);
	}

	/**
	 * {@inheritDoc}
	 * 
	 * <p>Determines if this computation should be considered as an isolation target for
	 * parallel processing optimization. Returns true if {@link #enableIsolate} is set or
	 * if the parent implementation indicates isolation is beneficial.</p>
	 * 
	 * @param context the process context for isolation decisions
	 * @return true if this computation should be isolated for parallel execution
	 */
	@Override
	public boolean isIsolationTarget(ProcessContext context) {
		return enableIsolate || super.isIsolationTarget(context);
	}

	/**
	 * {@inheritDoc}
	 * 
	 * <p>Applies global optimization strategies to this computation if {@link #enableOptimization}
	 * is true. This method controls whether the entire computation graph is optimized.</p>
	 * 
	 * @param ctx the process context for optimization decisions  
	 * @return this computation if optimization is disabled, otherwise the optimized computation
	 */
	@Override
	public ComputationBase<T, T, Evaluable<? extends T>> optimize(ProcessContext ctx) {
		if (!enableOptimization) return this;
		return super.optimize(ctx);
	}

	/**
	 * {@inheritDoc}
	 * 
	 * <p>Creates a new instance of this computation with the specified child processes.
	 * This method is used during computation graph optimization and reconstruction.</p>
	 * 
	 * @param children the list of child processes to include in the new computation
	 * @return a new {@code TraversableDeltaComputation} with the specified children
	 */
	@Override
	public TraversableDeltaComputation<T> generate(List<Process<?, ?>> children) {
		TraversableDeltaComputation<T> result =
				(TraversableDeltaComputation<T>) new TraversableDeltaComputation(getName(), getShape(), expression, target,
					children.stream().skip(1).toArray(Supplier[]::new))
					.setPostprocessor(getPostprocessor()).setShortCircuit(getShortCircuit());
		getDependentLifecycles().forEach(result::addDependentLifecycle);
		return result;
	}

	/**
	 * {@inheritDoc}
	 * 
	 * <p>Retrieves the gradient value at the specified multi-dimensional position.
	 * This method converts the position coordinates to an index and delegates to
	 * {@link #getValueAt(Expression)}.</p>
	 * 
	 * @param pos the position coordinates as an array of expressions
	 * @return the gradient value at the specified position
	 */
	@Override
	public Expression<Double> getValue(Expression... pos) { return getValueAt(getShape().index(pos)); }

	/**
	 * {@inheritDoc}
	 * 
	 * <p>Computes the gradient value at the specified linear index by evaluating
	 * the differential expression at that position.</p>
	 * 
	 * @param index the linear index expression specifying the position
	 * @return the gradient value at the specified index
	 */
	@Override
	public Expression getValueAt(Expression index) {
		return getExpression(index).getValueAt(index);
	}

	/**
	 * {@inheritDoc}
	 * 
	 * <p>Computes the gradient value at a position relative to the base index.
	 * This method is useful for relative addressing in kernel computations.</p>
	 * 
	 * @param index the relative index expression
	 * @return the gradient value at the relative position
	 */
	@Override
	public Expression<Double> getValueRelative(Expression index) {
		return getExpression(new IntegerConstant(0)).getValueRelative(index);
	}

	/**
	 * {@inheritDoc}
	 * 
	 * <p>Computes a unique non-zero offset expression for optimization purposes.
	 * This method is used by the compiler to determine memory access patterns
	 * and optimize kernel generation.</p>
	 * 
	 * @param globalIndex the global index context
	 * @param localIndex the local index context  
	 * @param targetIndex the target index expression
	 * @return an expression representing the unique non-zero offset
	 */
	@Override
	public Expression uniqueNonZeroOffset(Index globalIndex, Index localIndex, Expression<?> targetIndex) {
		return getExpression(targetIndex).uniqueNonZeroOffset(globalIndex, localIndex, targetIndex);
	}

	/**
	 * {@inheritDoc}
	 * 
	 * <p>Computes a unique non-zero index relative to a local index for optimization.
	 * This is used for relative addressing optimizations in generated kernels.</p>
	 * 
	 * @param localIndex the local index context
	 * @param targetIndex the target index expression
	 * @return an expression representing the unique non-zero relative index
	 */
	@Override
	public Expression uniqueNonZeroIndexRelative(Index localIndex, Expression<?> targetIndex) {
		return getExpression(new IntegerConstant(0)).uniqueNonZeroIndexRelative(localIndex, targetIndex);
	}

	/**
	 * Attempting to compute a delta of a delta computation is not supported and will
	 * throw an {@link UnsupportedOperationException}. This prevents infinite recursion
	 * and ensures mathematical correctness, as second-order derivatives require
	 * different computational strategies.
	 * 
	 * <p>For second-order derivatives (Hessians), use specialized computations or
	 * apply the delta operation to the result of this computation rather than
	 * chaining delta operations directly.</p>
	 * 
	 * @param target the target for delta computation (ignored)
	 * @return never returns normally
	 * @throws UnsupportedOperationException always thrown to prevent nested delta operations
	 */
	@Override
	public CollectionProducer<T> delta(Producer<?> target) {
		throw new UnsupportedOperationException();
	}

	/**
	 * Creates a new {@code TraversableDeltaComputation} instance for computing gradients
	 * of mathematical expressions. This is the preferred way to construct delta computations
	 * as it properly handles shape composition and parameter validation.
	 * 
	 * <p>The factory method automatically combines the delta shape and target shape to
	 * create the appropriate output dimensionality for the gradient computation. The
	 * resulting computation will have dimensions that reflect both the structure of
	 * the original expression and the target variable being differentiated.</p>
	 * 
	 * <p><strong>Shape Composition:</strong></p>
	 * <p>The output shape is constructed by appending the target shape to the delta shape:
	 * <pre>{@code
	 * outputShape = deltaShape.append(targetShape)
	 * }</pre>
	 * This ensures that gradients are properly dimensioned for matrix operations and
	 * multi-dimensional tensor computations.</p>
	 * 
	 * <p><strong>Usage Examples:</strong></p>
	 * 
	 * <p><em>Scalar Function Gradient:</em></p>
	 * <pre>{@code
	 * // f(x) = x^2, compute df/dx
	 * TraversalPolicy deltaShape = shape(1);    // scalar output
	 * TraversalPolicy targetShape = shape(3);   // 3-element input vector
	 * 
	 * TraversableDeltaComputation<PackedCollection<?>> delta = 
	 *     TraversableDeltaComputation.create(
	 *         "square_gradient",
	 *         deltaShape, targetShape,
	 *         args -> args[0].multiply(args[0]),  // x^2
	 *         x(),                                // target variable
	 *         x()                                 // input argument
	 *     );
	 * // Result shape: [1, 3] - gradient of scalar w.r.t. 3-element vector
	 * }</pre>
	 * 
	 * <p><em>Matrix Operation Gradient:</em></p>
	 * <pre>{@code
	 * // f(W) = W * x, compute df/dW  
	 * TraversalPolicy deltaShape = shape(3);      // 3-element output vector
	 * TraversalPolicy targetShape = shape(3, 3);  // 3x3 weight matrix
	 * 
	 * TraversableDeltaComputation<PackedCollection<?>> delta = 
	 *     TraversableDeltaComputation.create(
	 *         "matmul_gradient", 
	 *         deltaShape, targetShape,
	 *         args -> args[0].multiply(args[1]),  // W * x
	 *         W(),                                // target: weight matrix
	 *         W(), x()                           // arguments: weights and input
	 *     );
	 * // Result shape: [3, 3, 3] - gradient of 3-vector w.r.t. 3x3 matrix  
	 * }</pre>
	 * 
	 * @param <T> the type of {@link PackedCollection} the computation operates on
	 * @param name a descriptive name for the computation, used for debugging and profiling
	 * @param deltaShape the {@link TraversalPolicy} defining the shape of the expression output
	 * @param targetShape the {@link TraversalPolicy} defining the shape of the target variable
	 * @param expression the mathematical expression function to differentiate. Should take an
	 *                   array of {@link TraversableExpression} arguments and return a
	 *                   {@link CollectionExpression}
	 * @param target the {@link Producer} representing the variable to differentiate with respect to
	 * @param args variable number of input arguments, typically other producers that feed into
	 *             the expression function
	 * 
	 * @return a new {@code TraversableDeltaComputation} configured for gradient computation
	 * 
	 * @throws IllegalArgumentException if shapes are incompatible or arguments are invalid
	 * @throws NullPointerException if any required parameters are null
	 * 
	 * @see TraversalPolicy#append(TraversalPolicy)
	 * @see CollectionProducer#delta(Producer)
	 */
	public static <T extends PackedCollection<?>> TraversableDeltaComputation<T> create(
			String name, TraversalPolicy deltaShape, TraversalPolicy targetShape,
			Function<TraversableExpression[], CollectionExpression> expression,
			Producer<?> target,
			Supplier<Evaluable<? extends PackedCollection<?>>>... args) {
		return new TraversableDeltaComputation<>(name, deltaShape.append(targetShape), expression, target, args);
	}
}
