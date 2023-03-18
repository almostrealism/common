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
import org.almostrealism.collect.ProducerWithOffset;
import org.almostrealism.hardware.DynamicOperationComputationAdapter;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareFeatures;
import org.almostrealism.hardware.KernelizedOperation;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.hardware.PassThroughProducer;

import java.util.ArrayList;
import java.util.Collections;
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
	private List<ProducerWithOffset<T>> input;
	private T destination;

	private PackedCollection sourceOffsets;
	private PackedCollection sourceLengths;
	private PackedCollection destinationOffsets;
	private PackedCollection count;
	private KernelizedOperation kernel;
	private boolean fixedInput;
	private int maxInputs;

	public RootDelegateKernelOperation(int maxInputs, T destination) {
		this.maxInputs = maxInputs;
		this.input = new ArrayList<>(maxInputs);
		this.destination = destination;
		this.sourceOffsets = new PackedCollection(maxInputs);
		this.sourceLengths = new PackedCollection(maxInputs);
		this.destinationOffsets = new PackedCollection(maxInputs);
		this.count = new PackedCollection(1);
	}

	public RootDelegateKernelOperation(List<ProducerWithOffset<T>> input, T destination)  {
		this.maxInputs = input.size();
		this.input = Collections.unmodifiableList(input);
		this.destination = destination;
		this.sourceOffsets = new PackedCollection(input.size());
		this.sourceLengths = new PackedCollection(input.size());
		this.destinationOffsets = new PackedCollection(input.size());
		this.count = new PackedCollection(1);
		this.fixedInput = true;
	}

	public List<ProducerWithOffset<T>> getInput() {
		return input;
	}

	public int getMaxInputs() {
		return maxInputs;
	}

	public Runnable get() {
		this.kernel = (KernelizedOperation)
				construct(new PassThroughProducer(-1, 0),
				new PassThroughProducer(-1, 1),
				new PassThroughProducer<>(1, 2, -1),
				new PassThroughProducer<>(1, 3, -1),
				new PassThroughProducer<>(1, 4, -1),
				new PassThroughProducer<>(1, 5, -1)).getKernel();
		List<Evaluable<T>> evals = fixedInput ? this.input.stream().map(ProducerWithOffset::getProducer).map(Producer::get).collect(Collectors.toList()) : null;
		List<PackedCollection<?>> rootDelegate = new ArrayList<>();

		OperationList op = new OperationList("RootDelegateOperation");
		op.add(() -> () -> {
			if (input.isEmpty())
				throw new IllegalArgumentException("No inputs");
			if (input.size() > maxInputs)
				throw new IllegalArgumentException("Too many inputs");

			rootDelegate.clear();
			sourceOffsets.clear();
			sourceLengths.clear();
			destinationOffsets.clear();
			count.setMem(0, input.size());

			List<Evaluable<T>> ev = evals == null ? this.input.stream().map(ProducerWithOffset::getProducer).map(Producer::get).collect(Collectors.toList()) : evals;

			IntStream.range(0, ev.size()).forEach(i -> {
				T arg = ev.get(i).evaluate();

				if (arg.getMem().getProvider() != Hardware.getLocalHardware().getDataContext().getKernelMemoryProvider()) {
					throw new IllegalArgumentException("Argument " + i + " is not in kernel memory");
				}

				if (rootDelegate.isEmpty()) {
					rootDelegate.add((PackedCollection) arg.getRootDelegate());
				} else if (!rootDelegate.contains(arg.getRootDelegate())) {
					throw new UnsupportedOperationException("All inputs to a root delegate operation must share the same root delegate");
				}

				sourceOffsets.setMem(i, arg.getOffset());
				sourceLengths.setMem(i, arg.getMemLength());
				destinationOffsets.setMem(i, this.input.get(i).getOffset());
			});
		});
		op.add(() -> () ->
				kernel.kernelOperate(destination, rootDelegate.get(0).traverse(0), sourceOffsets, sourceLengths, destinationOffsets, count));
		return op.get();
	}

	public abstract DynamicOperationComputationAdapter<Void> construct(Producer<PackedCollection> destination,
																	Producer<PackedCollection> data,
																	Producer<PackedCollection> sourceOffsets,
																	Producer<PackedCollection> sourceLengths,
																	Producer<PackedCollection> destinationOffsets,
																	Producer<PackedCollection> count);
}
