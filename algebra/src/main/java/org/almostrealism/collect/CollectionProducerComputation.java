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

package org.almostrealism.collect;

import io.almostrealism.code.Computation;
import io.almostrealism.code.ComputeContext;
import io.almostrealism.code.MemoryProvider;
import io.almostrealism.code.OperationInfo;
import io.almostrealism.code.OperationMetadata;
import io.almostrealism.code.ProducerComputation;
import io.almostrealism.collect.CollectionProducerBase;
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.ParallelProcess;
import io.almostrealism.relation.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.computations.DefaultCollectionEvaluable;
import org.almostrealism.collect.computations.ReshapeProducer;
import org.almostrealism.hardware.AcceleratedComputationEvaluable;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.mem.MemoryDataAdapter;
import org.almostrealism.hardware.mem.MemoryDataDestinationProducer;
import org.almostrealism.io.SystemUtils;

import java.util.Collection;
import java.util.Collections;
import java.util.function.Function;
import java.util.stream.Stream;

public interface CollectionProducerComputation<T extends PackedCollection<?>> extends
		CollectionProducer<T>, ProducerComputation<T>, ParallelProcess<Process<?, ?>, Evaluable<? extends T>> {
	boolean isolationLogging = SystemUtils.isEnabled("AR_ISOLATION_LOGGING").orElse(false);

	/**
	 * When enabled, the TraversalPolicy of results from {@link #postProcessOutput(MemoryData, int)}
	 * will avoid prepending dimensions to the TraversalPolicy from {@link #getShape()}.
	 */
	// TODO  This doesn't seem to be implemented properly
	boolean enableShapeTrim = false;

	@Override
	default Stream<? extends Process> processChildren(Collection<? extends Process> children) {
		return children.stream()
				.filter(f -> !(f instanceof MemoryDataDestinationProducer));
	}

	default T createDestination(int len) {
		throw new UnsupportedOperationException();
	}

	default T postProcessOutput(MemoryData output, int offset) {
		TraversalPolicy shape = getShape();

		s: if (output instanceof Shape) {
			TraversalPolicy outputShape = ((Shape) output).getShape();

			while (shape.getDimensions() < outputShape.getDimensions()) {
				int dim = outputShape.getDimensions() - shape.getDimensions();

				if (enableShapeTrim && shape.getDimensions() == outputShape.getDimensions() - 1 && dim == 1) {
					break s;
				} else {
					shape = shape.prependDimension(outputShape.length(dim - 1));
				}
			}

			if (shape.getTotalSize() != outputShape.getTotalSize()) {
				throw new IllegalArgumentException("Output is not compatible with expected shape");
			}

			if (offset == 0 && shape.equals(((Shape) output).getShape())) {
				return (T) output;
			}
		}

		return (T) new PackedCollection(shape, shape.getTraversalAxis(), output, offset);
	}

	@Override
	default Evaluable<T> get() {
		ComputeContext<MemoryData> ctx = Hardware.getLocalHardware().getComputer().getContext(this);
		AcceleratedComputationEvaluable<T> ev = new DefaultCollectionEvaluable<>(
				ctx, getShape(), this,
				this::createDestination, this::postProcessOutput);
		ev.compile();
		return ev;
	}

	@Override
	default CollectionProducer<T> traverse(int axis) {
		return new ReshapeProducer(axis, this);
	}

	@Override
	default CollectionProducer<T> reshape(TraversalPolicy shape) {
		return new ReshapeProducer(shape, this);
	}

	default <T extends MemoryDataAdapter> T collect(Function<TraversalPolicy, T> factory) {
		PackedCollection c = get().evaluate();
		T data = factory.apply(c.getShape());
		data.setDelegate(c, 0);
		return data;
	}

	class IsolatedProcess<T extends PackedCollection<?>> implements
			Process<Process<?, ?>, Evaluable<? extends T>>,
			CollectionProducerBase<T, Producer<T>>,
			OperationInfo {
		private CollectionProducer<T> op;

		public IsolatedProcess(CollectionProducer<T> op) {
			if (isolationLogging)
				Computation.console.features(this)
						.log("Isolating " + OperationInfo.name(op) + " " + op.getShape().toStringDetail());

			this.op = op;

			if (op.getShape().getTotalSizeLong() > MemoryProvider.MAX_RESERVATION) {
				throw new IllegalArgumentException("Cannot isolate a process with a total size greater than " +
						MemoryProvider.MAX_RESERVATION);
			}
		}

		@Override
		public OperationMetadata getMetadata() {
			return op instanceof OperationInfo ? ((OperationInfo) op).getMetadata() : null;
		}

		@Override
		public Collection<Process<?, ?>> getChildren() {
			return op instanceof Process ? ((Process) op).getChildren() : Collections.emptyList();
		}

		@Override
		public Evaluable<T> get() {
			return op.get();
		}

		@Override
		public TraversalPolicy getShape() {
			return op.getShape();
		}

		@Override
		public boolean isConstant() { return op.isConstant(); }

		@Override
		public Producer<T> traverse(int axis) { throw new UnsupportedOperationException(); }

		@Override
		public Producer<T> reshape(TraversalPolicy shape) {
			throw new UnsupportedOperationException();
		}

		@Override
		public long getOutputSize() {
			return op.getShape().getTotalSize();
		}

		@Override
		public Process<Process<?, ?>, Evaluable<? extends T>> isolate() {
			return this;
		}
	}
}
