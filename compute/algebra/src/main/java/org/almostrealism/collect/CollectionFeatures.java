/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.collect;

import io.almostrealism.code.Computation;
import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.IndexOfPositionExpression;
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.Absolute;
import io.almostrealism.relation.Countable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.ScopeSettings;
import org.almostrealism.algebra.MatrixFeatures;
import org.almostrealism.calculus.DeltaFeatures;
import org.almostrealism.collect.computations.CollectionConcatenateComputation;
import org.almostrealism.collect.computations.CollectionProducerComputationBase;
import org.almostrealism.collect.computations.DefaultTraversableExpressionComputation;
import org.almostrealism.collect.computations.PackedCollectionEnumerate;
import org.almostrealism.collect.computations.ReshapeProducer;
import org.almostrealism.hardware.computations.Assignment;
import org.almostrealism.io.Console;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Comprehensive factory interface for creating {@link CollectionProducer} computations.
 *
 * <p>
 * {@link CollectionFeatures} is the primary API for building computational graphs in the Almost Realism
 * framework. It composes the themed feature interfaces into a single mixin, so implementing it grants
 * access to the entire factory-method vocabulary - creation, slicing, arithmetic, aggregation,
 * comparison, and gradients - through simple method calls. {@link CollectionFeatures} itself declares
 * only the operations that span those categories (concatenation, element indexing, assignment, and
 * the generic {@code compute} factories).
 * </p>
 *
 * <h2>Design Pattern</h2>
 * <p>
 * This interface is designed as a mixin that classes can implement to gain access to all factory methods:
 * </p>
 * <pre>{@code
 * public class MyComputation implements CollectionFeatures {
 *     public void example() {
 *         // All factory methods available directly
 *         CollectionProducer<?> data = c(10.0);
 *         CollectionProducer<?> result = add(data, c(5.0));
 *     }
 * }
 * }</pre>
 *
 * <h2>Where the Methods Live</h2>
 * <p>
 * The factory methods are declared across a chain of themed interfaces, each extending the one
 * below it; {@link CollectionFeatures} sits at the top and inherits them all:
 * </p>
 * <ul>
 *   <li>{@link ShapeFeatures} - {@code shape(...)} factories and shape inspection</li>
 *   <li>{@link TraversalPolicyFeatures} - traversal-policy construction and manipulation</li>
 *   <li>{@link CollectionTraversalFeatures} - {@code traverse}, {@code each}, {@code reshape},
 *       traversal-axis alignment</li>
 *   <li>{@link CollectionCreationFeatures} - {@code pack}, {@code p}, {@code c}, {@code cp},
 *       {@code constant}, {@code zeros}, {@code func}, {@code rand}/{@code randn},
 *       {@code integers}, {@code linear}</li>
 *   <li>{@link SlicingFeatures} - {@code subset}, {@code repeat}, {@code enumerate},
 *       {@code permute}, {@code pad}, {@code map}, {@code reduce}, {@code cumulativeProduct}</li>
 *   <li>{@link ArithmeticFeatures} - {@code add}, {@code subtract}, {@code multiply},
 *       {@code divide}, {@code minus}, {@code sqrt}, {@code pow}, {@code exp}, {@code log},
 *       {@code sq}, {@code floor}, {@code min}/{@code max}, {@code rectify}, {@code mod},
 *       {@code bound}, {@code abs}, {@code magnitude}, {@code sigmoid}</li>
 *   <li>{@link AggregationFeatures} - {@code sum}, {@code mean}, {@code variance},
 *       {@code subtractMean}, {@code max}, {@code indexOfMax}, {@code subdivide}</li>
 *   <li>{@link ComparisonFeatures} - {@code equals}, {@code greaterThan}, {@code lessThan}
 *       (and conditional-selection overloads), {@code and}</li>
 *   <li>{@link GradientFeatures} - {@code delta}, {@code combineGradient},
 *       {@code multiplyGradient}</li>
 *   <li>{@link CollectionFeatures} (this interface) - {@code concat}, element indexing
 *       ({@code c(collection, index)}, {@code index}, {@code sizeOf}), {@code a} (assignment),
 *       the generic {@code compute} factories, and host-side utilities
 *       ({@code argmax}, {@code topK})</li>
 * </ul>
 *
 * <h2>Usage Examples</h2>
 *
 * <h3>Building a Computation Graph</h3>
 * <pre>{@code
 * CollectionProducer<?> x = v(PackedCollection.class);
 * CollectionProducer<?> weights = c(1.0, 2.0, 3.0);
 *
 * // Build graph: y = sigmoid(x * weights + 0.5)
 * CollectionProducer<?> y = sigmoid(
 *     add(
 *         multiply(x, weights),
 *         c(0.5)
 *     )
 * );
 * }</pre>
 *
 * <h3>Statistical Analysis</h3>
 * <pre>{@code
 * CollectionProducer<?> data = p(myData);
 *
 * // Normalize: (x - mean) / sqrt(variance)
 * CollectionProducer<?> normalized = divide(
 *     subtractMean(data),
 *     sqrt(variance(data))
 * );
 * }</pre>
 *
 * <h3>Conditional Logic</h3>
 * <pre>{@code
 * CollectionProducer<?> x = ...;
 * CollectionProducer<?> y = ...;
 *
 * // ReLU: max(0, x)
 * CollectionProducer<?> relu = greaterThan(x, c(0.0), x, c(0.0));
 *
 * // Clamp: clip values to [min, max]
 * CollectionProducer<?> clamped = lessThan(x, c(min),
 *     c(min),
 *     greaterThan(x, c(max), c(max), x)
 * );
 * }</pre>
 *
 * <h3>Tensor Transformations</h3>
 * <pre>{@code
 * CollectionProducer<?> tensor = p(myTensor); // shape (10, 20, 30)
 *
 * tensor.reshape(shape(200, 30))      // Reshape to (200, 30)
 * tensor.traverse(1)                  // Traverse along axis 1
 * tensor.subset(shape(5, 10), 0, 5)   // Extract (5, 10) subset
 * tensor.permute(2, 0, 1)             // Permute to (30, 10, 20)
 * tensor.pad(1, 2, 3)                 // Add padding
 * }</pre>
 *
 * <h2>Implementation Notes</h2>
 * <ul>
 *   <li><b>Lazy Evaluation:</b> All methods return producers that build a computation graph;
 *       actual computation happens only when {@code get().evaluate()} is called</li>
 *   <li><b>Hardware Acceleration:</b> Computations are compiled to optimized kernels automatically</li>
 *   <li><b>Shape Inference:</b> Output shapes are inferred from input shapes where possible</li>
 *   <li><b>Broadcasting:</b> Binary operations support NumPy-style broadcasting</li>
 *   <li><b>Type Safety:</b> Generic types help catch shape/type errors at compile time</li>
 * </ul>
 *
 * <h2>Configuration</h2>
 * <ul>
 *   <li><b>enableShapelessWarning:</b> Warns when creating shapeless producers (default: false)</li>
 * </ul>
 *
 * @author  Michael Murray
 * @see CollectionProducer
 * @see PackedCollection
 * @see TraversalPolicy
 * @see MatrixFeatures
 * @see DeltaFeatures
 */
public interface CollectionFeatures extends GradientFeatures {
	/** When true, logs a warning when operations are attempted on collections with no defined shape. */
	boolean enableShapelessWarning = false;

	/** When true, repeat operations are permitted for producers with variable (non-fixed) counts. */
	boolean enableVariableRepeat = false;

	// Should be flipped and removed
	/** When true, uses the alternative delta computation path for index projection operations. */
	boolean enableIndexProjectionDeltaAlt = true;

	/** When true, uses the collection size for index expressions instead of a fixed constant. */
	boolean enableCollectionIndexSize = false;

	// Possible future feature
	/** When true, applies the unary weighted sum optimization for single-input aggregations. */
	boolean enableUnaryWeightedSum = false;

	/** When true, enables subdivision of collections for block-based aggregation. */
	boolean enableSubdivide = enableUnaryWeightedSum;

	/**
	 * When true, {@link #concat(TraversalPolicy, Producer[])} produces a single
	 * index-select {@link CollectionConcatenateComputation} whenever the inputs exactly
	 * cover the output along the concatenation axis, instead of zero-padding every input
	 * to the output shape and summing (which fragments the compiled graph into a pad+add
	 * chain per input and inflates gradient expression trees).
	 */
	boolean enableConcatenateComputation = true;

	/**
	 * Returns whether the alternative index projection delta computation path is enabled.
	 *
	 * @return true if the alternative delta path is active
	 */
	static boolean isEnableIndexProjectionDeltaAlt() {
		return enableIndexProjectionDeltaAlt;
	}

	/** Shared console logger for collection feature operations. */
	Console console = Computation.console.child();

	/**
	 * Creates an assignment operation with a short human-readable description.
	 *
	 * @param shortDescription a brief label for debugging and profiling
	 * @param result the destination producer to assign to
	 * @param value the source producer whose value is assigned
	 * @return an assignment operation with the given description
	 */
	default Assignment<PackedCollection> a(String shortDescription, Producer<PackedCollection> result, Producer<PackedCollection> value) {
		Assignment<PackedCollection> a = a(result, value);
		a.getMetadata().setShortDescription(shortDescription);
		return a;
	}

	/**
	 * Creates an assignment operation that copies the value of {@code value} into {@code result}.
	 * Automatically reconciles traversal axis mismatches and adjusts the value traversal
	 * to satisfy kernel statement count limits.
	 *
	 * @param result the destination producer to assign to
	 * @param value the source producer whose value is assigned
	 * @return an assignment operation
	 * @throws IllegalArgumentException if the shapes are not compatible
	 */
	default Assignment<PackedCollection> a(Producer<PackedCollection> result, Producer<PackedCollection> value) {
		TraversalPolicy resultShape = shape(result);
		TraversalPolicy valueShape = shape(value);

		if (resultShape.getSize() != valueShape.getSize()) {
			int axis = TraversalPolicy.compatibleAxis(resultShape, valueShape, true);
			if (axis == -1) {
				throw new IllegalArgumentException();
			} else if (axis < resultShape.getTraversalAxis()) {
				console.warn("Assignment destination (" + resultShape.getCountLong() +
						") adjusted to match source (" + valueShape.getCountLong() + ")");
			}

			return a(traverse(axis, result), value);
		} else if (resultShape.getSizeLong() > ScopeSettings.preferredStatements) {
			TraversalPolicy adjustedValueShape = valueShape;

			// Attempt to increase the count, thereby reducing the size
			// and hence the number of required statements
			while (adjustedValueShape.getSizeLong() > ScopeSettings.preferredStatements) {
				int axis = adjustedValueShape.getTraversalAxis();

				// TODO  It may be preferable to have a special case for the final
				// TODO  dimension if it is large, attempting to split it in half
				// TODO  rather than simply skip to a size of 1 and a potentially
				// TODO  very large count. eg (3, 1024) -> (3, 2, 512) instead
				if (axis <= adjustedValueShape.getDimensions()) {
					adjustedValueShape = adjustedValueShape.traverse(axis + 1);
				} else {
					// This should never happen, as traversing past the
					// final dimension should always produce a size of 1
					throw new UnsupportedOperationException();
				}
			}

			return a(result, traverse(adjustedValueShape.getTraversalAxis(), value));
		}

		// TODO  Value should be repeated to ensure it is compatible with result
		return new Assignment<>(shape(result).getSize(), result, value);
	}

	/**
	 * Concatenates the given producers along axis 0.
	 *
	 * @param producers the producers to concatenate
	 * @return a producer whose output is the concatenation of all inputs along axis 0
	 */
	default CollectionProducer concat(Producer<PackedCollection>... producers) {
		return concat(0, producers);
	}

	/**
	 * Concatenates the given producers along the specified axis.
	 * All inputs must have the same number of dimensions.
	 *
	 * @param axis the axis along which to concatenate
	 * @param producers the producers to concatenate
	 * @return a producer whose output is the concatenation of all inputs along the given axis
	 * @throws IllegalArgumentException if inputs have mismatched dimension counts
	 */
	default CollectionProducer concat(int axis, Producer<PackedCollection>... producers) {
		List<TraversalPolicy> shapes = Stream.of(producers)
				.map(this::shape)
				.filter(s -> s.getDimensions() == shape(producers[0]).getDimensions())
				.collect(Collectors.toList());
		if (shapes.size() != producers.length) {
			throw new IllegalArgumentException("All inputs must have the same number of dimensions");
		}

		long[] dims = new long[shapes.get(0).getDimensions()];
		for (int i = 0; i < dims.length; i++) {
			if (i == axis) {
				dims[i] = shapes.stream().mapToLong(s -> s.length(axis)).sum();
			} else {
				dims[i] = shapes.get(0).length(i);
			}
		}

		return concat(new TraversalPolicy(dims), producers);
	}

	/**
	 * Concatenates the given producers into an output with the given explicit shape.
	 *
	 * <p>When the inputs exactly cover the output along the concatenation axis (and
	 * {@link #enableConcatenateComputation} is set), this produces a single
	 * {@link CollectionConcatenateComputation} that selects the source for each output
	 * position by index — one kernel, with a sparse-projection gradient. Otherwise
	 * (the output is longer than the inputs' total, leaving a zero tail), inputs are
	 * zero-padded to the output shape and summed.</p>
	 *
	 * @param shape the shape of the concatenated output
	 * @param producers the producers to concatenate
	 * @return a producer computing the concatenation
	 * @throws IllegalArgumentException if inputs cannot be concatenated into the given shape
	 */
	default CollectionProducer concat(TraversalPolicy shape, Producer<PackedCollection>... producers) {
		List<TraversalPolicy> shapes = Stream.of(producers)
				.map(this::shape)
				.filter(s -> s.getDimensions() == shape.getDimensions())
				.collect(Collectors.toList());

		if (shapes.size() != producers.length) {
			throw new IllegalArgumentException("All inputs must have the same number of dimensions");
		}

		int axis = -1;

		for (TraversalPolicy s : shapes) {
			for (int d = 0; d < shape.getDimensions(); d++) {
				if (s.length(d) != shape.length(d)) {
					if (axis < 0) axis = d;

					if (axis != d) {
						throw new IllegalArgumentException("Cannot concatenate over more than one axis at once");
					}
				}
			}
		}

		if (axis < 0) {
			throw new UnsupportedOperationException();
		}

		int total = 0;
		List<TraversalPolicy> positions = new ArrayList<>();

		for (TraversalPolicy s : shapes) {
			if (total >= shape.length(axis)) {
				throw new IllegalArgumentException("The result is not large enough to concatenate all inputs");
			}

			int[] pos = new int[s.getDimensions()];
			pos[axis] = total;
			total += s.length(axis);
			positions.add(new TraversalPolicy(true, pos));
		}

		if (enableConcatenateComputation && total == shape.length(axis)) {
			// The inputs exactly cover the output along the axis, so the dedicated
			// index-select computation applies. (A shorter total leaves an implicit
			// zero tail that the select form does not represent; only the padded
			// sum below preserves that.)
			return new CollectionConcatenateComputation(shape, axis, producers);
		}

		return add(IntStream.range(0, shapes.size())
				.mapToObj(i -> pad(shape, positions.get(i), producers[i]))
				.collect(Collectors.toList()));
	}

	/**
	 * Converts a generic producer to a {@link CollectionProducer}.
	 *
	 * @param producer the producer to convert
	 * @return the producer as a {@link CollectionProducer}
	 * @throws UnsupportedOperationException if the producer cannot be converted
	 */
	default CollectionProducer c(Producer producer) {
		if (producer instanceof CollectionProducer) {
			return (CollectionProducer) producer;
		} else if (producer instanceof Shape) {
			return new ReshapeProducer(((Shape) producer).getShape().getTraversalAxis(), producer);
		} else if (producer != null) {
			throw new UnsupportedOperationException(producer.getClass() + " cannot be converted to a CollectionProducer");
		} else {
			throw new UnsupportedOperationException();
		}
	}

	/**
	 * Retrieves the value at a specific relative index from a collection producer.
	 *
	 * @param supplier the collection producer to read from
	 * @param index the relative index within each item to retrieve
	 * @return a scalar computation returning the value at that index
	 */
	default CollectionProducerComputation c(Producer supplier, int index) {
		TraversalPolicy shape = shape(1);
		long size = shape(supplier).getSizeLong();
		return new DefaultTraversableExpressionComputation("valueAtIndexRelative", shape,
				args -> {
					if (args[1] == null) {
						throw new UnsupportedOperationException();
					}

					return CollectionExpression.create(shape, idx ->
							args[1].getValueAt(idx.multiply(size).add(index)));
				},
				supplier);
	}

	/**
	 * Retrieves values from a collection at positions given by another collection.
	 * Uses either the collection shape or the index shape depending on {@link #enableCollectionIndexSize}.
	 *
	 * @param collection the collection to read values from
	 * @param index the producer of indices into the collection
	 * @return a computation that gathers values at the specified indices
	 */
	default CollectionProducerComputation c(Producer<PackedCollection> collection,
											Producer<PackedCollection> index) {
		if (enableCollectionIndexSize) {
			return c(shape(index), collection, index);
		} else {
			return c(shape(collection), collection, index);
		}
	}

	/**
	 * Retrieves values from a collection at positions given by another collection,
	 * producing output with the given explicit shape.
	 *
	 * @param shape the output shape
	 * @param collection the collection to read values from
	 * @param index the producer of indices into the collection
	 * @return a computation that gathers values at the specified indices
	 */
	default CollectionProducerComputation c(TraversalPolicy shape,
											Producer<PackedCollection> collection,
											Producer<PackedCollection> index) {
		DefaultTraversableExpressionComputation exp = new DefaultTraversableExpressionComputation("valueAtIndex", shape,
				args -> CollectionExpression.create(shape, idx -> args[1].getValueAt(args[2].getValueAt(idx).toInt())),
				collection, index);
		if (shape.getTotalSize() == 1 && Countable.isFixedCount(index)) {
			exp.setShortCircuit(args -> {
				Evaluable<? extends PackedCollection> out = ag -> new PackedCollection(1);
				Evaluable<? extends PackedCollection> c = collection.get();
				Evaluable<? extends PackedCollection> i = index.get();

				PackedCollection col = c.evaluate(args);
				PackedCollection idx = i.evaluate(args);
				PackedCollection dest = out.evaluate(args);
				dest.setMem(col.toDouble((int) idx.toDouble(0)));
				return dest;
			});
		}

		return exp;
	}

	/**
	 * Retrieves values from a collection at multi-dimensional positions.
	 * Infers the collection shape from the producer itself.
	 *
	 * @param collection the collection to read values from
	 * @param pos the position producers, one per dimension of the collection
	 * @return a computation that gathers values at the specified positions
	 */
	default CollectionProducerComputation c(Producer<PackedCollection> collection,
											Producer<PackedCollection>... pos) {
		return c(collection, shape(collection), pos);
	}

	/**
	 * Retrieves values from a collection at multi-dimensional positions with an explicit collection shape.
	 *
	 * @param collection the collection to read values from
	 * @param collectionShape the shape used to compute linear indices from the given positions
	 * @param pos the position producers, one per dimension of the collection
	 * @return a computation that gathers values at the specified positions
	 */
	default CollectionProducerComputation c(Producer<PackedCollection> collection,
											TraversalPolicy collectionShape,
											Producer<PackedCollection>... pos) {
		return c(shape(pos[0]), collection, collectionShape, pos);
	}

	/**
	 * Retrieves values from a collection at multi-dimensional positions with explicit output and collection shapes.
	 *
	 * @param outputShape the shape of the output
	 * @param collection the collection to read values from
	 * @param collectionShape the shape used to compute linear indices from the given positions
	 * @param pos the position producers, one per dimension of the collection
	 * @return a computation that gathers values at the specified positions
	 */
	default CollectionProducerComputation c(TraversalPolicy outputShape,
											Producer<PackedCollection> collection,
											TraversalPolicy collectionShape,
											Producer<PackedCollection>... pos) {
		return c(outputShape, collection, index(collectionShape, pos));
	}

	/**
	 * Computes a linear index from multi-dimensional position producers using the given shape.
	 * Infers the output shape from the first position producer.
	 *
	 * @param shapeOf the shape defining how positions map to linear indices
	 * @param pos the position producers, one per dimension
	 * @return a scalar computation of the linear index
	 */
	default CollectionProducerComputation index(TraversalPolicy shapeOf,
												Producer<PackedCollection>... pos) {
		return index(shape(pos[0]), shapeOf, pos);
	}

	/**
	 * Computes a linear index from multi-dimensional position producers with an explicit output shape.
	 *
	 * @param shape the output shape
	 * @param shapeOf the shape defining how positions map to linear indices
	 * @param pos the position producers, one per dimension
	 * @return a computation of the linear index
	 */
	default CollectionProducerComputation index(TraversalPolicy shape,
												TraversalPolicy shapeOf,
												Producer<PackedCollection>... pos) {
		return new DefaultTraversableExpressionComputation("index", shape,
						(args) ->
								new IndexOfPositionExpression(shape, shapeOf,
										Stream.of(args).skip(1).toArray(TraversableExpression[]::new)), pos);
	}

	/**
	 * Returns a scalar computation that yields the size of the given collection.
	 *
	 * @param collection the collection whose size is computed
	 * @return a scalar computation producing the total element count
	 */
	default CollectionProducerComputation sizeOf(Producer<PackedCollection> collection) {
		return new DefaultTraversableExpressionComputation("sizeOf", shape(1),
				(args) -> CollectionExpression.create(shape(1),
						index -> ((ArrayVariable) args[1]).length()), collection);
	}

	/**
	 * Creates an enumeration using explicit {@link TraversalPolicy} shapes for both
	 * subset and stride patterns. This provides full control over the enumeration
	 * operation for advanced use cases.
	 * 
	 * <p>Note: This method delegates to {@link PackedCollectionEnumerate#of}.</p>
	 * 
	 *
	 * @param shape the {@link TraversalPolicy} defining the subset shape
	 * @param stride the {@link TraversalPolicy} defining the stride pattern
	 * @param collection the collection to enumerate
	 * @return a {@link CollectionProducerComputation} containing the enumerated sequences
	 * 
	 *
	 * <pre>{@code
	 * // Custom stride enumeration for complex patterns
	 * CollectionProducer data = c(shape(8, 6), 1, 2, 3, ...);
	 * CollectionProducerComputation<PackedCollection> custom =
	 *     enumerate(shape(2, 3), shape(1, 1), data);
	 * // Creates overlapping 2x3 patches with stride 1 in both dimensions
	 * }</pre>
	 * 
	 * @see PackedCollectionEnumerate
	 */

	/**
	 * Wraps a {@link CollectionExpression} in a named computation.
	 *
	 * @param name the name of the computation for debugging
	 * @param expression the expression defining the computation
	 * @return a computation backed by the given expression
	 */
	default DefaultTraversableExpressionComputation compute(String name, CollectionExpression expression) {
		return new DefaultTraversableExpressionComputation(name, expression.getShape(), expression);
	}

	/**
	 * Creates a computation from a shape-dependent expression factory using no delta strategy.
	 *
	 * @param name the name of the computation
	 * @param expression a function from output shape to expression factory
	 * @param arguments the input producers for the computation
	 * @return a collection producer implementing the computation
	 */
	default CollectionProducer compute(
			String name, Function<TraversalPolicy, Function<TraversableExpression[], CollectionExpression>> expression,
			Producer<PackedCollection>... arguments) {
		return compute(name, DeltaFeatures.MultiTermDeltaStrategy.NONE, expression, arguments);
	}

	/**
	 * Creates a computation from a shape-dependent expression factory with a custom description function.
	 *
	 * @param name the name of the computation
	 * @param expression a function from output shape to expression factory
	 * @param description a function producing a human-readable description from child descriptions
	 * @param arguments the input producers for the computation
	 * @return a collection producer implementing the computation
	 */
	default CollectionProducer compute(
			String name, Function<TraversalPolicy, Function<TraversableExpression[], CollectionExpression>> expression,
			Function<List<String>, String> description,
			Producer<PackedCollection>... arguments) {
		return compute(name, DeltaFeatures.MultiTermDeltaStrategy.NONE, expression, description, arguments);
	}

	/**
	 * Creates a computation from a shape-dependent expression factory with an explicit delta strategy.
	 *
	 * @param name the name of the computation
	 * @param deltaStrategy the strategy for computing gradients
	 * @param expression a function from output shape to expression factory
	 * @param arguments the input producers for the computation
	 * @return a collection producer implementing the computation
	 */
	default CollectionProducer compute(
			String name, DeltaFeatures.MultiTermDeltaStrategy deltaStrategy,
			Function<TraversalPolicy, Function<TraversableExpression[], CollectionExpression>> expression,
			Producer<PackedCollection>... arguments) {
		return compute(name, deltaStrategy, expression, null, arguments);
	}

	/**
	 * Creates a computation from a shape-dependent expression factory with an explicit delta strategy and description.
	 *
	 * @param name the name of the computation
	 * @param deltaStrategy the strategy for computing gradients
	 * @param expression a function from output shape to expression factory
	 * @param description a function producing a human-readable description from child descriptions
	 * @param arguments the input producers for the computation
	 * @return a collection producer implementing the computation
	 */
	default CollectionProducer compute(
			String name, DeltaFeatures.MultiTermDeltaStrategy deltaStrategy,
			Function<TraversalPolicy, Function<TraversableExpression[], CollectionExpression>> expression,
			Function<List<String>, String> description, Producer<PackedCollection>... arguments) {
		return compute((shape, args) -> (Producer<PackedCollection>) new DefaultTraversableExpressionComputation(
				name, largestTotalSize(args), deltaStrategy, true, expression.apply(shape),
				args.toArray(Producer[]::new)), description, arguments);
	}

	/**
	 * Aligns traversal axes across the given arguments and applies the processor to produce
	 * a {@link CollectionProducer}. The resulting producer's traversal axis is adjusted so that
	 * its leading count matches the highest count among the arguments. An optional description
	 * function is attached for diagnostic and display purposes.
	 *
	 * @param <P> the type of producer returned by the processor
	 * @param processor a function from aligned shape and producers to the output producer
	 * @param description a function producing a human-readable description from argument names,
	 *                    or {@code null} if no description is needed
	 * @param arguments the input collection producers to align and process
	 * @return a {@link CollectionProducer} wrapping the computed result
	 */
	@Override
	default <P extends Producer<PackedCollection>> CollectionProducer compute(
				BiFunction<TraversalPolicy, List<Producer<PackedCollection>>, P> processor,
				Function<List<String>, String> description,
				Producer<PackedCollection>... arguments) {
		Producer<PackedCollection> c = alignTraversalAxes(List.of(arguments), processor);

		if (c instanceof CollectionProducerComputationBase) {
			((CollectionProducerComputationBase) c).setDescription(description);
		}

		// TODO  This should use outputShape, so that the calculation isn't
		// TODO  implemented in two separate places
		long count = highestCount(List.of(arguments));

		if (c instanceof Shape) {
			Shape<?> s = (Shape<?>) c;

			if (s.getShape().getCountLong() != count) {
				for (int i = 0; i <= s.getShape().getDimensions(); i++) {
					if (s.getShape().traverse(i).getCountLong() == count) {
						return c((Producer<PackedCollection>) s.traverse(i));
					}
				}
			}
		}

		return c(c);
	}

	/**
	 * Returns the index of the maximum value in the array.
	 *
	 * @param values the array to search
	 * @return the index of the maximum value, or -1 if the array is empty
	 */
	default int argmax(double[] values) {
		if (values.length == 0) return -1;

		int maxIdx = 0;
		double maxVal = values[0];
		for (int i = 1; i < values.length; i++) {
			if (values[i] > maxVal) {
				maxVal = values[i];
				maxIdx = i;
			}
		}
		return maxIdx;
	}

	/**
	 * Returns the indices of the top-k values in descending order.
	 *
	 * @param values the array to search
	 * @param k the number of top values to return
	 * @return list of indices of the top-k values
	 */
	default List<Integer> topK(double[] values, int k) {
		List<Integer> result = new ArrayList<>(k);
		boolean[] used = new boolean[values.length];

		for (int i = 0; i < Math.min(k, values.length); i++) {
			int maxIdx = -1;
			double maxVal = Double.NEGATIVE_INFINITY;
			for (int j = 0; j < values.length; j++) {
				if (!used[j] && values[j] > maxVal) {
					maxVal = values[j];
					maxIdx = j;
				}
			}
			if (maxIdx >= 0) {
				result.add(maxIdx);
				used[maxIdx] = true;
			}
		}

		return result;
	}

	/**
	 * Applies parentheses to each string in a list of expression arguments.
	 * Strings containing spaces are wrapped in parentheses; others are returned as-is.
	 * This is used when constructing human-readable descriptions of composed operations.
	 *
	 * @param args the list of argument description strings to process
	 * @return a new list with parentheses applied to each element where needed
	 */
	default List<String> applyParentheses(List<String> args) {
		return args.stream().map(this::applyParentheses).collect(Collectors.toList());
	}

	/**
	 * Wraps a single expression description string in parentheses if it contains spaces,
	 * ensuring unambiguous precedence in composed expression descriptions.
	 *
	 * @param value the expression description string to potentially parenthesize
	 * @return the original string, or the string wrapped in parentheses if it contains spaces
	 */
	default String applyParentheses(String value) {
		if (value.contains(" ")) {
			return "(" + value + ")";
		} else {
			return value;
		}
	}

	/**
	 * Returns a singleton-style anonymous instance of {@link CollectionFeatures} that provides
	 * access to all mixin methods without requiring a specific implementing class.
	 * Useful for one-off computations and tests where a full class hierarchy is not needed.
	 *
	 * @return a new anonymous {@link CollectionFeatures} instance
	 */
	static CollectionFeatures getInstance() {
		return new CollectionFeatures() { };
	}
}
