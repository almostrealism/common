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

import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.hardware.MemoryDataComputation;
import org.almostrealism.io.Console;

import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

/**
 * Factory interface for creating and manipulating {@link TraversalPolicy} shapes.
 * This interface provides methods for creating shapes, positions, and extracting
 * shape information from various objects.
 *
 * <p>Shape operations are fundamental to the Almost Realism framework, as they
 * define how data is organized and accessed in collections.</p>
 *
 * @author Michael Murray
 * @see TraversalPolicy
 * @see CollectionFeatures
 */
public interface ShapeFeatures {
	boolean enableShapelessWarning = false;

	Console console = CollectionFeatures.console;

	/**
	 * Creates a new {@link TraversalPolicy} with the specified dimensions.
	 * This is one of the most fundamental methods for creating shapes that define
	 * how data is organized and accessed in collections.
	 *
	 * @param dims the dimensions of the shape (e.g., width, height, depth)
	 * @return a new {@link TraversalPolicy} representing the specified shape
	 */
	default TraversalPolicy shape(int... dims) {
		if (dims[0] == -1) {
			if (dims.length == 1) {
				return new TraversalPolicy(false, false, 1);
			}

			return new TraversalPolicy(false, false, IntStream.of(dims).skip(1).toArray());
		}

		return new TraversalPolicy(dims);
	}

	/**
	 * Creates a new {@link TraversalPolicy} with the specified dimensions using long values.
	 * This overload is useful when working with very large dimensions that exceed
	 * the range of int values.
	 *
	 * @param dims the dimensions of the shape as long values
	 * @return a new {@link TraversalPolicy} representing the specified shape
	 */
	default TraversalPolicy shape(long... dims) {
		if (dims[0] == -1) {
			if (dims.length == 1) {
				return new TraversalPolicy(false, false, 1);
			}

			return new TraversalPolicy(false, false, LongStream.of(dims).skip(1).toArray());
		}

		return new TraversalPolicy(dims);
	}

	/**
	 * Creates a position {@link TraversalPolicy} with the specified dimensions.
	 * Unlike regular shapes, positions are used to specify coordinates or offsets
	 * within a larger collection structure.
	 *
	 * @param dims the position coordinates
	 * @return a new {@link TraversalPolicy} representing the specified position
	 */
	default TraversalPolicy position(int... dims) {
		return new TraversalPolicy(true, dims);
	}

	/**
	 * Extracts the {@link TraversalPolicy} shape from a {@link Supplier}.
	 * This method is useful for determining the shape of collections at runtime
	 * by examining the supplier object.
	 *
	 * @param s the supplier to extract shape from
	 * @return the {@link TraversalPolicy} representing the supplier's shape, or shape(1) if no shape available
	 */
	default TraversalPolicy shape(Supplier s) {
		if (s instanceof Shape) {
			return ((Shape) s).getShape();
		} else {
			if (enableShapelessWarning) {
				console.warn(s.getClass() + " does not have a Shape");
			}

			return shape(1);
		}
	}

	/**
	 * Extracts the {@link TraversalPolicy} shape from a {@link TraversableExpression}.
	 * This method is used to determine the shape of expressions used in
	 * computational graphs and operations.
	 *
	 * @param t the {@link TraversableExpression} to extract shape from
	 * @return the {@link TraversalPolicy} representing the expression's shape, or shape(1) if no shape available
	 */
	default TraversalPolicy shape(TraversableExpression t) {
		if (t instanceof Shape) {
			return ((Shape) t).getShape();
		} else {
			if (enableShapelessWarning) {
				System.out.println("WARN: " + t.getClass() + " does not have a Shape");
			}

			return shape(1);
		}
	}

	/**
	 * Gets the number of elements that will be operated on by one thread of a kernel
	 * for the given {@link Supplier}. This is not the total number of elements that can be
	 * produced, but rather the number that will be processed by a single thread.
	 * The total number is the product of the size and the count.
	 *
	 * @param s the supplier to examine
	 * @return the total number of elements, or -1 if the supplier is null
	 */
	default int size(Supplier s) {
		if (s == null) {
			return -1;
		} else if (s instanceof MemoryDataComputation) {
			return ((MemoryDataComputation) s).getMemLength();
		} else {
			return shape(s).getSize();
		}
	}

	/**
	 * Gets the number of elements that will be operated on by a single thread
	 * of a kernel for a {@link Shape}. This is a convenient method for getting
	 * the size directly from objects that implement the {@link Shape} interface.
	 * The total number of elements is the product of this size and the count.
	 *
	 * @param s the {@link Shape} to examine
	 * @return the number of elements operated on by one thread
	 */
	default int size(Shape s) {
		return s.getShape().getSize();
	}

	/**
	 * Pads a {@link TraversalPolicy} shape with additional dimensions of length 1.
	 * This utility method adds dimensions to a shape until it reaches the target
	 * number of dimensions, useful for making shapes compatible for operations.
	 *
	 * @param shape the original shape to pad
	 * @param target the desired number of dimensions
	 * @return a new {@link TraversalPolicy} with the target number of dimensions
	 */
	default TraversalPolicy padDimensions(TraversalPolicy shape, int target) {
		return padDimensions(shape, 1, target);
	}

	/**
	 * Pads a {@link TraversalPolicy} shape with additional dimensions, but only if it has at least min dimensions.
	 * This overload provides more control by specifying a minimum number of dimensions
	 * that must be present before padding occurs.
	 *
	 * @param shape the original shape to pad
	 * @param min the minimum number of dimensions required before padding
	 * @param target the desired number of dimensions after padding
	 * @return a new {@link TraversalPolicy} with the target number of dimensions (if min is met)
	 */
	default TraversalPolicy padDimensions(TraversalPolicy shape, int min, int target) {
		return padDimensions(shape, min, target, false);
	}

	/**
	 * Pads a {@link TraversalPolicy} shape with additional dimensions, with control over padding direction.
	 * This is the most flexible padding method, allowing you to specify whether
	 * padding dimensions should be added at the beginning (false) or end (true) of the shape.
	 *
	 * @param shape the original shape to pad
	 * @param min the minimum number of dimensions required before padding
	 * @param target the desired number of dimensions after padding
	 * @param post whether to append dimensions at the end (true) or prepend at the beginning (false)
	 * @return a new {@link TraversalPolicy} with the target number of dimensions
	 */
	default TraversalPolicy padDimensions(TraversalPolicy shape, int min, int target, boolean post) {
		if (shape.getDimensions() < min) {
			return shape;
		}

		while (shape.getDimensions() < target) {
			shape = post ? shape.appendDimension(1) : shape.prependDimension(1);
		}

		return shape;
	}
}
