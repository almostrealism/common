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

import io.almostrealism.expression.IntegerConstant;

/**
 * A {@link DiagonalCollectionExpression} that represents the identity matrix.
 *
 * <p>Every diagonal element is {@code 1} and every off-diagonal element is {@code 0},
 * corresponding to the identity transformation in the vector space described by the
 * given shape.</p>
 */
public class IdentityCollectionExpression extends DiagonalCollectionExpression {
	/**
	 * Creates an identity collection expression with the given square shape.
	 *
	 * @param shape the shape of the identity matrix (must be a square matrix shape)
	 */
	public IdentityCollectionExpression(TraversalPolicy shape) {
		super("identity", shape, new ConstantCollectionExpression(shape.item(), new IntegerConstant(1)));
	}
}
