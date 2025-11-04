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

package org.almostrealism.bool;

import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducerParallelProcess;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.computations.CollectionComparisonComputation;

import java.util.List;

/**
 * Performs element-wise logical AND operation on two collections.
 * Returns trueValue if both operands are non-zero (considered true),
 * otherwise returns falseValue.
 */
public class CollectionConjunctionComputation<T extends PackedCollection<?>> extends CollectionComparisonComputation<T> {

	public CollectionConjunctionComputation(
			TraversalPolicy shape,
			Producer leftOperand,
			Producer rightOperand,
			Producer trueValue,
			Producer falseValue) {
		super("and", shape, leftOperand, rightOperand, trueValue, falseValue);
	}

	@Override
	protected CollectionExpression getExpression(TraversableExpression... args) {
		// args[1] = left operand (a), args[2] = right operand (b)
		// args[3] = trueValue, args[4] = falseValue
		// Returns trueValue if both a != 0.0 AND b != 0.0, otherwise returns falseValue
		// Check if a != 0 and b != 0 by using eq().not()
		return CollectionExpression.create(getShape(), index ->
				conditional(
						args[1].getValueAt(index).eq(e(0.0)).not()
								.and(args[2].getValueAt(index).eq(e(0.0)).not()),
						args[3].getValueAt(index),
						args[4].getValueAt(index)));
	}

	@Override
	public CollectionProducerParallelProcess<T> generate(List<Process<?, ?>> children) {
		return (CollectionProducerParallelProcess)
				and((Producer) children.get(1), (Producer) children.get(2),
						(Producer) children.get(3), (Producer) children.get(4));
	}
}
