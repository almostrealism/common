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

import io.almostrealism.collect.Algebraic;
import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.ConstantCollectionExpression;
import io.almostrealism.collect.DiagonalCollectionExpression;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Computable;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducerParallelProcess;
import org.almostrealism.collect.PackedCollection;

import java.util.List;
import java.util.Optional;

public class ScalarMatrixComputation<T extends PackedCollection<?>> extends MatrixExpressionComputation<T> {
	public ScalarMatrixComputation(TraversalPolicy shape, Producer<? extends PackedCollection<?>> scalar) {
		this("scalarMatrix", shape, scalar);
	}

	public ScalarMatrixComputation(String name, TraversalPolicy shape, Producer<? extends PackedCollection<?>> scalar) {
		super(name, shape, scalar);

		if (shape.getTotalSizeLong() == 1) {
			warn("ScalarMatrixComputation will be identical to input");
		}

		if (shape(scalar).getTotalSize() != 1) {
			throw new IllegalArgumentException();
		}
	}

	protected ScalarMatrixComputation(String name, TraversalPolicy shape) {
		super(name, shape);
	}

	@Override
	protected CollectionExpression getExpression(TraversableExpression... args) {
		ConstantCollectionExpression scalar = new ConstantCollectionExpression(shape(1), args[1].getValueAt(e(0)));
		return new DiagonalCollectionExpression(getShape(), scalar);
	}

	@Override
	public boolean isZero() {
		return super.isZero() || Algebraic.isZero(getInputs().get(1));
	}

	@Override
	public boolean isDiagonal(int width) {
		return super.isDiagonal(width) || width == getShape().length(0);
	}

	@Override
	public Optional<Computable> getDiagonalScalar(int width) {
		if (width == getShape().length(0)) {
			return Optional.of((Computable) getInputs().get(1));
		}

		return super.getDiagonalScalar(width);
	}

	@Override
	public CollectionProducerParallelProcess<T> generate(List<Process<?, ?>> children) {
		return new ScalarMatrixComputation<>(getShape(), (Producer) children.get(1));
	}
}
