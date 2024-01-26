/*
 * Copyright 2024 Michael Murray
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
import org.almostrealism.hardware.DynamicProducerForMemoryData;
import org.almostrealism.hardware.KernelizedEvaluable;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.hardware.ctx.ThreadLocalContextSpecific;

import java.util.function.BiFunction;
import java.util.function.IntFunction;

public class MemoryDataDestination<T extends MemoryData> extends DynamicProducerForMemoryData<T> implements Delegated<Countable> {
	public static boolean enableThreadLocalProvider = true;

	private final Countable process;
	private ThreadLocalContextSpecific<MemoryBankProvider<T>> provider;

	public MemoryDataDestination(Countable process) {
		this(process, (IntFunction<MemoryBank<T>>) null);
	}

	public MemoryDataDestination(Countable process, IntFunction<MemoryBank<T>> destination) {
		super(args -> { throw new UnsupportedOperationException(); }, destination);
		this.process = process;
		if (enableThreadLocalProvider) {
			this.provider = new ThreadLocalContextSpecific<>(() -> new MemoryBankProvider<>(destination), MemoryBankProvider::destroy);
			this.provider.init();
		}
	}

	public MemoryDataDestination(Countable process, BiFunction<MemoryBank<T>, Integer, MemoryBank<T>> destination) {
		super(args -> { throw new UnsupportedOperationException(); }, i -> destination.apply(null, i));
		this.process = process;
		if (enableThreadLocalProvider) {
			this.provider = new ThreadLocalContextSpecific<>(() -> new MemoryBankProvider<>(destination), MemoryBankProvider::destroy);
			this.provider.init();
		}
	}

	@Override
	public Countable getDelegate() { return process; }

	@Override
	public int getCount() {
		if (process != null) {
			return process.getCount();
		}

		return super.getCount();
	}

	@Override
	public boolean isFixedCount() { return true; }

	@Override
	public KernelizedEvaluable<T> get() {
		Evaluable<T> e = super.get();

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
