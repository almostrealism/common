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

/**
 * Factory interface for creating and manipulating {@link TraversalPolicy} shapes.
 * This interface extends {@link ShapeFeatures} and provides the primary documented
 * API for shape creation and dimension manipulation in the Almost Realism framework.
 *
 * <p>Methods here cover:</p>
 * <ul>
 *   <li>Creating {@link TraversalPolicy} instances from integer or long dimensions</li>
 *   <li>Creating position policies</li>
 *   <li>Extracting shapes from {@link TraversableExpression} objects</li>
 *   <li>Querying element counts via {@code size()}</li>
 *   <li>Padding shapes to a target number of dimensions</li>
 * </ul>
 *
 * @author  Michael Murray
 * @see ShapeFeatures
 * @see TraversalPolicy
 * @see CollectionFeatures
 */
public interface TraversalPolicyFeatures extends ShapeFeatures {

	/**
	 * Extracts the {@link TraversalPolicy} shape from a {@link TraversableExpression}.
	 * This method is used to determine the shape of expressions used in
	 * computational graphs and operations.
	 *
	 * @param t the {@link TraversableExpression} to extract shape from
	 * @return the {@link TraversalPolicy} representing the expression's shape,
	 *         or {@link #shape(int...)} with a single element if no shape is available
	 *
	 * <pre>{@code
	 * // Create an expression with known shape
	 * TraversableExpression expr = new PackedCollectionMap(shape(2, 3), someProducer, mapper);
	 * TraversalPolicy extractedShape = shape(expr);
	 * // Result: shape with dimensions [2, 3]
	 * }</pre>
	 */
	@Override
	default TraversalPolicy shape(TraversableExpression t) {
		if (t instanceof Shape) {
			return ((Shape) t).getShape();
		} else {
			if (enableShapelessWarning) {
				console.warn(t.getClass() + " does not have a Shape");
			}

			return shape(1);
		}
	}

	/**
	 * Pads a {@link TraversalPolicy} shape with additional dimensions of length 1.
	 * This utility method adds dimensions to a shape until it reaches the target
	 * number of dimensions, useful for making shapes compatible for operations.
	 *
	 * @param shape the original shape to pad
	 * @param target the desired number of dimensions
	 * @return a new {@link TraversalPolicy} with the target number of dimensions
	 *
	 * <pre>{@code
	 * // Pad a 1D shape to 3D
	 * TraversalPolicy original = shape(5); // [5]
	 * TraversalPolicy padded = padDimensions(original, 3);
	 * // Result: shape [1, 1, 5] (padded at the beginning)
	 *
	 * // Pad a 2D shape to 4D
	 * TraversalPolicy matrix = shape(3, 4); // [3, 4]
	 * TraversalPolicy tensor = padDimensions(matrix, 4);
	 * // Result: shape [1, 1, 3, 4]
	 * }</pre>
	 */
	@Override
	default TraversalPolicy padDimensions(TraversalPolicy shape, int target) {
		return padDimensions(shape, 1, target);
	}

	/**
	 * Pads a {@link TraversalPolicy} shape with additional dimensions, but only if it has
	 * at least {@code min} dimensions. This overload provides more control by specifying a
	 * minimum number of dimensions that must be present before padding occurs.
	 *
	 * @param shape the original shape to pad
	 * @param min the minimum number of dimensions required before padding
	 * @param target the desired number of dimensions after padding
	 * @return a new {@link TraversalPolicy} with the target number of dimensions (if min is met)
	 *
	 * <pre>{@code
	 * // Only pad if shape has at least 2 dimensions
	 * TraversalPolicy small = shape(5); // [5] - only 1 dimension
	 * TraversalPolicy unchanged = padDimensions(small, 2, 4);
	 * // Result: [5] (unchanged because it has less than 2 dimensions)
	 *
	 * TraversalPolicy matrix = shape(3, 4); // [3, 4] - 2 dimensions
	 * TraversalPolicy expanded = padDimensions(matrix, 2, 4);
	 * // Result: [1, 1, 3, 4] (padded because it has >= 2 dimensions)
	 * }</pre>
	 */
	@Override
	default TraversalPolicy padDimensions(TraversalPolicy shape, int min, int target) {
		return padDimensions(shape, min, target, false);
	}

	/**
	 * Pads a {@link TraversalPolicy} shape with additional dimensions, with control over
	 * padding direction. This is the most flexible padding method, allowing you to specify
	 * whether padding dimensions should be added at the beginning (false) or end (true) of the shape.
	 *
	 * @param shape the original shape to pad
	 * @param min the minimum number of dimensions required before padding
	 * @param target the desired number of dimensions after padding
	 * @param post whether to append dimensions at the end (true) or prepend at the beginning (false)
	 * @return a new {@link TraversalPolicy} with the target number of dimensions
	 *
	 * <pre>{@code
	 * // Pad at the beginning (default behavior)
	 * TraversalPolicy original = shape(3, 4); // [3, 4]
	 * TraversalPolicy frontPadded = padDimensions(original, 1, 4, false);
	 * // Result: [1, 1, 3, 4] (padded at front)
	 *
	 * // Pad at the end
	 * TraversalPolicy backPadded = padDimensions(original, 1, 4, true);
	 * // Result: [3, 4, 1, 1] (padded at back)
	 *
	 * // Practical use: making tensor shapes compatible
	 * TraversalPolicy vector = shape(100); // [100]
	 * TraversalPolicy batchVector = padDimensions(vector, 1, 2, false);
	 * // Result: [1, 100] (adds batch dimension at front)
	 * }</pre>
	 */
	@Override
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
