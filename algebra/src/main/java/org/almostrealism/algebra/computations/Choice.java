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
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.relation.Producer;
import io.almostrealism.expression.Expression;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.computations.TraversableExpressionComputation;

public class Choice<T extends PackedCollection<?>> extends TraversableExpressionComputation<T> {
	private int choiceCount;

	public Choice(TraversalPolicy shape, int choiceCount,
				  Producer<PackedCollection<?>> decision,
				  Producer<PackedCollection<?>> choices) {
		super("choice", shape, decision, adjustChoices(shape.getTotalSize(), choiceCount, choices));
		this.choiceCount = choiceCount;
	}

	@Override
	protected CollectionExpression getExpression(TraversableExpression... args) {
		return CollectionExpression.create(getShape(), idx -> {
			Expression choice = args[1].getValueAt(e(0)).multiply(choiceCount).floor();
			Expression pos = choice.multiply(getShape().getSize());
			return args[2].getValueAt(pos.add(idx));
		});
	}

	protected static <T extends PackedCollection<?>> Producer<PackedCollection<?>>
			adjustChoices(int memLength, int choiceCount, Producer<PackedCollection<?>> choices) {
		if (!(choices instanceof Shape)) return choices;

		TraversalPolicy shape = ((Shape) choices).getShape();
		if (shape.getCount() != choiceCount) {
			throw new IllegalArgumentException();
		} else if (shape.getSize() != memLength) {
			throw new IllegalArgumentException();
		}

		return choices;
	}
}
