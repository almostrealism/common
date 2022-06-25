/*
 * Copyright 2022 Michael Murray
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

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.DynamicOperationComputationAdapter;
import org.almostrealism.hardware.HardwareFeatures;
import org.almostrealism.hardware.KernelizedOperation;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.hardware.PassThroughProducer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Superclass providing common functionality for kernelized operations that take place on arguments
 * which are known to share a root delegate (@see {@link io.almostrealism.relation.Delegated}. This
 * base class makes possible to provide a {@link List} of {@link org.almostrealism.hardware.MemoryData}s
 * to a {@link Producer} that actually accepts a single {@link org.almostrealism.hardware.MemoryData} and
 * a list of positional offsets within it.
 *
 * @author  Michael Murray
 */
public abstract class RootDelegateKernelOperation<T extends MemoryBank> implements Supplier<Runnable>, HardwareFeatures {
	private List<Producer<T>> input;
	private T destination;

	private PackedCollection offsets;
	private PackedCollection count;
	private KernelizedOperation kernel;

	public RootDelegateKernelOperation(List<Producer<T>> input, T destination)  {
		this.input = input;
		this.destination = destination;
		this.offsets = new PackedCollection(input.size());
		this.count = new PackedCollection(1);
		this.count.setMem((double) input.size());
	}

	public Runnable get() {
		this.kernel = (KernelizedOperation)
				construct(new PassThroughProducer(-1, 0),
				new PassThroughProducer(-1, 1),
				new PassThroughProducer<>(1, 2, -1),
				new PassThroughProducer<>(1, 3, -1)).get();
		List<Evaluable<T>> evals = this.input.stream().map(Producer::get).collect(Collectors.toList());
		List<PackedCollection> rootDelegate = new ArrayList<>();

		OperationList op = new OperationList("RootDelegateOperation");
		op.add(() -> () -> {
			rootDelegate.clear();

			IntStream.range(0, evals.size()).forEach(i -> {
				T arg = evals.get(i).evaluate();

				if (rootDelegate.isEmpty()) {
					rootDelegate.add((PackedCollection) arg.getRootDelegate());
				} else if (!rootDelegate.contains(arg.getRootDelegate())) {
					throw new UnsupportedOperationException("All inputs to a root delegate operation must share the same root delegate");
				}

				offsets.setMem(i, arg.getOffset());
			});
		});
		op.add(() -> () -> kernel.kernelOperate(destination, rootDelegate.get(0).traverseEach(), offsets, count));
		return op.get();
	}

	public abstract DynamicOperationComputationAdapter<Void> construct(Producer<PackedCollection> destination,
																	Producer<PackedCollection> data,
																	Producer<PackedCollection> offsets,
																	Producer<PackedCollection> count);
}
