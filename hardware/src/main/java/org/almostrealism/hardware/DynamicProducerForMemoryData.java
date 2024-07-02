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

import io.almostrealism.code.OperationInfo;
import io.almostrealism.code.OperationMetadata;
import io.almostrealism.relation.DynamicProducer;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.ParallelProcess;
import io.almostrealism.relation.Process;
import io.almostrealism.uml.Multiple;

import java.util.Collection;
import java.util.Collections;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;

public class DynamicProducerForMemoryData<T extends MemoryData> extends DynamicProducer<T>
		implements ParallelProcess<Process<?, ?>, Evaluable<? extends T>>,
		OperationInfo {

	private final IntFunction<MemoryBank<T>> destination;

	public DynamicProducerForMemoryData(Supplier<T> supplier) {
		this(args -> supplier.get());
	}

	public DynamicProducerForMemoryData(Supplier<T> supplier, IntFunction<MemoryBank<T>> destination) {
		this(args -> supplier.get(), destination);
	}

	public DynamicProducerForMemoryData(Function<Object[], T> function) {
		this(function, null);
	}

	public DynamicProducerForMemoryData(Function<Object[], T> function, IntFunction<MemoryBank<T>> destination) {
		super(function);
		this.destination = destination;
	}

	@Override
	public OperationMetadata getMetadata() {
		return OperationInfo.metadataForProcess(this,
				new OperationMetadata(OperationInfo.name(getFunction()), OperationInfo.display(getFunction())));
	}

	@Override
	public long getCountLong() { return 1; }

	public IntFunction<MemoryBank<T>> getDestinationFactory() { return destination; }

	@Override
	public Collection<Process<?, ?>> getChildren() {
		return Collections.emptyList();
	}

	@Override
	public Process<Process<?, ?>, Evaluable<? extends T>> isolate() {
		return this;
	}

	@Override
	public Evaluable<T> get() {
		Evaluable<T> e = super.get();

		return new KernelizedEvaluable<T>() {
			@Override
			public Multiple<T> createDestination(int size) {
				if (destination == null) {
					throw new UnsupportedOperationException();
				} else {
					return destination.apply(size);
				}
			}

			@Override
			public Evaluable<T> withDestination(MemoryBank destination) {
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
