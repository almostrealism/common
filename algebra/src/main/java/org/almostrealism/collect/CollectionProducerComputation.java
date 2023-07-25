/*
 * Copyright 2023 Michael Murray
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
import io.almostrealism.code.ProducerComputation;
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.ParallelProcess;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.Scope;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.collect.computations.DefaultCollectionEvaluable;
import org.almostrealism.collect.computations.ReshapeProducer;
import org.almostrealism.hardware.AcceleratedComputationEvaluable;
import org.almostrealism.hardware.DestinationEvaluable;
import org.almostrealism.hardware.Input;
import org.almostrealism.hardware.KernelizedEvaluable;
import org.almostrealism.hardware.KernelizedProducer;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.mem.MemoryDataAdapter;

import java.util.function.Function;

public interface CollectionProducerComputation<T extends PackedCollection<?>> extends
		CollectionProducer<T>, ProducerComputation<T>, ParallelProcess, KernelizedProducer<T> {
	boolean enableShapeTrim = false;

	// This should be 0, but Scalar is actually a Pair so a set of scalars is 2D not 1D
	int SCALAR_AXIS = 1;

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
		}

		return (T) new PackedCollection(shape, shape.getTraversalAxis(), output, offset);
	}

	@Override
	default KernelizedEvaluable<T> get() {
		AcceleratedComputationEvaluable<T> ev = new DefaultCollectionEvaluable<T>(getShape(), this, this::postProcessOutput);
		ev.compile();
		return ev;
	}

	@Override
	default CollectionProducer<T> traverse(int axis) {
		return reshape(getShape().traverse(axis));
	}

	@Override
	default CollectionProducer<T> reshape(TraversalPolicy shape) {
		return new ReshapeProducer<>(shape, (Producer) this);
	}

	default <T extends MemoryDataAdapter> T collect(Function<TraversalPolicy, T> factory) {
		PackedCollection c = get().evaluate();
		T data = factory.apply(c.getShape());
		data.setDelegate(c, 0);
		return data;
	}

	default KernelizedEvaluable<PackedCollection<?>> shortCircuit(Evaluable<PackedCollection<?>> ev) {
		return new KernelizedEvaluable<PackedCollection<?>>() {
			private KernelizedEvaluable<PackedCollection<?>> kernel;

			@Override
			public MemoryBank<PackedCollection<?>> createKernelDestination(int size) {
				return getKernel().createKernelDestination(size);
			}

			@Override
			public PackedCollection<?> evaluate(Object... args) {
				return ev.evaluate(args);
			}

			@Override
			public Evaluable<PackedCollection<?>> withDestination(MemoryBank<PackedCollection<?>> destination) {
				return new DestinationEvaluable<>((AcceleratedComputationEvaluable) getKernel(), destination);
			}

			public KernelizedEvaluable<PackedCollection<?>> getKernel() {
				if (kernel == null) {
					AcceleratedComputationEvaluable<PackedCollection<?>> ev = new DefaultCollectionEvaluable<PackedCollection<?>>(getShape(), (Computation) CollectionProducerComputation.this, CollectionProducerComputation.this::postProcessOutput);
					ev.compile();
					kernel = ev;
				}

				return kernel;
			}
		};
	}
}
