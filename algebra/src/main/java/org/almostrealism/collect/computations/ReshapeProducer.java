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

import io.almostrealism.code.ArgumentMap;
import io.almostrealism.code.OperationInfo;
import io.almostrealism.code.OperationMetadata;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.code.ScopeLifecycle;
import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.Index;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Countable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.ParallelProcess;
import io.almostrealism.relation.Process;
import io.almostrealism.relation.ProcessContext;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Provider;
import org.almostrealism.collect.CollectionProducer;
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.hardware.AcceleratedOperation;
import org.almostrealism.hardware.computations.HardwareEvaluable;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class ReshapeProducer<T extends Shape<T>>
		implements CollectionProducer<T>, TraversableExpression<Double>,
					ParallelProcess<Process<?, ?>, Evaluable<? extends T>>,
					OperationInfo, ScopeLifecycle {
	public static boolean enableDelegateIsolation = false;

	private TraversalPolicy shape;
	private int traversalAxis;
	private Producer<T> producer;

	public ReshapeProducer(int traversalAxis, Producer<T> producer) {
		this.traversalAxis = traversalAxis;
		this.producer = producer;
	}

	public ReshapeProducer(TraversalPolicy shape, Producer<T> producer) {
		this.shape = shape;
		this.producer = producer;

		if (shape(producer).getTotalSizeLong() != shape.getTotalSizeLong()) {
			throw new IllegalArgumentException();
		}
	}

	@Override
	public OperationMetadata getMetadata() {
		if (producer instanceof OperationInfo) {
			OperationMetadata metadata = ((OperationInfo) producer).getMetadata();

			if (shape == null) {
				return metadata.withDisplayName(metadata.getDisplayName() + " (-> axis " + traversalAxis + ")");
			} else {
				return metadata.withDisplayName(metadata.getDisplayName() + " (-> " + getShape() + ")");
			}
		}

		return null;
	}

	@Override
	public TraversalPolicy getShape() {
		if (shape == null) {
			TraversalPolicy inputShape = ((Shape) producer).getShape();
			return inputShape.traverse(traversalAxis);
		} else {
			return shape;
		}
	}

	@Override
	public long getParallelism() {
		if (producer instanceof ParallelProcess) {
			return ((ParallelProcess) producer).getParallelism();
		}

		return 1;
	}

	@Override
	public long getCountLong() { return getShape().getCountLong(); }

	@Override
	public boolean isFixedCount() {
		return Countable.isFixedCount(producer);
	}

	@Override
	public Collection<Process<?, ?>> getChildren() {
		return producer instanceof Process ? List.of((Process) producer) : Collections.emptyList();
	}

	@Override
	public ParallelProcess<Process<?, ?>, Evaluable<? extends T>> generate(List<Process<?, ?>> children) {
		if (children.size() != 1) return this;

		return shape == null ?
				new ReshapeProducer<>(traversalAxis, (Producer<T>) children.get(0)) :
				new ReshapeProducer<>(shape, (Producer<T>) children.get(0));
	}

	@Override
	public ParallelProcess<Process<?, ?>, Evaluable<? extends T>> optimize(ProcessContext ctx) {
		if (producer instanceof Process) {
			return generate(List.of(optimize(ctx, ((Process) producer))));
		}

		return this;
	}

	@Override
	public Process<Process<?, ?>, Evaluable<? extends T>> isolate() {
		if ((shape == null || enableDelegateIsolation) && producer instanceof Process) {
			// return new CollectionProducerComputation.IsolatedProcess(this);
			return generate(List.of(((Process) producer).isolate()));
		} else {
			return this;
		}
	}

	@Override
	public void prepareArguments(ArgumentMap map) {
		if (producer instanceof ScopeLifecycle) {
			((ScopeLifecycle) producer).prepareArguments(map);
		}
	}

	@Override
	public void prepareScope(ScopeInputManager manager, KernelStructureContext context) {
		if (producer instanceof ScopeLifecycle) {
			((ScopeLifecycle) producer).prepareScope(manager, context);
		}
	}

	@Override
	public void resetArguments() {
		if (producer instanceof ScopeLifecycle) {
			((ScopeLifecycle) producer).resetArguments();
		}
	}

	@Override
	public Expression<Double> getValue(Expression... pos) {
		return getValueAt(getShape().index(pos));
	}

	@Override
	public Expression<Double> getValueAt(Expression index) {
		return producer instanceof TraversableExpression ? ((TraversableExpression) producer).getValueAt(index) : null;
	}

	@Override
	public Expression<Double> getValueRelative(Expression index) {
		return producer instanceof TraversableExpression ? ((TraversableExpression) producer).getValueRelative(index) : null;
	}

	@Override
	public Expression uniqueNonZeroOffset(Index globalIndex, Index localIndex, Expression<?> targetIndex) {
		return producer instanceof TraversableExpression ?
				((TraversableExpression) producer).uniqueNonZeroOffset(globalIndex, localIndex, targetIndex) :
				null;
	}

	@Override
	public Expression uniqueNonZeroIndexRelative(Index localIndex, Expression<?> targetIndex) {
		return producer instanceof TraversableExpression ?
				((TraversableExpression) producer).uniqueNonZeroIndexRelative(localIndex, targetIndex) :
				TraversableExpression.super.uniqueNonZeroIndexRelative(localIndex, targetIndex);
	}

	@Override
	public boolean isTraversable() {
		if (producer instanceof TraversableExpression) return ((TraversableExpression) producer).isTraversable();
		return false;
	}

	@Override
	public boolean isRelative() {
		if (producer instanceof TraversableExpression) return ((TraversableExpression) producer).isRelative();
		return true;
	}

	@Override
	public CollectionProducer<T> delta(Producer<?> target) {
		if (producer instanceof CollectionProducer) {
			if (shape == null) {
				return new ReshapeProducer<>(traversalAxis, ((CollectionProducer) producer).delta(target));
			} else {
				TraversalPolicy newShape = shape.append(shape(target));
				return new ReshapeProducer<>(newShape, ((CollectionProducer) producer).delta(target));
			}
		}

		return CollectionProducer.super.delta(target);
	}

	public CollectionProducer<T> traverse(int axis) {
		if (shape == null || shape(producer).traverse(0).equals(getShape().traverse(0))) {
			return new ReshapeProducer<>(axis, producer);
		} else {
			return new ReshapeProducer<>(axis, this);
		}
	}

	@Override
	public CollectionProducer<T> reshape(TraversalPolicy shape) {
		return new ReshapeProducer<>(shape, producer);
	}

	@Override
	public Evaluable<T> get() {
		Evaluable<T> ev = producer.get();

		if (ev instanceof Provider) {
			return new Provider<>(null) {
				@Override
				public T get() {
					return apply((Shape<T>) ((Provider) ev).get());
				}
			};
		}

		HardwareEvaluable<T> hev = new HardwareEvaluable<>(producer::get, null, null, false);
		hev.setShortCircuit(args -> {
			long start = System.nanoTime();
			Shape<T> out = hev.getKernel().getValue().evaluate(args);
			AcceleratedOperation.wrappedEvalMetric.addEntry(producer, System.nanoTime() - start);
			return apply(out);
		});
		return hev;
	}

	private T apply(Shape<T> in) {
		if (shape == null) {
			return in.reshape(in.getShape().traverse(traversalAxis));
		} else {
			return in.reshape(shape);
		}
	}
}
