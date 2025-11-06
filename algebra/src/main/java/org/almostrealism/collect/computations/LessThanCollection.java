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

package org.almostrealism.collect.computations;

import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducerParallelProcess;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;

import java.util.List;

public class LessThanCollection<T extends PackedCollection<?>> extends CollectionComparisonComputation<T> {
	private boolean includeEqual;

	public LessThanCollection(
			TraversalPolicy shape,
			Producer leftOperand,
			Producer rightOperand,
			Producer trueValue,
			Producer falseValue) {
		this(shape, leftOperand, rightOperand, trueValue, falseValue, false);
	}

	public LessThanCollection(
			TraversalPolicy shape,
			Producer<PackedCollection<?>> left, Producer<PackedCollection<?>> right,
			Producer<PackedCollection<?>> trueValue, Producer<PackedCollection<?>> falseValue,
			boolean includeEqual) {
		super("lessThan", shape,  left, right, trueValue, falseValue);
		this.includeEqual = includeEqual;
	}

	@Override
	protected CollectionExpression getExpression(TraversableExpression... args) {
		if (includeEqual) {
			return CollectionExpression.create(getShape(), index ->
					conditional(args[1].getValueAt(index).lessThanOrEqual(args[2].getValueAt(index)),
							args[3].getValueAt(index), args[4].getValueAt(index)));
		} else {
			return CollectionExpression.create(getShape(), index ->
					conditional(args[1].getValueAt(index).lessThan(args[2].getValueAt(index)),
							args[3].getValueAt(index), args[4].getValueAt(index)));
		}
	}

	@Override
	public CollectionProducerParallelProcess<T> generate(List<Process<?, ?>> children) {
		return (CollectionProducerParallelProcess)
				lessThan((Producer) children.get(1), (Producer) children.get(2),
						(Producer) children.get(3), (Producer) children.get(4), includeEqual);
	}
}
