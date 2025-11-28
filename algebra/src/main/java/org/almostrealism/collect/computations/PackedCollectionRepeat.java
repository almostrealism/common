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

import io.almostrealism.code.Computation;
import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.DefaultIndex;
import io.almostrealism.kernel.Index;
import io.almostrealism.kernel.IndexValues;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Provider;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.hardware.computations.HardwareEvaluable;

import java.util.List;
import java.util.OptionalDouble;
import java.util.function.Supplier;

/**
 * A computation that repeats elements of a {@link PackedCollection} to create larger collections
 * with duplicated data along specified dimensions. This operation is essential for broadcasting
 * operations, neural network computations, and tensor manipulations.
 * 
 * <h3>Overview</h3>
 * <p>
 * {@code PackedCollectionRepeat} extends the functionality of collections by replicating their
 * content according to specified repetition patterns. The class supports two main modes:
 * </p>
 * <ul>
 * <li><strong>Simple repetition:</strong> Repeats the entire collection a specified number of times</li>
 * <li><strong>Item repetition:</strong> Repeats individual items within the collection structure</li>
 * </ul>
 * 
 * <h3>Common Usage Patterns</h3>
 * 
 * <h4>Basic Collection Repetition</h4>
 * <pre>{@code
 * // Create a 2x3 collection
 * PackedCollection input = new PackedCollection(shape(2, 3));
 * input.fill(pos -> Math.random());
 * 
 * // Repeat the collection 4 times along a new first dimension
 * PackedCollection repeated = cp(input).repeat(4).get().evaluate();
 * // Result shape: (4, 2, 3) - same data repeated 4 times
 * }</pre>
 * 
 * <h4>Item-level Repetition with Traversal</h4>
 * <pre>{@code
 * // Repeat each item within the collection structure
 * PackedCollection itemRepeated = cp(input).traverse().repeat(3).get().evaluate();
 * // Result: each individual element is repeated 3 times
 * }</pre>
 * 
 * <h4>Broadcasting for Neural Networks</h4>
 * <pre>{@code
 * // Typical use in dense layer computation
 * PackedCollection weights = new PackedCollection(shape(inputSize, outputSize));
 * PackedCollection input = new PackedCollection(shape(inputSize));
 * 
 * // Repeat input for each output node
 * CollectionProducer<?> result = cp(input).repeat(outputSize).traverseEach()
 *     .multiply(cp(weights).enumerate(1, 1))
 *     .traverse(1).sum();
 * }</pre>
 * 
 * <h4>Upsampling Operations</h4>
 * <pre>{@code
 * // 2x2 upsampling of image data
 * PackedCollection image = pack(1.0, 2.0, 3.0, 4.0).reshape(1, 1, 2, 2);
 * PackedCollection upsampled = cp(image)
 *     .repeat(4, 2)  // Repeat along axis 4 with factor 2
 *     .repeat(3, 2)  // Repeat along axis 3 with factor 2
 *     .evaluate()
 *     .reshape(1, 1, 4, 4);
 * }</pre>
 * 
 * <h3>Technical Details</h3>
 * <p>
 * The class uses index projection to efficiently map output indices to input indices,
 * avoiding actual data duplication in memory where possible. The implementation supports:
 * </p>
 * <ul>
 * <li>Memory-efficient repetition through index mapping</li>
 * <li>Large collection support (configurable via {@link #enableLargeSlice})</li>
 * <li>Hardware acceleration compatibility</li>
 * <li>Optimization for unique index patterns</li>
 * <li>Input isolation for computational efficiency</li>
 * </ul>
 * 
 * <h3>Performance Considerations</h3>
 * <p>
 * Several configuration flags control optimization behavior:
 * </p>
 * <ul>
 * <li>{@link #enableUniqueIndexOptimization} - Optimizes for unique index patterns</li>
 * <li>{@link #enableInputIsolation} - Isolates input computations</li>
 * <li>{@link #enableIsolation} - General isolation optimization</li>
 * <li>{@link #enableLargeSlice} - Permits large slice operations</li>
 * <li>{@link #enableShortCircuit} - Short-circuits simple repetitions</li>
 * </ul>
 *
 * @see IndexProjectionProducerComputation
 * @see TraversalPolicy
 * @see PackedCollection#repeat(int)
 * @see org.almostrealism.collect.CollectionFeatures#repeat(int, io.almostrealism.relation.Producer)
 *
 * @author Michael Murray
 * @since 0.68
 */
public class PackedCollectionRepeat
		extends IndexProjectionProducerComputation {
	
	public static boolean enableUniqueIndexOptimization = true;
	public static boolean enableInputIsolation = true;
	public static boolean enableIsolation = true;
	public static boolean enableLargeSlice = true;
	public static boolean enableShortCircuit = false;

	private TraversalPolicy subsetShape;
	private TraversalPolicy sliceShape;

	/**
	 * Creates a new PackedCollectionRepeat that repeats the entire collection
	 * a specified number of times along a new leading dimension.
	 * 
	 * <p>This is the most common constructor for simple repetition operations.
	 * The input collection's item shape (obtained via {@link TraversalPolicy#item()})
	 * is used as the repetition unit.</p>
	 * 
	 * <h4>Example Usage:</h4>
	 * <pre>{@code
	 * // Repeat a 3x4 collection 5 times
	 * PackedCollection input = new PackedCollection(shape(3, 4));
	 * PackedCollectionRepeat<?> repeat = new PackedCollectionRepeat<>(5, cp(input));
	 * // Result shape: (5, 3, 4)
	 * }</pre>
	 * 
	 * @param repeat the number of times to repeat the collection (must be positive)
	 * @param collection the source collection to repeat (must implement {@link Shape})
	 * 
	 * @throws IllegalArgumentException if collection doesn't implement Shape
	 * @throws UnsupportedOperationException if the operation would exceed size limits
	 *         and {@link #enableLargeSlice} is false
	 * 
	 * @see #PackedCollectionRepeat(TraversalPolicy, int, Producer)
	 */
	public PackedCollectionRepeat(int repeat, Producer<?> collection) {
		this(shape(collection).item(), repeat, collection);
	}

	/**
	 * Creates a new PackedCollectionRepeat with explicit shape control for the repetition unit.
	 * 
	 * <p>This constructor provides fine-grained control over how the collection is repeated
	 * by allowing specification of the exact shape that should be repeated. This is useful
	 * for custom repetition patterns or when the automatic item shape detection is not
	 * appropriate.</p>
	 * 
	 * <h4>Example Usage:</h4>
	 * <pre>{@code
	 * // Repeat specific 2x2 blocks from a 4x4 collection, 3 times
	 * PackedCollection input = new PackedCollection(shape(4, 4));
	 * TraversalPolicy blockShape = shape(2, 2);
	 * PackedCollectionRepeat<?> repeat = new PackedCollectionRepeat<>(blockShape, 3, cp(input));
	 * }</pre>
	 * 
	 * <h4>Shape Calculations:</h4>
	 * <p>The output shape is computed as:</p>
	 * <ul>
	 * <li>If the specified shape has 0 dimensions, it's treated as a single element</li>
	 * <li>The repeat count is prepended as a new leading dimension</li>
	 * <li>The final shape includes traversal information for efficient access</li>
	 * </ul>
	 * 
	 * @param shape the shape of each repetition unit (cannot be null)
	 * @param repeat the number of times to repeat (must be positive)
	 * @param collection the source collection to repeat (must implement {@link Shape})
	 * 
	 * @throws IllegalArgumentException if collection doesn't implement Shape
	 * @throws UnsupportedOperationException if the operation would exceed size limits
	 *         and {@link #enableLargeSlice} is false
	 * 
	 * @see TraversalPolicy#prependDimension(int)
	 * @see #shape(int, TraversalPolicy)
	 */
	public PackedCollectionRepeat(TraversalPolicy shape, int repeat, Producer<?> collection) {
		super("repeat" + repeat, shape(collection).replace(shape.prependDimension(repeat)).traverse(),
				null, collection);
		this.subsetShape = shape.getDimensions() == 0 ? shape(1) : shape;
		this.sliceShape = subsetShape.prependDimension(repeat);

		if (collection instanceof CollectionConstantComputation) {
			warn("Repeating a constant");
		}

		if (!enableLargeSlice &&
				(!isFixedCount() || sliceShape.getTotalSizeLong() < getShape().getTotalSizeLong()) &&
				sliceShape.getTotalSizeLong() > Integer.MAX_VALUE) {
			throw new UnsupportedOperationException();
		}
	}

	/**
	 * Internal constructor for creating repeat operations with pre-computed shape policies.
	 * 
	 * <p>This constructor is used internally for optimization and when generating
	 * replacement computations. It bypasses shape calculations and validation,
	 * assuming the provided shapes are already correct.</p>
	 * 
	 * @param shape the overall output shape including repetition dimensions
	 * @param subsetShape the shape of individual repetition units
	 * @param sliceShape the complete slice shape including repetition axis
	 * @param collection the source collection to repeat
	 */
	private PackedCollectionRepeat(TraversalPolicy shape, TraversalPolicy subsetShape,
								   TraversalPolicy sliceShape, Producer<?> collection) {
		super("repeat", shape, null, collection);
		this.subsetShape = subsetShape;
		this.sliceShape = sliceShape;
	}

	/**
	 * Projects an output index to the corresponding input index in the source collection.
	 * 
	 * <p>This is the core method that implements the repetition logic by mapping
	 * each position in the output collection to the appropriate position in the
	 * input collection. The implementation handles different cases based on whether
	 * the repetition creates multiple slices or operates within a single slice.</p>
	 * 
	 * <h4>Algorithm:</h4>
	 * <ol>
	 * <li>Determines which repetition slice the output index belongs to</li>
	 * <li>Calculates the offset within that slice</li>
	 * <li>Maps the offset back to the corresponding input position</li>
	 * <li>Handles modular arithmetic for repetition boundaries</li>
	 * </ol>
	 * 
	 * @param index the output index to be projected to the input space
	 * @return the corresponding input index expression
	 * 
	 * @see IndexProjectionProducerComputation#projectIndex(Expression)
	 */
	@Override
	protected Expression projectIndex(Expression index) {
		Expression slice;
		Expression offset;

		if (!isFixedCount() || sliceShape.getTotalSizeLong() < getShape().getTotalSizeLong()) {
			// Identify the output slice
			if (sliceShape.getTotalSizeLong() == 1) {
				slice = index;
			} else if (!index.isFP()) {
				slice = index.divide(e(sliceShape.getTotalSizeLong()));
			} else {
				slice = index.divide(e((double) sliceShape.getTotalSizeLong())).floor();
			}

			// Find the index in the output slice
			offset = index.toInt().imod(sliceShape.getTotalSizeLong());
		} else {
			// There is only one slice
			slice = e(0);
			offset = index;
		}

		// Find the index in the input slice
		offset = offset.imod(subsetShape.getTotalSizeLong());

		// Position the offset relative to the slice
		offset = slice.multiply(e(subsetShape.getTotalSizeLong())).add(offset);

		return offset;
	}

	/**
	 * Retrieves a value from the repeated collection at a relative index position.
	 * 
	 * <p>This method provides direct access to values in the repeated collection
	 * by computing the corresponding input position and delegating to the
	 * underlying collection's value retrieval.</p>
	 * 
	 * @deprecated This method is marked for removal in future versions.
	 *             Use the standard evaluation pipeline instead.
	 * 
	 * @param index the relative index in the output collection
	 * @return the value expression at the computed input position
	 * @throws UnsupportedOperationException if the index cannot be simplified to a constant
	 */
	// TODO  Remove
	@Override
	public Expression<Double> getValueRelative(Expression index) {
		Expression offset = projectIndex(index);
		OptionalDouble offsetValue = offset.getSimplified().doubleValue();
		if (offsetValue.isEmpty()) throw new UnsupportedOperationException();

		return getArgument(1).getValueRelative((int) offsetValue.getAsDouble());
	}

	/**
	 * Computes a unique non-zero offset expression for optimized index operations.
	 * 
	 * <p>This method implements an optimization for cases where the repetition
	 * pattern allows for predictable index relationships. It's particularly
	 * effective when the slice shape aligns well with the overall output shape
	 * and the indices follow regular patterns.</p>
	 * 
	 * <h4>Optimization Conditions:</h4>
	 * <ul>
	 * <li>Unique index optimization must be enabled</li>
	 * <li>The slice must not be smaller than the total shape</li>
	 * <li>Index limits must be available and properly aligned</li>
	 * <li>The subset shape must be divisible by the local index limit</li>
	 * </ul>
	 * 
	 * @param globalIndex the global index context for the operation
	 * @param localIndex the local index within the current scope
	 * @param targetIndex the target index being computed
	 * @return an optimized offset expression, or null if optimization is not applicable
	 * 
	 * @see IndexProjectionProducerComputation#uniqueNonZeroOffset(Index, Index, Expression)
	 */
	@Override
	public Expression uniqueNonZeroOffset(Index globalIndex, Index localIndex, Expression<?> targetIndex) {
		if (!enableUniqueIndexOptimization || sliceShape.getTotalSizeLong() < getShape().getTotalSizeLong())
			return super.uniqueNonZeroOffset(globalIndex, localIndex, targetIndex);

		if (!Index.child(globalIndex, localIndex).equals(targetIndex)) {
			return super.uniqueNonZeroOffset(globalIndex, localIndex, targetIndex);
		}

		if (localIndex.getLimit().isEmpty() || globalIndex.getLimit().isEmpty()) return null;
		if (subsetShape.getTotalSizeLong() % localIndex.getLimit().getAsLong() != 0) return null;

		if (globalIndex.getLimit().getAsLong() == 0) {
			throw new UnsupportedOperationException();
		}

		long limit = getShape().getTotalSizeLong() / globalIndex.getLimit().getAsLong();
		DefaultIndex g = new DefaultIndex(getNameProvider().getVariablePrefix() + "_g", limit);
		DefaultIndex l = new DefaultIndex(getNameProvider().getVariablePrefix() + "_l", localIndex.getLimit().getAsLong());

		Expression idx = getCollectionArgumentVariable(1).uniqueNonZeroOffset(g, l, Index.child(g, l));
		if (idx == null) return idx;
		if (!idx.isValue(IndexValues.of(g))) return null;

		return idx.withIndex(g, ((Expression<?>) globalIndex).imod(limit));
	}

	/**
	 * Creates an evaluable instance of this repeat operation.
	 * 
	 * <p>This method implements several optimization strategies:</p>
	 * <ul>
	 * <li><strong>Short-circuit optimization:</strong> For simple repetitions where
	 *     the slice size equals the output size, delegates directly to the
	 *     {@link PackedCollection#repeat(int)} method</li>
	 * <li><strong>Provider optimization:</strong> When the input is a Provider,
	 *     creates an optimized evaluable that applies repetition directly</li>
	 * <li><strong>Hardware optimization:</strong> Uses hardware-accelerated
	 *     evaluation with short-circuit capabilities when possible</li>
	 * </ul>
	 * 
	 * <h4>Performance Notes:</h4>
	 * <p>The short-circuit path is significantly more efficient for simple
	 * repetitions as it avoids the full computation pipeline and performs
	 * the repetition at the collection level.</p>
	 * 
	 * @return an optimized evaluable for this repeat operation
	 * 
	 * @see PackedCollection#repeat(int)
	 * @see HardwareEvaluable
	 */
	@Override
	public Evaluable<PackedCollection> get() {
		if (!enableShortCircuit || sliceShape.getTotalSizeLong() != getShape().getTotalSizeLong()) {
			return super.get();
		}

		Evaluable<PackedCollection> ev = (Evaluable) getInputs().get(1).get();

		int r = Math.toIntExact(getShape().getTotalSizeLong() / subsetShape.getTotalSizeLong());

		if (ev instanceof Provider) {
			return p((Provider) ev, v ->
					((PackedCollection) v).repeat(r));
		}

		HardwareEvaluable<PackedCollection> hev = new HardwareEvaluable(getInputs().get(1)::get, null, null, false);
		hev.setShortCircuit(args -> {
			PackedCollection out = hev.getKernel().getValue().evaluate(args);
			return out.repeat(r);
		});
		return hev;
	}

	/**
	 * Generates a new PackedCollectionRepeat with updated child processes.
	 * 
	 * <p>This method is used by the computation framework to create new instances
	 * of this repeat operation when the computation graph is modified or optimized.
	 * The new instance preserves the shape policies while updating the input
	 * collection reference.</p>
	 * 
	 * @param children the list of child processes, where the second element
	 *                 should be the new collection producer
	 * @return a new PackedCollectionRepeat instance with the same configuration
	 *         but updated input
	 * 
	 * @see Process#generate(List)
	 */
	@Override
	public PackedCollectionRepeat generate(List<Process<?, ?>> children) {
		return new PackedCollectionRepeat(getShape(), subsetShape, sliceShape, (Producer<?>) children.get(1));
	}

	/**
	 * Creates an isolated version of this computation for optimization purposes.
	 * 
	 * <p>Isolation is a key optimization technique that can improve performance by:</p>
	 * <ul>
	 * <li>Preventing redundant computation of input values</li>
	 * <li>Enabling better memory locality</li>
	 * <li>Allowing for specialized optimization of the repeat operation</li>
	 * </ul>
	 * 
	 * <h4>Isolation Strategy:</h4>
	 * <p>The method considers several factors when deciding how to isolate:</p>
	 * <ul>
	 * <li>Whether the input is a {@link ReshapeProducer} (unwrapped for efficiency)</li>
	 * <li>Whether the input is a {@link Computation} (isolated recursively)</li>
	 * <li>Configuration flags controlling isolation behavior</li>
	 * </ul>
	 * 
	 * @return an isolated version of this computation, potentially wrapped
	 *         in additional isolation infrastructure
	 * 
	 * @see Process#isolate()
	 * @see Process#isolated(Supplier)
	 */
	@Override
	public Process<Process<?, ?>, Evaluable<? extends PackedCollection>> isolate() {
		Producer in = (Producer) getInputs().get(1);
		if (in instanceof ReshapeProducer) in = ((ReshapeProducer) in).getComputation();

		boolean computable = in instanceof Computation;

		if (!enableIsolation && !computable) {
			PackedCollectionRepeat isolated = (PackedCollectionRepeat)
					generateReplacement(List.of((Process) getInputs().get(0), (Process) getInputs().get(1)));
			return isolated;
		}

		if (!enableInputIsolation || !computable)
			return super.isolate();

		PackedCollectionRepeat isolated = (PackedCollectionRepeat)
				generateReplacement(List.of((Process) getInputs().get(0), isolate((Process) getInputs().get(1))));

		return enableIsolation ? (Process) Process.isolated(isolated) : isolated;
	}

	/**
	 * Provides a human-readable description of this repeat operation.
	 * 
	 * <p>This method generates descriptive text for debugging and logging purposes.
	 * When there's only one child (the typical case), it returns the child's
	 * description to avoid redundant nesting in the description hierarchy.</p>
	 * 
	 * @param children descriptions of child operations
	 * @return a description string for this repeat operation
	 */
	@Override
	public String description(List<String> children) {
		return children.size() == 1 ? children.get(0) : super.description(children);
	}

	/**
	 * Extracts the traversal policy from a collection producer.
	 * 
	 * <p>This utility method ensures that the provided producer implements
	 * the {@link Shape} interface, which is required for repeat operations
	 * to determine the appropriate traversal policy.</p>
	 * 
	 * @param collection the collection producer to extract shape from
	 * @return the traversal policy of the collection
	 * @throws IllegalArgumentException if the collection doesn't implement Shape
	 * 
	 * @see Shape#getShape()
	 */
	private static TraversalPolicy shape(Producer<?> collection) {
		if (!(collection instanceof Shape))
			throw new IllegalArgumentException("Repeat cannot be performed without a TraversalPolicy");

		return ((Shape) collection).getShape();
	}

	/**
	 * Computes the output shape for a repeat operation given the repeat count and input shape.
	 * 
	 * <p>This method handles the complex logic of determining how the output shape
	 * should be structured based on the input collection's characteristics and
	 * the desired repetition pattern.</p>
	 * 
	 * <h4>Shape Computation Rules:</h4>
	 * <ul>
	 * <li><strong>Single element case:</strong> If the input is a single element
	 *     (total size = 1, dimensions = 1, item dimensions = 0), creates a simple
	 *     traversed vector of the repeated elements</li>
	 * <li><strong>General case:</strong> Prepends the repeat count as a new leading
	 *     dimension to the item shape, then applies traversal</li>
	 * </ul>
	 * 
	 * <h4>Example Transformations:</h4>
	 * <pre>{@code
	 * // Input: shape(3, 4), repeat: 5
	 * // Item: shape(3, 4)
	 * // Result: shape(5, 3, 4).traverse()
	 * 
	 * // Input: shape(1) (single element), repeat: 3  
	 * // Result: shape(3).traverse()
	 * }</pre>
	 * 
	 * @param repeat the number of repetitions to perform
	 * @param inputShape the shape of the input collection
	 * @return the computed output shape with appropriate traversal policy
	 * 
	 * @see TraversalPolicy#item()
	 * @see TraversalPolicy#prependDimension(int)
	 * @see TraversalPolicy#traverse()
	 */
	public static TraversalPolicy shape(int repeat, TraversalPolicy inputShape) {
		TraversalPolicy shape = inputShape.item();

		if (inputShape.getTotalSizeLong() == 1 && inputShape.getDimensions() == 1 && shape.getDimensions() == 0) {
			return new TraversalPolicy(repeat).traverse();
		}

		return inputShape.replace(shape.prependDimension(repeat)).traverse();
	}
}
