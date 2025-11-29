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

package org.almostrealism.algebra.computations;

import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.CollectionProducerBase;
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.Expression;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.TraversableExpressionComputation;

/**
 * A computation that selects from multiple pre-computed options based on a decision value.
 *
 * <p>
 * {@link Choice} implements a dynamic selection mechanism where:
 * <ul>
 *   <li>A decision value (typically in range [0, 1]) determines which option to select</li>
 *   <li>The decision value is scaled by choiceCount and floored to get an integer index</li>
 *   <li>The corresponding option is extracted from the choices array</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Select from 3 pre-computed options based on a decision value
 * CollectionProducer<PackedCollection> decision = c(0.7);  // Will select option 2
 * CollectionProducer<PackedCollection> options = c(
 *     shape(3, 5),  // 3 options, each of size 5
 *     option1_data, option2_data, option3_data
 * );
 *
 * Choice choice = new Choice(
 *     shape(5),    // Result shape
 *     3,           // Number of choices
 *     decision,
 *     options
 * );
 *
 * // When decision = 0.0-0.33: selects option 0
 * // When decision = 0.33-0.66: selects option 1
 * // When decision = 0.66-1.0: selects option 2
 * }</pre>
 *
 * @author  Michael Murray
 * @see org.almostrealism.algebra.ScalarFeatures#choice(int, TraversalPolicy, Producer, Producer)
 */
public class Choice extends TraversableExpressionComputation {
	private final int choiceCount;

	/**
	 * Creates a new Choice computation.
	 *
	 * @param shape  the shape of the output (single choice result)
	 * @param choiceCount  the number of choices available
	 * @param decision  producer providing the decision value (typically in [0, 1])
	 * @param choices  producer providing all choice options (shape should be [choiceCount, resultSize])
	 * @throws IllegalArgumentException if the choices shape doesn't match expectations
	 */
	public Choice(TraversalPolicy shape, int choiceCount,
				  Producer<PackedCollection> decision,
				  Producer<PackedCollection> choices) {
		super("choice", shape, decision, adjustChoices(shape.getTotalSize(), choiceCount, choices));
		this.choiceCount = choiceCount;
	}

	/**
	 * Generates the expression that performs the choice selection.
	 *
	 * <p>
	 * The selection logic:
	 * <ol>
	 *   <li>Read decision value from args[1]</li>
	 *   <li>Scale by choiceCount and floor to get integer index</li>
	 *   <li>Calculate position in choices array: index * resultSize</li>
	 *   <li>Extract the selected option from choices</li>
	 * </ol>
	 * </p>
	 *
	 * @param args  traversable expressions [this, decision, choices]
	 * @return the collection expression for the selected choice
	 */
	@Override
	protected CollectionExpression getExpression(TraversableExpression... args) {
		return CollectionExpression.create(getShape(), idx -> {
			Expression choice = args[1].getValueAt(e(0)).multiply(choiceCount).floor();
			Expression pos = choice.toInt().multiply(getShape().getSize());
			return args[2].getValueAt(pos.add(idx));
		});
	}

	/**
	 * Validates and adjusts the choices producer to ensure it has the correct shape.
	 *
	 * @param memLength  expected size of each individual choice
	 * @param choiceCount  expected number of choices
	 * @param choices  the choices producer to validate
	 * @return the choices producer (unchanged if valid)
	 * @throws IllegalArgumentException if the choices shape doesn't match expectations
	 */
	protected static Producer<PackedCollection>
			adjustChoices(int memLength, int choiceCount, Producer<PackedCollection> choices) {
		if (!(choices instanceof Shape)) return choices;

		TraversalPolicy shape = ((Shape) choices).getShape();
		if (shape.getCount() != choiceCount) {
			if (shape.length(0) == choiceCount && shape.getTotalSize() / shape.length(0) == memLength) {
				return (Producer<PackedCollection>) ((CollectionProducerBase) choices).traverse(1);
			}
			throw new IllegalArgumentException();
		} else if (shape.getSize() != memLength) {
			throw new IllegalArgumentException();
		}

		return choices;
	}
}
