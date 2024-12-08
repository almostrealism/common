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

package org.almostrealism.calculus;

import io.almostrealism.code.OperationInfo;
import io.almostrealism.code.OperationMetadata;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.Expression;
import io.almostrealism.relation.Countable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.ParallelProcess;
import io.almostrealism.relation.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

import java.util.Collection;
import java.util.List;

// TODO  This should probably extend DelegatedProducer
public class InputStub<T extends PackedCollection<?>> implements CollectionProducer<T>,
														ParallelProcess<Process<?, ?>, Evaluable<? extends T>>,
														TraversableExpression<Double>, OperationInfo {
	private final Producer<T> producer;
	private final OperationMetadata metadata;

	public InputStub(Producer<T> producer) {
		this.producer = producer;
		this.metadata = new OperationMetadata("stub", "stub");
	}

	public OperationMetadata getMetadata() { return metadata; }

	@Override
	public long getParallelism() {
		return ParallelProcess.parallelism(producer);
	}

	@Override
	public long getCountLong() {
		return CollectionProducer.super.getCountLong();
	}

	@Override
	public boolean isFixedCount() {
		return Countable.isFixedCount(producer);
	}

	@Override
	public TraversalPolicy getShape() {
		return shape(producer);
	}

	@Override
	public Expression<Double> getValueAt(Expression<?> index) {
		return null;
	}

	@Override
	public Expression<Boolean> containsIndex(Expression<Integer> index) {
		return CollectionProducer.super.containsIndex(index);
	}

	@Override
	public CollectionProducer<T> reshape(TraversalPolicy shape) {
		if (producer instanceof CollectionProducer) {
			return new InputStub<>(((CollectionProducer<T>) producer).reshape(shape));
		}

		throw new UnsupportedOperationException();
	}

	@Override
	public CollectionProducer<T> traverse(int axis) {
		if (producer instanceof CollectionProducer) {
			return new InputStub<>(((CollectionProducer<T>) producer).traverse(axis));
		}

		throw new UnsupportedOperationException();
	}

	@Override
	public Collection<Process<?, ?>> getChildren() {
		return List.of((Process<?, ?>) producer);
	}

	@Override
	public ParallelProcess<Process<?, ?>, Evaluable<? extends T>> generate(List<Process<?, ?>> children) {
		return new InputStub<>((Producer) children.iterator().next());
	}

	@Override
	public Evaluable<T> get() {
		return producer.get();
	}

	@Override
	public String describe() {
		return "<stub> " + getShape().toStringDetail();
	}
}
