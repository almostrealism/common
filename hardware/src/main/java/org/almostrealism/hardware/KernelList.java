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

package org.almostrealism.hardware;

import io.almostrealism.code.ProducerComputation;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Provider;
import io.almostrealism.uml.Plural;
import org.almostrealism.collect.Shape;
import org.almostrealism.collect.TraversalPolicy;
import org.almostrealism.hardware.computations.Assignment;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.IntStream;

public class KernelList<T extends MemoryData> implements Supplier<Runnable>, Plural<MemoryBank<T>> {
	private ProducerComputation<T> computation;
	private BiFunction<Producer<MemoryBank<T>>, Producer<T>, ProducerComputation<T>> computationProvider;

	private MemoryBank<T> input;
	private MemoryBank<T> parameters;
	private MemoryBank<? extends MemoryBank<T>> data;

	private BiFunction<Integer, Integer, MemoryBank<? extends MemoryBank<T>>> tableProvider;
	private int size;

	private Map<Integer, Producer<? extends MemoryBank<T>>> parameterValues;

	public KernelList(IntFunction<MemoryBank<T>> bankProvider,
					  BiFunction<Integer, Integer, MemoryBank<? extends MemoryBank<T>>> tableProvider,
					  ProducerComputation<T> computation, int size) {
		this(bankProvider, tableProvider, (p, in) -> computation, size, 0);
	}

	public KernelList(IntFunction<MemoryBank<T>> bankProvider,
					  BiFunction<Integer, Integer, MemoryBank<? extends MemoryBank<T>>> tableProvider,
					  BiFunction<Producer<MemoryBank<T>>, Producer<T>, ProducerComputation<T>> computation,
					  int size, int parameters) {
		if (size <= 0) throw new IllegalArgumentException();
		this.tableProvider = tableProvider;
		this.parameters = parameters > 0 ? bankProvider.apply(parameters) : null;
//		this.computation = computation.apply(() -> new Provider(this.parameters), new PassThroughProducer<>(0, 0));
		this.computationProvider = computation;
		this.parameterValues = new HashMap<>();
		this.size = size;
	}

	public MemoryBank<? extends MemoryBank<T>> getData() { return data; }

	public void setInput(MemoryBank<T> input) {
		this.input = input;
		this.data = tableProvider.apply(input.getCount(), size);

		TraversalPolicy shape = ((Shape) input).getShape();
		this.computation = computationProvider.apply(() -> new Provider(this.parameters), new PassThroughProducer<>(shape, 0));
	}

	public void setParameters(int pos, Producer<? extends MemoryBank<T>> parameters) {
		this.parameterValues.put(pos, parameters);
	}

	public Supplier<Runnable> assignParameters(Producer<? extends MemoryBank<T>> parameters) {
		return new Assignment<>(this.parameters.getMemLength(), () -> new Provider(this.parameters), parameters);
	}

	@Override
	public Runnable get() {
		KernelizedEvaluable<T> ev = (KernelizedEvaluable<T>) computation.get();

		OperationList op = new OperationList("KernelList Parameter Assignments and Kernel Evaluations");
		IntStream.range(0, size).forEach(i -> {
			if (parameterValues.containsKey(i)) op.add(assignParameters(parameterValues.get(i)));
			op.add(() -> () -> {
				if (AcceleratedComputationOperation.enableKernelLog) {
					System.out.println("KernelList: Evaluating kernel " + i + " against " + input.getCount() + " values...");
				}

				ev.into(data.get(i)).evaluate(input);
			});
		});

		return op.get();
	}

	@Override
	public MemoryBank<T> valueAt(int pos) { return data.get(pos); }
}
