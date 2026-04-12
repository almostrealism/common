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

import java.util.List;

/**
 * Abstract base class for collection expressions that operate on one or more operand
 * {@link TraversableExpression} inputs.
 *
 * <p>Subclasses combine the operands in different ways (e.g. pointwise product, weighted sum,
 * conditional selection) and access the shared operand array directly or via
 * {@link #getOperands()}.</p>
 */
public abstract class OperandCollectionExpression extends CollectionExpressionAdapter {
	/** The operand expressions that supply input values to this expression. */
	protected TraversableExpression[] operands;

	/**
	 * Creates an operand collection expression with the given name, shape, and operands.
	 *
	 * @param name     a descriptive name for this expression (used for diagnostics)
	 * @param shape    the shape of the output collection
	 * @param operands the input operand expressions
	 */
	public OperandCollectionExpression(String name, TraversalPolicy shape,
									   TraversableExpression... operands) {
		super(name, shape);
		this.operands = operands;
	}

	/**
	 * Returns the list of operand expressions that supply input values.
	 *
	 * @return an unmodifiable list of operands
	 */
	public List<TraversableExpression<Double>> getOperands() {
		return List.of(operands);
	}

}
