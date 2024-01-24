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

package org.almostrealism.collect.computations;

import io.almostrealism.collect.CollectionVariable;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.Expression;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;

import java.util.function.Supplier;
import java.util.function.UnaryOperator;

public class IndexProjectionProducerComputation<T extends PackedCollection<?>>
		extends KernelProducerComputationAdapter<PackedCollection<?>, T> {
	private UnaryOperator<Expression<?>> indexProjection;

	public IndexProjectionProducerComputation(TraversalPolicy shape, Producer<?> collection,
											  UnaryOperator<Expression<?>> indexProjection) {
		super(shape, (Supplier<Evaluable<? extends PackedCollection<?>>>) collection);
		this.indexProjection = indexProjection;
	}

	@Override
	public Expression<Double> getValueAt(Expression index) {
		CollectionVariable var = getCollectionArgumentVariable(1);
		if (var == null) return null;

		return var.getValueAt(projectIndex(index));
	}

	protected Expression<?> projectIndex(Expression<?> index) {
		return indexProjection.apply(index);
	}
}
