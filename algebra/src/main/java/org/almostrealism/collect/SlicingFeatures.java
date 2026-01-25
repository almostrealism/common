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

import io.almostrealism.collect.Algebraic;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.Expression;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.computations.CollectionPermute;
import org.almostrealism.collect.computations.PackedCollectionEnumerate;
import org.almostrealism.collect.computations.PackedCollectionMap;
import org.almostrealism.collect.computations.PackedCollectionPad;
import org.almostrealism.collect.computations.PackedCollectionRepeat;
import org.almostrealism.collect.computations.PackedCollectionSubset;
import org.almostrealism.collect.computations.SingleConstantComputation;

import java.util.function.Function;

/**
 * Factory interface for slicing, subsetting, and transformation operations on collections.
 * This interface provides methods for extracting subsets, repeating, enumerating,
 * permuting, padding, and mapping collections.
 *
 * @author Michael Murray
 * @see CollectionFeatures
 * @see TraversalPolicy
 */
public interface SlicingFeatures extends CollectionTraversalFeatures {

	/**
	 * Creates a subset computation that extracts a sub-collection using static integer positions.
	 *
	 * @param shape The desired shape/dimensions of the resulting subset
	 * @param collection The source collection to extract from
	 * @param position The starting position coordinates (one integer per dimension)
	 * @return A CollectionProducerComputation that will produce the subset when evaluated
	 */
	default CollectionProducerComputation subset(TraversalPolicy shape, Producer<?> collection, int... position) {
		return new PackedCollectionSubset(shape, collection, position);
	}

	/**
	 * Creates a subset computation using expression-based positions.
	 *
	 * @param shape The desired shape/dimensions of the resulting subset
	 * @param collection The source collection to extract from
	 * @param position The starting position coordinates as expressions
	 * @return A CollectionProducerComputation that will produce the subset when evaluated
	 */
	default CollectionProducerComputation subset(TraversalPolicy shape, Producer<?> collection, Expression... position) {
		return new PackedCollectionSubset(shape, collection, position);
	}

	/**
	 * Creates a subset computation with fully dynamic positions provided by another Producer.
	 *
	 * @param shape The desired shape/dimensions of the resulting subset
	 * @param collection The source collection to extract from
	 * @param position A Producer that generates position coordinates at runtime
	 * @return A CollectionProducerComputation that will produce the subset when evaluated
	 */
	default CollectionProducerComputation subset(TraversalPolicy shape, Producer<?> collection, Producer<?> position) {
		return new PackedCollectionSubset(shape, collection, position);
	}

	/**
	 * Creates a computation that repeats a collection a specified number of times.
	 *
	 * @param repeat the number of times to repeat the collection
	 * @param collection the source collection to repeat
	 * @return a computation that produces the repeated collection
	 */
	default CollectionProducerComputation repeat(int repeat, Producer<?> collection) {
		if (collection instanceof SingleConstantComputation) {
			return ((SingleConstantComputation) collection)
					.reshape(PackedCollectionRepeat.shape(repeat, shape(collection)));
		}

		return new PackedCollectionRepeat(repeat, collection);
	}

	/**
	 * Creates a computation that repeats a collection along a specific axis.
	 *
	 * @param axis the axis along which to perform repetition
	 * @param repeat the number of times to repeat
	 * @param collection the source collection to repeat
	 * @return a computation that produces the repeated collection
	 */
	default CollectionProducerComputation repeat(int axis, int repeat, Producer<PackedCollection> collection) {
		return repeat(repeat, traverse(axis, collection));
	}

	/**
	 * Creates an enumeration of a collection along a specific axis with specified length.
	 *
	 * @param axis the axis along which to enumerate (0-based)
	 * @param len the length of each enumerated sequence
	 * @param collection the collection to enumerate
	 * @return a {@link CollectionProducerComputation} containing the enumerated sequences
	 */
	default CollectionProducerComputation enumerate(int axis, int len, Producer<?> collection) {
		return enumerate(axis, len, len, collection);
	}

	/**
	 * Creates an enumeration with custom stride between elements.
	 *
	 * @param axis the axis along which to enumerate
	 * @param len the length of each enumerated sequence
	 * @param stride the step size between consecutive sequence starts
	 * @param collection the collection to enumerate
	 * @return a {@link CollectionProducerComputation} containing the enumerated sequences
	 */
	default CollectionProducerComputation enumerate(int axis, int len, int stride, Producer<?> collection) {
		return enumerate(axis, len, stride, 1, collection);
	}

	/**
	 * Creates multiple levels of enumeration with repetition.
	 *
	 * @param axis the axis along which to enumerate
	 * @param len the length of each enumerated sequence
	 * @param stride the step size between consecutive sequence starts
	 * @param repeat the number of times to repeat the enumeration process
	 * @param collection the collection to enumerate
	 * @return a {@link CollectionProducerComputation} containing the multi-level enumerated sequences
	 */
	default CollectionProducerComputation enumerate(int axis, int len, int stride, int repeat, Producer<?> collection) {
		CollectionProducerComputation result = null;

		TraversalPolicy inputShape = shape(collection);
		int traversalDepth = PackedCollectionEnumerate.enableDetectTraversalDepth ? inputShape.getTraversalAxis() : 0;

		inputShape = inputShape.traverse(traversalDepth).item();
		axis = axis - traversalDepth;

		for (int i = 0; i < repeat; i++) {
			TraversalPolicy shp = inputShape.traverse(axis).replaceDimension(len);
			TraversalPolicy st = inputShape.traverse(axis).stride(stride);
			result = enumerate(shp, st, result == null ? collection : result);
			inputShape = shape(result).traverse(traversalDepth).item();
		}

		return result;
	}

	/**
	 * Creates an enumeration using explicit {@link TraversalPolicy} shapes.
	 *
	 * @param shape the {@link TraversalPolicy} defining the subset shape
	 * @param collection the collection to enumerate
	 * @return a {@link CollectionProducerComputation} containing the enumerated sequences
	 */
	default CollectionProducerComputation enumerate(TraversalPolicy shape, Producer<?> collection) {
		PackedCollectionEnumerate enumerate = new PackedCollectionEnumerate(shape, collection);

		if (Algebraic.isZero(enumerate)) {
			return zeros(enumerate.getShape());
		}

		return enumerate;
	}

	/**
	 * Creates an enumeration using explicit {@link TraversalPolicy} shapes for both subset and stride patterns.
	 *
	 * @param shape the {@link TraversalPolicy} defining the subset shape
	 * @param stride the {@link TraversalPolicy} defining the stride pattern
	 * @param collection the collection to enumerate
	 * @return a {@link CollectionProducerComputation} containing the enumerated sequences
	 */
	default CollectionProducerComputation enumerate(TraversalPolicy shape, TraversalPolicy stride, Producer<?> collection) {
		PackedCollectionEnumerate enumerate = new PackedCollectionEnumerate(shape, stride, collection);

		if (Algebraic.isZero(enumerate)) {
			return zeros(enumerate.getShape());
		}

		return enumerate;
	}

	/**
	 * Creates a computation that permutes (reorders) the dimensions of a collection.
	 *
	 * @param collection The input collection to permute
	 * @param order The dimension permutation order
	 * @return A computation that produces the permuted collection
	 */
	default CollectionProducerComputation permute(Producer<?> collection, int... order) {
		if (Algebraic.isZero(collection)) {
			return zeros(shape(collection).permute(order).extentShape());
		}

		return new CollectionPermute(collection, order);
	}

	/**
	 * Pads a collection along specified axes with a uniform depth.
	 *
	 * @param axes Array of axis indices to pad (0-based)
	 * @param depth Amount of padding to add on each side
	 * @param collection The input collection to pad
	 * @return A CollectionProducerComputation that produces the padded collection
	 */
	default CollectionProducerComputation pad(int[] axes, int depth, Producer<?> collection) {
		TraversalPolicy shape = shape(collection);
		if (shape.getOrder() != null) {
			throw new UnsupportedOperationException();
		}

		int[] depths = new int[shape.getDimensions()];
		for (int i = 0; i < axes.length; i++) {
			depths[axes[i]] = depth;
		}

		return pad(collection, depths);
	}

	/**
	 * Pads a collection with specified depths for each dimension.
	 *
	 * @param collection The input collection to pad
	 * @param depths Padding depth for each dimension
	 * @return A CollectionProducerComputation that produces the padded collection
	 */
	default CollectionProducerComputation pad(Producer<?> collection, int... depths) {
		TraversalPolicy shape = shape(collection);

		int[] dims = new int[shape.getDimensions()];
		for (int i = 0; i < dims.length; i++) {
			dims[i] = shape.length(i) + 2 * depths[i];
		}

		shape = new TraversalPolicy(dims).traverse(shape.getTraversalAxis());
		return pad(shape, new TraversalPolicy(true, depths), collection);
	}

	/**
	 * Pads a collection to a specific output shape with specified positioning.
	 *
	 * @param shape The desired output shape after padding
	 * @param collection The input collection to pad
	 * @param pos Position offsets for placing the input within the output shape
	 * @return A CollectionProducer that produces the padded collection
	 */
	default CollectionProducer pad(TraversalPolicy shape, Producer<?> collection, int... pos) {
		int traversalAxis = shape.getTraversalAxis();

		if (traversalAxis == shape.getDimensions()) {
			return pad(shape, new TraversalPolicy(true, pos), collection);
		} else {
			return pad(shape.traverseEach(), new TraversalPolicy(true, pos), collection)
					.traverse(traversalAxis);
		}
	}

	/**
	 * Creates a PackedCollectionPad computation with explicit shape and position policies.
	 *
	 * @param shape The complete output shape specification
	 * @param position The positioning policy specifying where input data is placed
	 * @param collection The input collection producer
	 * @return A CollectionProducerComputation that implements the padding operation
	 */
	default CollectionProducerComputation pad(TraversalPolicy shape, TraversalPolicy position, Producer<?> collection) {
		if (Algebraic.isZero(collection)) {
			return zeros(shape);
		}

		return new PackedCollectionPad(shape, position, collection);
	}

	default CollectionProducerComputation map(
			Producer<?> collection,
			Function<CollectionProducerComputation, CollectionProducer> mapper) {
		return new PackedCollectionMap(collection, mapper);
	}

	default CollectionProducerComputation map(
			TraversalPolicy itemShape, Producer<?> collection,
			Function<CollectionProducerComputation, CollectionProducer> mapper) {
		return new PackedCollectionMap(shape(collection).replace(itemShape), collection, mapper);
	}

	default CollectionProducerComputation reduce(
			Producer<?> collection,
			Function<CollectionProducerComputation, CollectionProducer> mapper) {
		return map(shape(1), collection, mapper);
	}

	default CollectionProducer cumulativeProduct(Producer<PackedCollection> input, boolean pad) {
		return func(shape(input), inputs -> args -> {
			PackedCollection in = inputs[0];
			PackedCollection result = new PackedCollection(in.getShape());

			double r = 1.0;
			int offset = 0;

			if (pad) {
				result.setMem(0, r);
				offset = 1;
			}

			for (int i = offset; i < in.getMemLength(); i++) {
				r *= in.toDouble(i - offset);
				result.setMem(i, r);
			}

			return result;
		}, input);
	}

	// Required for internal use
	CollectionProducerComputation zeros(TraversalPolicy shape);
	CollectionProducer func(TraversalPolicy shape, Function<PackedCollection[], Function<Object[], PackedCollection>> function, Producer<?> argument, Producer<?>... args);
}
