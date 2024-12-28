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
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Computable;
import io.almostrealism.relation.Process;
import org.almostrealism.collect.CollectionProducerParallelProcess;
import org.almostrealism.collect.PackedCollection;

import java.util.List;
import java.util.Optional;

public class IdentityMatrixComputation<T extends PackedCollection<?>> extends ScalarMatrixComputation<T> {
	public IdentityMatrixComputation(TraversalPolicy shape) {
		super("identity", shape);
	}

	@Override
	protected CollectionExpression getExpression(TraversableExpression... args) {
		return ident(getShape().traverse(1));
	}

	@Override
	public boolean isZero() { return false; }

	@Override
	public boolean isIdentity(int width) {
		return width == getShape().length(0) && width == getShape().length(1);
	}

	@Override
	public Optional<Computable> getDiagonalScalar(int width) {
		if (isIdentity(width)) {
			return Optional.of(c(1.0));
		}

		return super.getDiagonalScalar(width);
	}

	@Override
	public CollectionProducerParallelProcess<T> generate(List<Process<?, ?>> children) {
		return this;
	}
}
