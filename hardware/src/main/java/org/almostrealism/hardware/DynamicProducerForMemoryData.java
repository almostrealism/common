/*
 * Copyright 2023 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.hardware;

import io.almostrealism.relation.Countable;
import io.almostrealism.relation.DynamicProducer;
import io.almostrealism.relation.Evaluable;

import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;

public class DynamicProducerForMemoryData<T extends MemoryData> extends DynamicProducer<T> implements Countable {

	private final IntFunction<MemoryBank<T>> kernelDestination;

	public DynamicProducerForMemoryData(Supplier<T> supplier) {
		this(args -> supplier.get());
	}

	public DynamicProducerForMemoryData(Supplier<T> supplier, IntFunction<MemoryBank<T>> kernelDestination) {
		this(args -> supplier.get(), kernelDestination);
	}

	public DynamicProducerForMemoryData(Function<Object[], T> function) {
		this(function, null);
	}

	public DynamicProducerForMemoryData(Function<Object[], T> function, IntFunction<MemoryBank<T>> kernelDestination) {
		super(function);
		this.kernelDestination = kernelDestination;
	}

	@Override
	public int getCount() { return 1; }

	@Override
	public KernelizedEvaluable<T> get() {
		Evaluable<T> e = super.get();

		return new KernelizedEvaluable<T>() {
			@Override
			public MemoryBank<T> createKernelDestination(int size) {
				if (kernelDestination == null) {
					throw new UnsupportedOperationException();
				} else {
					return kernelDestination.apply(size);
				}
			}

			@Override
			public Evaluable<T> withDestination(MemoryBank<T> destination) {
				return new DestinationEvaluable(e, destination);
			}

			@Override
			public T evaluate(Object... args) { return e.evaluate(args); }
		};
	}

	@Override
	public void destroy() {
		super.destroy();
		ProducerCache.purgeEvaluableCache(this);
	}
}
