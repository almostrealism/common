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

package io.almostrealism.collect;

/**
 * A {@link UniformCollectionExpression} that converts multi-dimensional positions
 * from an operand into flat indices within a reference shape.
 *
 * <p>Given a position expressed as a set of operand values, this expression computes
 * the corresponding linear index in the provided {@code shapeOf} shape by delegating
 * to {@link TraversalPolicy#index}. This is used when an operand encodes coordinates
 * that need to be looked up in another collection by their flat offset.</p>
 */
public class IndexOfPositionExpression extends UniformCollectionExpression {
	/**
	 * Creates an expression that maps operand positions to flat indices in the given shape.
	 *
	 * @param shape    the output shape of this expression
	 * @param shapeOf  the reference shape used to compute flat indices from positions
	 * @param operands the operand expressions supplying the position values
	 */
	public IndexOfPositionExpression(TraversalPolicy shape, TraversalPolicy shapeOf,
									 TraversableExpression... operands) {
		super("index", shape, shapeOf::index, operands);
	}
}
