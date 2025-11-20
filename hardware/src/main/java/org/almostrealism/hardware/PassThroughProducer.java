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

package org.almostrealism.hardware;

import io.almostrealism.collect.Algebraic;
import io.almostrealism.code.ProducerComputationBase;
import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.kernel.Index;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.compute.Process;
import io.almostrealism.scope.Argument;
import io.almostrealism.scope.Argument.Expectation;
import io.almostrealism.code.ArgumentMap;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.code.ProducerArgumentReference;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.expression.Expression;
import io.almostrealism.scope.Scope;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.util.DescribableParent;
import org.almostrealism.hardware.mem.MemoryDataDestinationProducer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Represents a dynamic input argument that passes through a computation without transformation,
 * enabling data to flow into hardware-accelerated kernels at evaluation time.
 *
 * <p>{@link PassThroughProducer} is a fundamental building block for creating computation graphs
 * that accept runtime inputs. Instead of baking data values into the computation structure at
 * build time, it creates a placeholder that will be filled with actual data when
 * {@link Evaluable#evaluate(Object...)} is called.</p>
 *
 * <h2>Core Concept: Dynamic vs Static Inputs</h2>
 *
 * <p><strong>Static Approach (Inefficient):</strong></p>
 * <pre>{@code
 * // BAD: Data baked into computation graph
 * PackedCollection<?> data1 = loadData1();
 * Producer<?> result1 = multiply(cp(data1), c(2.0));
 * result1.get().evaluate();  // Compiles kernel with data1
 *
 * // New data requires rebuilding and recompiling
 * PackedCollection<?> data2 = loadData2();
 * Producer<?> result2 = multiply(cp(data2), c(2.0));  // Must recompile
 * result2.get().evaluate();
 * }</pre>
 *
 * <p><strong>Dynamic Approach (Efficient with PassThroughProducer):</strong></p>
 * <pre>{@code
 * // GOOD: Computation graph accepts dynamic input
 * Producer<?> input = v(shape(1000), 0);  // Argument 0
 * Producer<?> result = multiply(input, c(2.0));
 * Evaluable<?> operation = result.get();  // Compile once
 *
 * // Reuse same compiled kernel with different data
 * operation.evaluate(data1);
 * operation.evaluate(data2);
 * operation.evaluate(data3);  // No recompilation
 * }</pre>
 *
 * <h2>Fixed vs Variable Count Behavior</h2>
 *
 * <p>The {@link PassThroughProducer}'s sizing behavior is determined by its {@link TraversalPolicy}:</p>
 *
 * <h3>Fixed-Count (Predetermined Size)</h3>
 * <p>Created with: {@code new TraversalPolicy(size)} or {@code shape(size)}</p>
 * <pre>{@code
 * // Fixed-count: expects exactly 3 elements
 * Producer<Vector> input = v(shape(3), 0);
 *
 * // Kernel size is predetermined: 3 work items
 * Producer<?> doubled = multiply(input, c(2.0));
 *
 * // MUST provide 3-element input at evaluation
 * doubled.get().evaluate(new Vector(1, 2, 3));  // OK
 * doubled.get().evaluate(new PackedCollection<>(5));  // ERROR if output size != 1 or 3
 * }</pre>
 *
 * <p><strong>Use fixed-count when:</strong></p>
 * <ul>
 *   <li>Input size is known at compile time (vectors, matrices with fixed dimensions)</li>
 *   <li>You want to enforce size constraints</li>
 *   <li>Working with geometric primitives (Vector, Pair, etc.)</li>
 * </ul>
 *
 * <h3>Variable-Count (Adaptive Size)</h3>
 * <p>Created with: {@code new TraversalPolicy(false, false, elementSize)} or {@code shape(-1, elementSize)}</p>
 * <pre>{@code
 * // Variable-count: adapts to input size
 * Producer<PackedCollection<?>> input = v(shape(-1, 100), 0);
 *
 * // Kernel size determined at runtime from output
 * Producer<?> processed = filter(input);
 *
 * // Works with any size input (multiples of 100)
 * processed.get().evaluate(new PackedCollection<>(1000));   // OK: 10 x 100
 * processed.get().evaluate(new PackedCollection<>(5000));   // OK: 50 x 100
 * }</pre>
 *
 * <p><strong>Use variable-count when:</strong></p>
 * <ul>
 *   <li>Processing variable-sized collections</li>
 *   <li>Building reusable operations for batch processing</li>
 *   <li>Input size unknown until runtime</li>
 * </ul>
 *
 * <h2>Kernel Execution Implications</h2>
 *
 * <p>When compiled to a hardware kernel, {@link PassThroughProducer} affects kernel sizing:</p>
 *
 * <h3>Fixed-Count Kernel Sizing</h3>
 * <pre>
 * For fixed-count PassThroughProducer of size N:
 * - Output size must be 1 (scalar broadcast) OR exactly N
 * - Kernel launches N work items (threads)
 * - Each work item processes one element
 *
 * Exception: If output size != 1 and != N
 *   -> IllegalArgumentException at ProcessDetailsFactory.init()
 * </pre>
 *
 * <h3>Variable-Count Kernel Sizing</h3>
 * <pre>
 * For variable-count PassThroughProducer:
 * - Kernel size determined from output at runtime
 * - More flexible but requires output size to be known
 * - Ideal for operations on collections of varying sizes
 * </pre>
 *
 * <h2>Common Usage Patterns</h2>
 *
 * <h3>Single Dynamic Input</h3>
 * <pre>{@code
 * public class DataProcessor implements HardwareFeatures {
 *     private final Evaluable<PackedCollection<?>> operation;
 *
 *     public DataProcessor() {
 *         // Build computation graph with dynamic input
 *         Producer<PackedCollection<?>> input = v(shape(-1, 1), 0);
 *         Producer<?> normalized = divide(input, max(input));
 *         this.operation = normalized.get();  // Compile once
 *     }
 *
 *     public PackedCollection<?> process(PackedCollection<?> data) {
 *         return operation.evaluate(data);  // Reuse compiled kernel
 *     }
 * }
 * }</pre>
 *
 * <h3>Multiple Dynamic Inputs</h3>
 * <pre>{@code
 * // Argument 0: first input, Argument 1: second input
 * Producer<?> a = v(shape(-1, 1), 0);
 * Producer<?> b = v(shape(-1, 1), 1);
 * Producer<?> combined = add(multiply(a, c(2.0)), multiply(b, c(3.0)));
 *
 * Evaluable<?> op = combined.get();
 * PackedCollection<?> result = op.evaluate(inputA, inputB);  // Two arguments
 * }</pre>
 *
 * <h3>Mixed Static and Dynamic</h3>
 * <pre>{@code
 * // Dynamic input, static weights
 * Producer<?> input = v(shape(-1, 784), 0);  // Variable batch size
 * Producer<?> weights = cp(loadWeights());    // Fixed weights
 * Producer<?> output = matmul(input, weights);
 *
 * // Can process any batch size without recompilation
 * output.get().evaluate(batch1);  // 10 x 784
 * output.get().evaluate(batch2);  // 100 x 784
 * }</pre>
 *
 * <h2>Argument Indexing</h2>
 *
 * <p>The {@code argIndex} parameter in constructors specifies which argument this producer
 * references:</p>
 * <pre>{@code
 * Producer<?> arg0 = v(shape(100), 0);  // First argument
 * Producer<?> arg1 = v(shape(100), 1);  // Second argument
 * Producer<?> arg2 = v(shape(100), 2);  // Third argument
 *
 * Producer<?> result = add(arg0, multiply(arg1, arg2));
 *
 * // Evaluation provides arguments in order
 * result.get().evaluate(
 *     data0,  // Fills arg0
 *     data1,  // Fills arg1
 *     data2   // Fills arg2
 * );
 * }</pre>
 *
 * <h2>Traversal and Reshaping</h2>
 *
 * <p>{@link PassThroughProducer} supports traversal axis changes and reshaping:</p>
 * <pre>{@code
 * // Create 2D input: 10 rows x 100 columns
 * Producer<?> input = v(shape(10, 100), 0);
 *
 * // Traverse along columns (axis 1)
 * Producer<?> columnView = input.traverse(1);  // Process column-wise
 *
 * // Reshape while maintaining total size
 * Producer<?> reshaped = input.reshape(shape(100, 10));  // Transpose
 * }</pre>
 *
 * <h2>Performance Considerations</h2>
 *
 * <ul>
 *   <li><strong>Compilation Cost:</strong> {@link PassThroughProducer} allows kernel compilation
 *       once and reuse many times - significant savings for repeated operations</li>
 *   <li><strong>Fixed vs Variable:</strong> Fixed-count has slightly lower overhead at runtime
 *       but variable-count offers more flexibility</li>
 *   <li><strong>Argument Passing:</strong> Passing data via evaluate() is more efficient than
 *       embedding it in the computation graph</li>
 * </ul>
 *
 * <h2>Common Pitfalls</h2>
 *
 * <h3>Size Mismatch with Fixed-Count</h3>
 * <pre>{@code
 * // WRONG: Fixed size 100, output size 200
 * Producer<?> input = v(shape(100), 0);  // Fixed: 100 elements
 * PackedCollection<?> output = new PackedCollection<>(200);
 * input.get().into(output.traverseEach()).evaluate(data);
 * // ERROR: Output size (200) doesn't match fixed count (100) or 1
 * }</pre>
 *
 * <p><strong>Solution:</strong> Use variable-count or match output size:</p>
 * <pre>{@code
 * // CORRECT: Variable-count adapts to output size
 * Producer<?> input = v(shape(-1, 1), 0);
 * PackedCollection<?> output = new PackedCollection<>(200);
 * input.get().into(output.traverseEach()).evaluate(data);  // OK
 * }</pre>
 *
 * <h3>Wrong Argument Count</h3>
 * <pre>{@code
 * Producer<?> a = v(shape(100), 0);
 * Producer<?> b = v(shape(100), 1);
 * Evaluable<?> op = add(a, b).get();
 *
 * op.evaluate(data1);  // ERROR: Missing argument 1
 * op.evaluate(data1, data2);  // OK
 * }</pre>
 *
 * <h2>Integration with Computation Graph</h2>
 *
 * <p>{@link PassThroughProducer} integrates seamlessly with the computation graph:</p>
 * <ul>
 *   <li>Appears as a leaf node in the graph (no inputs)</li>
 *   <li>Generates argument references in compiled kernels</li>
 *   <li>Can be composed with any other producers</li>
 *   <li>Supports all standard traversal operations</li>
 * </ul>
 *
 * <h2>Creation via Input Factory</h2>
 *
 * <p>Typically created via {@link Input} static methods:</p>
 * <pre>{@code
 * // Using Input.value
 * Producer<?> input = Input.value(shape(100), 0);
 *
 * // Using HardwareFeatures.v() (more common)
 * class MyOp implements HardwareFeatures {
 *     Producer<?> input = v(shape(-1, 1), 0);
 * }
 * }</pre>
 *
 * @param <T> The type of {@link MemoryData} this producer references
 *
 * @see Input#value(TraversalPolicy, int)
 * @see TraversalPolicy
 * @see Evaluable#evaluate(Object...)
 * @see ProcessDetailsFactory
 */
public class PassThroughProducer<T extends MemoryData> extends ProducerComputationBase<T, T>
		implements ProducerArgumentReference, MemoryDataComputation<T>,
					CollectionExpression<PassThroughProducer<T>>,
					DescribableParent<Process<?, ?>> {

	private TraversalPolicy shape;
	private int argIndex;

	public PassThroughProducer(TraversalPolicy shape, int argIndex) {
		this();
		this.shape = shape;
		this.argIndex = argIndex;
		init();
	}

	private PassThroughProducer() {
		this.setInputs(Arrays.asList(new MemoryDataDestinationProducer(this)));
	}

	@Override
	public TraversalPolicy getShape() { return shape; }

	@Override
	public int getReferencedArgumentIndex() { return argIndex; }

	@Override
	public int getMemLength() { return getShape().getSize(); }

	@Override
	public long getCountLong() { return getShape().getCountLong(); }

	@Override
	public boolean isFixedCount() { return getShape().isFixedCount(); }

	@Override
	public PassThroughProducer<T> traverse(int axis) {
		return reshape(getShape().traverse(axis));
	}

	@Override
	public PassThroughProducer<T> reshape(TraversalPolicy shape) {
		if (shape.getTotalSize() != getShape().getTotalSize()) {
			throw new UnsupportedOperationException();
		}

		return new PassThroughProducer<>(shape, argIndex);
	}

	@Override
	public void prepareArguments(ArgumentMap map) {
		super.prepareArguments(map);
		map.add(this);
	}

	@Override
	public void prepareScope(ScopeInputManager manager, KernelStructureContext context) {
		super.prepareScope(manager, context);

		List<Argument<? extends T>> args = new ArrayList<>();
		args.add(new Argument<>(manager.argumentForInput(getNameProvider()).apply((Supplier) this), Expectation.NOT_ALTERED));
		setArguments(args);
	}

	@Override
	public Scope<T> getScope(KernelStructureContext context) {
		throw new UnsupportedOperationException();
	}

	@Override
	public Evaluable<T> get() {
		return args -> (T) args[argIndex];
	}

	/**
	 * Since the normal {@link #getArgument(int)}
	 * method returns the {@link ArrayVariable} for the specified input
	 * index, and this {@link io.almostrealism.relation.Producer} does
	 * not use inputs in the conventional way, this method returns
	 * the indexed {@link ArrayVariable} directly from the list
	 * of arguments.
	 */
	@Override
	public ArrayVariable getArgument(int index) {
		return getArgumentVariables().get(index);
	}

	@Override
	public Expression<Double> getValueAt(Expression index) {
		if (isFixedCount()) {
			// TODO  This should eventually be unnecessary, since the
			// TODO  behavior in CollectionVariable should take care
			// TODO  of this consideration
			index = index.toInt().imod(getShape().getTotalSizeLong());
		}

		return (Expression) getArgumentVariables().get(0).reference(index);
	}

	@Override
	public Expression<Double> getValueRelative(Expression index) {
		return (Expression) getArgumentVariables().get(0).reference(kernel().multiply(getMemLength()).add(index));
	}

	@Override
	public Expression uniqueNonZeroOffset(Index globalIndex, Index localIndex, Expression<?> targetIndex) {
		return null;
	}

	@Override
	public PassThroughProducer<T> generate(List<Process<?, ?>> children) {
		return this;
	}

	@Override
	public <A extends Algebraic> boolean matches(A other) {
		if ((other instanceof ProducerArgumentReference)) {
			if (!(other instanceof PassThroughProducer)) {
				// This should not be an issue, but it is something that might be
				// worth knowing if there is ever a future system which matches
				// across different types of the argument references
				warn(other.getClass().getSimpleName() + " is not a PassThroughProducer");
			}

			return ((ProducerArgumentReference) other).getReferencedArgumentIndex() == getReferencedArgumentIndex();
		}

		return false;
	}

	@Override
	public String signature() {
		return "param(" + getReferencedArgumentIndex() + "{" + getShape().toStringDetail() + "})";
	}

	@Override
	public int hashCode() {
		return getReferencedArgumentIndex();
	}

	@Override
	public boolean equals(Object obj) {
		if (!Objects.equals(getClass(), obj.getClass()) || !(obj instanceof Algebraic)) {
			return false;
		}

		return matches((Algebraic) obj);
	}

	@Override
	public String describe() {
		return getMetadata().getShortDescription() + " " +
				getShape().toStringDetail() +
				(isFixedCount() ? " (fixed)" : " (variable)");
	}

	@Override
	public String description(List<String> children) {
		if (argIndex == 0) {
			return "x";
		} else if (argIndex == 1) {
			return "y";
		} else if (argIndex == 2) {
			return "z";
		} else {
			return "input" + argIndex;
		}
	}
}
