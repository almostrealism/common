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

package org.almostrealism.hardware.test;

import io.almostrealism.code.ComputeContext;
import io.almostrealism.code.ProducerComputation;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.DelegatedCollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.CollectionProducerComputationBase;
import org.almostrealism.collect.computations.DefaultCollectionEvaluable;
import org.almostrealism.collect.computations.ReshapeProducer;
import org.almostrealism.hardware.AcceleratedComputationEvaluable;
import org.almostrealism.hardware.instructions.ComputableInstructionSetManager;
import org.almostrealism.hardware.instructions.ExecutionKey;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

public class ReusableEvaluableTests implements TestFeatures {
	private static ComputableInstructionSetManager<?> sharedInstructions;
	private static ExecutionKey sharedKey;

	@Test
	public void add() {
		HardwareOperator.enableInstructionSetMonitoring = true;

		try {
			int n = 6;

			PackedCollection<?> a = new PackedCollection<>(shape(n))
					.randFill().traverseEach();
			PackedCollection<?> b = new PackedCollection<>(shape(n))
					.randFill().traverseEach();
			PackedCollection<?> c = new PackedCollection<>(shape(n))
					.randFill().traverseEach();

			Producer<PackedCollection<?>> sum = createSum(cp(a), cp(b), cp(c));
			PackedCollection<?> out = sum.get().evaluate();

			for (int i = 0; i < n; i++) {
				assertEquals(a.toDouble(i) + b.toDouble(i) + c.toDouble(i), out.toDouble(i));
			}

			PackedCollection<?> alt = new PackedCollection<>(shape(n))
					.randFill().traverseEach();

			sum = createSum(cp(alt), cp(b), cp(c));
			out = sum.get().evaluate();

			for (int i = 0; i < n; i++) {
				assertEquals(alt.toDouble(i) + b.toDouble(i) + c.toDouble(i), out.toDouble(i));
			}
		} finally {
			sharedInstructions = null;
			HardwareOperator.enableInstructionSetMonitoring = false;
		}
	}

	@Test
	public void multiply() {
		HardwareOperator.enableInstructionSetMonitoring = true;

		try {
			int n = 6;

			PackedCollection<?> a = new PackedCollection<>(shape(n))
					.randFill().traverseEach();
			PackedCollection<?> b = new PackedCollection<>(shape(n))
					.randFill().traverseEach();
			PackedCollection<?> c = new PackedCollection<>(shape(n))
					.randFill().traverseEach();

			Producer<PackedCollection<?>> sum = createProduct(cp(a), cp(b), cp(c));
			PackedCollection<?> out = sum.get().evaluate();

			for (int i = 0; i < n; i++) {
				assertEquals((a.toDouble(i) + b.toDouble(i)) * c.toDouble(i), out.toDouble(i));
			}

			PackedCollection<?> alt = new PackedCollection<>(shape(n))
					.randFill().traverseEach();

			sum = createProduct(cp(alt), cp(b), cp(c));
			out = sum.get().evaluate();

			for (int i = 0; i < n; i++) {
				assertEquals((alt.toDouble(i) + b.toDouble(i)) * c.toDouble(i), out.toDouble(i));
			}
		} finally {
			sharedInstructions = null;
			HardwareOperator.enableInstructionSetMonitoring = false;
		}
	}

	protected CollectionProducer<PackedCollection<?>> createSum(Producer<PackedCollection<?>> a,
																Producer<PackedCollection<?>> b,
																Producer<PackedCollection<?>> c) {
		return wrap((ProducerComputation) add(v(a), b).add(c));
	}

	protected CollectionProducer<PackedCollection<?>> createProduct(Producer<PackedCollection<?>> a,
																    Producer<PackedCollection<?>> b,
																    Producer<PackedCollection<?>> c) {
		Producer<PackedCollection<?>> add = (Producer) ((CollectionProducerComputationBase) add(v(a), b)).isolate();
		return wrap((ProducerComputation) multiply(add, c));
	}

	protected <T extends PackedCollection<?>> DelegatedCollectionProducer<T> v(Producer<T> inner) {
		return new DelegatedCollectionProducer<>(c(inner)) {
			@Override
			public boolean isFixedCount() {
				return false;
			}

			@Override
			public Evaluable<T> get() {
				Evaluable<T> original = op.get();
				return original::evaluate;
			}
		};
	}

	protected <T extends PackedCollection<?>> CollectionProducer<T> wrap(ProducerComputation<T> inner) {
		return new CollectionProducer() {
			@Override
			public Evaluable<T> get() {
				ComputeContext<MemoryData> ctx = Hardware.getLocalHardware().getComputer().getContext(inner);
				AcceleratedComputationEvaluable<T> ev = new DefaultCollectionEvaluable<>(
						ctx, getShape(), inner,
						this::createDestination, this::postProcessOutput);

				if (sharedInstructions == null) {
					ev.compile();
					sharedInstructions = ev.getInstructionSetManager();
					sharedKey = ev.getExecutionKey();
				} else {
					ev.compile(sharedInstructions, sharedKey);
				}


				return ev;
			}

			public T createDestination(int len) {
				if (inner instanceof CollectionProducerComputation) {
					return ((CollectionProducerComputation<T>) inner).createDestination(len);
				}

				throw new UnsupportedOperationException();
			}

			public T postProcessOutput(MemoryData output, int offset) {
				if (inner instanceof CollectionProducerComputation) {
					return ((CollectionProducerComputation<T>) inner).postProcessOutput(output, offset);
				}

				throw new UnsupportedOperationException();
			}

			// TODO  This should go in a common parent interface of CollectionProducerComputation and this class
			@Override
			public CollectionProducer<T> traverse(int axis) {
				return new ReshapeProducer(axis, this);
			}

			@Override
			public CollectionProducer<T> reshape(TraversalPolicy shape) {
				return new ReshapeProducer(shape, this);
			}


			@Override
			public TraversalPolicy getShape() {
				return shape(inner);
			}
		};
	}
}
