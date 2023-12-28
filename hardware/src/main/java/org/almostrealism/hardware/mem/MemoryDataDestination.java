/*
 * Copyright 2021 Michael Murray
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

package org.almostrealism.hardware.mem;

import io.almostrealism.relation.Countable;
import io.almostrealism.relation.Delegated;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.uml.Multiple;
import org.almostrealism.hardware.DestinationSupport;
import org.almostrealism.hardware.DynamicProducerForMemoryData;
import org.almostrealism.hardware.KernelizedEvaluable;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.hardware.ctx.ThreadLocalContextSpecific;

import java.util.function.IntFunction;

public class MemoryDataDestination<T extends MemoryData> extends DynamicProducerForMemoryData<T> implements Delegated<DestinationSupport<T>> {
	public static boolean enableThreadLocalProvider = true;

	private final DestinationSupport<T> destination;
	private ThreadLocalContextSpecific<MemoryBankProvider<T>> provider;

	public MemoryDataDestination(DestinationSupport<T> destination) {
		this(destination, null);
	}

	public MemoryDataDestination(DestinationSupport<T> destination, IntFunction<MemoryBank<T>> kernelDestination) {
		super(args -> { throw new UnsupportedOperationException(); }, kernelDestination);
		this.destination = destination;
		if (enableThreadLocalProvider) {
			this.provider = new ThreadLocalContextSpecific<>(() -> new MemoryBankProvider<>(kernelDestination), MemoryBankProvider::destroy);
			this.provider.init();
		}
	}

	@Override
	public DestinationSupport<T> getDelegate() { return destination; }

	@Override
	public int getCount() {
		if (destination instanceof Countable) {
			return ((Countable) destination).getCount();
		}

		return super.getCount();
	}

	@Override
	public KernelizedEvaluable<T> get() {
		KernelizedEvaluable<T> e = super.get();

		return new KernelizedEvaluable<T>() {
			@Override
			public Multiple<T> createDestination(int size) {
				if (enableThreadLocalProvider) {
					return provider.getValue().apply(size);
				} else {
					return e.createDestination(size);
				}
			}

			@Override
			public Evaluable<T> withDestination(MemoryBank destination) {
				return args -> (T) destination;
			}

			@Override
			public T evaluate(Object... args) {
				return createDestination(1).get(0);
			}
		};
	}
}
