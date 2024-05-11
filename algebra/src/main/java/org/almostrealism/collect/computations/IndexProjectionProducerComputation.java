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

import io.almostrealism.code.CollectionUtils;
import io.almostrealism.collect.IndexProjectionCollectionExpression;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.Index;
import io.almostrealism.relation.Computable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.ParallelProcess;
import io.almostrealism.relation.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.PackedCollection;

import java.util.List;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

public class IndexProjectionProducerComputation<T extends PackedCollection<?>>
		extends CollectionProducerComputationAdapter<PackedCollection<?>, T> {
	public static boolean enableDelegatedIsolate = true;
	public static boolean enableInputIsolate = false;

	private UnaryOperator<Expression<?>> indexProjection;
	protected boolean relative;

	public IndexProjectionProducerComputation(TraversalPolicy shape,
											  UnaryOperator<Expression<?>> indexProjection,
											  Producer<?> collection) {
		this(shape, indexProjection, false, collection, new Producer[0]);
	}

	public IndexProjectionProducerComputation(TraversalPolicy shape,
											  UnaryOperator<Expression<?>> indexProjection,
											  boolean relative,
											  Producer<?> collection) {
		this(shape, indexProjection, relative, collection, new Producer[0]);
	}

	protected IndexProjectionProducerComputation(TraversalPolicy shape,
											  UnaryOperator<Expression<?>> indexProjection,
											  boolean relative,
											  Producer<?> collection,
											  Producer<?>... inputs) {
		super(shape, CollectionUtils.include(new Supplier[0], (Supplier) collection, (Supplier[]) inputs));
		this.indexProjection = indexProjection;
		this.relative = relative;
	}

	@Override
	public boolean isConstant() {
		if (getInputs().get(1) instanceof Computable) {
			return ((Computable) getInputs().get(1)).isConstant();
		}

		return false;
	}

	@Override
	public Expression<Double> getValueAt(Expression index) {
		if (relative) {
			TraversableExpression var = getTraversableArguments(index)[1];
			if (var == null) return null;

			return var.getValueRelative(projectIndex(var, index));
		} else {
			TraversableExpression var = getCollectionArgumentVariable(1);
			if (var == null) return null;

			return var.getValueAt(projectIndex(var, index));
		}
	}

	@Override
	public Expression uniqueNonZeroOffset(Index globalIndex, Index localIndex, Expression<?> targetIndex) {
		if (relative) {
			TraversableExpression var = getTraversableArguments(targetIndex)[1];
			if (var == null) return null;

			return var.uniqueNonZeroOffset(globalIndex, localIndex, projectIndex(var, targetIndex));
		} else {
			TraversableExpression var = getCollectionArgumentVariable(1);
			if (var == null) return null;

			return var.uniqueNonZeroOffset(globalIndex, localIndex, projectIndex(var, targetIndex));
		}
	}

	protected Expression<?> projectIndex(TraversableExpression<?> input, Expression<?> index) {
		return projectIndex(index);
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

	private Process<Process<?, ?>, Evaluable<? extends T>> isolateForce() {
		return super.isolate();
	}

	private Process<Process<?, ?>, Evaluable<? extends T>> isolateInput() {
		IndexProjectionProducerComputation c;

		if (getInputs().get(1) instanceof IndexProjectionProducerComputation) {
			c = (IndexProjectionProducerComputation) ((IndexProjectionProducerComputation) getInputs().get(1)).isolateInput();
		} else {
			c = (IndexProjectionProducerComputation)
					generate((List) getInputs().stream().map(Process::isolated).collect(Collectors.toList()));
		}

		return c;
	}

	@Override
	public Process<Process<?, ?>, Evaluable<? extends T>> isolate() {
		if (enableDelegatedIsolate && isConstant()) {
			IndexProjectionProducerComputation c;

			if (enableInputIsolate && getInputs().get(1) instanceof IndexProjectionProducerComputation) {
				c = (IndexProjectionProducerComputation) ((IndexProjectionProducerComputation) getInputs().get(1)).isolateInput();
			} else {
				c = (IndexProjectionProducerComputation)
						generate((List) getInputs().stream().map(Process::isolated).collect(Collectors.toList()));
			}

			return c.isolateForce();
		}

		return super.isolate();
	}

	@Override
	public ParallelProcess<Process<?, ?>, Evaluable<? extends T>> generate(List<Process<?, ?>> children) {
		return new IndexProjectionProducerComputation<>(getShape(), indexProjection, relative,
				(Producer<?>) children.get(1),
				children.stream().skip(2).toArray(Producer[]::new));
	}

	@Override
	public CollectionProducer<T> delta(Producer<?> target) {
		if (getInputs().get(1) instanceof CollectionProducer) {
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

			UnaryOperator<Expression<?>> project = idx -> {
				Expression pos[] = overallShape.position(idx);
				return deltaShape.index(projectIndex(pos[0]), pos[1]);
			};

			return new IndexProjectionProducerComputation<>(shape, project, false, delta);
		}

		return super.delta(target);
	}
}
