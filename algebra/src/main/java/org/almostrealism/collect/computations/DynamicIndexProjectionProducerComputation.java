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
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.Expression;
import io.almostrealism.compute.Process;
import io.almostrealism.kernel.Index;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Supplier;

public class DynamicIndexProjectionProducerComputation<T extends PackedCollection<?>>
		extends IndexProjectionProducerComputation<T> {
	public static boolean enableDeltaTraverseEach = false;
	public static boolean enableChainDelta = false;

	private BiFunction<TraversableExpression[], Expression, Expression> indexExpression;
	private boolean relative;

	public DynamicIndexProjectionProducerComputation(String name, TraversalPolicy shape,
													 BiFunction<TraversableExpression[], Expression, Expression> indexExpression,
													 Producer<?> collection,
													 Producer<?>... inputs) {
		this(name, shape, indexExpression, false, collection, inputs);
	}

	public DynamicIndexProjectionProducerComputation(String name, TraversalPolicy shape,
													 BiFunction<TraversableExpression[], Expression, Expression> indexExpression,
													 boolean relative,
													 Producer<?> collection,
													 Producer<?>... inputs) {
		super(name, shape, null, collection, inputs);
		this.indexExpression = indexExpression;
		this.relative = relative;
	}

	@Override
	protected boolean isOutputRelative() { return relative || super.isOutputRelative(); }

	@Override
	public Expression<Double> getValueAt(Expression index) {
		if (relative) {
			TraversableExpression<Double> var = getTraversableArguments(index)[1];
			if (var == null) return null;

			return var.getValueRelative(projectIndex(var, index));
		}

		return super.getValueAt(index);
	}

	@Override
	public Expression uniqueNonZeroOffset(Index globalIndex, Index localIndex, Expression<?> targetIndex) {
		if (relative) {
			TraversableExpression var = getTraversableArguments(targetIndex)[1];
			if (var == null) return null;

			return var.uniqueNonZeroOffset(globalIndex, localIndex, projectIndex(var, targetIndex));
		}

		return super.uniqueNonZeroOffset(globalIndex, localIndex, targetIndex);
	}

	@Override
	protected Expression<?> projectIndex(TraversableExpression<?> input, Expression<?> index) {
		return projectIndex(getTraversableArguments(index), index);
	}

	protected Expression<?> projectIndex(TraversableExpression[] args, Expression<?> index) {
		return indexExpression.apply(args, index);
	}

	@Override
	public DynamicIndexProjectionProducerComputation<T> generate(List<Process<?, ?>> children) {
		return (DynamicIndexProjectionProducerComputation)
				new DynamicIndexProjectionProducerComputation<>(getName(), getShape(), indexExpression, relative,
							(Producer<?>) children.get(1),
							children.stream().skip(2).toArray(Producer[]::new))
						.addAllDependentLifecycles(getDependentLifecycles());
	}

	@Override
	public CollectionProducer<T> delta(Producer<?> target) {
		if (enableChainDelta) {
			TraversableDeltaComputation<T> delta =
					TraversableDeltaComputation.create("delta", getShape(), shape(target),
								args -> CollectionExpression.create(getShape(),
										(idx) -> args[1].getValueAt(projectIndex(args, idx))),
							target, getInputs().stream().skip(1).toArray(Supplier[]::new));
			return delta;
		} else {
			TraversalPolicy outShape = getShape();
			TraversalPolicy inShape = shape(getInputs().get(1));
			TraversalPolicy targetShape = shape(target);

			int outSize = outShape.getTotalSize();
			int inSize = inShape.getTotalSize();
			int targetSize = targetShape.getTotalSize();

			TraversalPolicy deltaShape = shape(inSize, targetSize);
			TraversalPolicy overallShape = shape(outSize, targetSize);

			CollectionProducer<PackedCollection<?>> delta = ((CollectionProducer) getInputs().get(1)).delta(target);

			TraversalPolicy shape = outShape.append(targetShape);
			int traversalAxis = shape.getTraversalAxis();

			BiFunction<TraversableExpression[], Expression, Expression> project = (args, idx) -> {
				Expression pos[] = overallShape.position(idx);
				return deltaShape.index(projectIndex(args, pos[0]), pos[1]);
			};

			if (enableDeltaTraverseEach) {
				return traverse(traversalAxis,
						new DynamicIndexProjectionProducerComputation(
								getName() + "DeltaIndex", shape.traverseEach(), project, relative, delta,
								getInputs().stream().skip(2).toArray(Producer[]::new)));
			} else {
				return new DynamicIndexProjectionProducerComputation(
						getName() + "DeltaIndex", shape, project, relative, delta,
						getInputs().stream().skip(2).toArray(Producer[]::new));
			}
		}
	}
}
