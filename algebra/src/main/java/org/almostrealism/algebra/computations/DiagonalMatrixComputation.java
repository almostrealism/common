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

package org.almostrealism.algebra.computations;

import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.DiagonalCollectionExpression;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducerParallelProcess;
import org.almostrealism.collect.PackedCollection;

import java.util.List;

public class DiagonalMatrixComputation<T extends PackedCollection<?>> extends MatrixExpressionComputation<T> {
	public DiagonalMatrixComputation(TraversalPolicy shape, Producer<T> values) {
		this("diagonal", shape, values);
	}

	public DiagonalMatrixComputation(String name, TraversalPolicy shape, Producer<T> values) {
		super(name, shape, values);
	}

	@Override
	protected CollectionExpression getExpression(TraversableExpression... args) {
		return new DiagonalCollectionExpression(getShape(), args[1]);
	}

	@Override
	public boolean isDiagonal(int width) {
		return super.isDiagonal(width) || width == getShape().length(0);
	}

	@Override
	public CollectionProducerParallelProcess<T> generate(List<Process<?, ?>> children) {
		return (CollectionProducerParallelProcess) diagonal((Producer) children.get(1));
	}
}
