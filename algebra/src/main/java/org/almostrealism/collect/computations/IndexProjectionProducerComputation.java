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
import io.almostrealism.expression.Sum;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.MatrixFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.PackedCollection;

import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;

public class IndexProjectionProducerComputation<T extends PackedCollection<?>>
		extends KernelProducerComputationAdapter<PackedCollection<?>, T> {
	public static boolean enableChainDelta = true;

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

	public CollectionProducerComputation<PackedCollection<?>> getIndex() {
		int outSize = getShape().getTotalSize();
		int inSize = shape(getInputs().get(1)).getTotalSize();
		TraversalPolicy shape = shape(outSize, inSize);
		return compute(shape.traverse(),
					idx -> {
						Expression pos[] = shape.position(idx);
						return conditional(pos[0].eq(projectIndex(pos[1])), e(1), e(0));
					})
				.addDependentLifecycle(this);
	}

	@Override
	public CollectionProducer<T> delta(Producer<?> target) {
		if (enableChainDelta && getInputs().get(1) instanceof CollectionProducer) {
			TraversalPolicy outShape = getShape();
			TraversalPolicy inShape = shape(getInputs().get(1));
			TraversalPolicy targetShape = shape(target);

			int outSize = outShape.getTotalSize();
			int inSize = inShape.getTotalSize();
			int targetSize = targetShape.getTotalSize();

			TraversalPolicy deltaShape = shape(inSize, targetSize);
			TraversalPolicy overallShape = shape(outSize, targetSize);

			CollectionProducer<PackedCollection<?>> delta = ((CollectionProducer) getInputs().get(1)).delta(target);
			return (CollectionProducer<T>) new TraversableExpressionComputation<>(outShape.append(targetShape),
					(args, idx) -> {
						Expression pos[] = overallShape.position(idx);
						Expression projected = deltaShape.index(projectIndex(pos[0]), pos[1]);
						return args[1].getValueAt(projected);
					},
					delta).addDependentLifecycle(this);
		} else {
			return super.delta(target);
		}
	}
}
