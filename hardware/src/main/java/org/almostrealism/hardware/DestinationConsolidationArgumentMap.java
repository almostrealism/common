/*
 * Copyright 2022 Michael Murray
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

import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.code.NameProvider;
import io.almostrealism.relation.Delegated;
import org.almostrealism.hardware.mem.MemoryBankAdapter;
import org.almostrealism.hardware.mem.MemoryDataAdapter;
import org.almostrealism.hardware.mem.MemoryDataArgumentMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public class DestinationConsolidationArgumentMap<S, A> extends MemoryDataArgumentMap<S, A> {
	private final List<DestinationSupport> keys;
	private final List<DestinationThreadLocal> destinations;
	private boolean confirmed;

	public DestinationConsolidationArgumentMap(boolean kernel) {
		super(null, kernel);
		keys = new ArrayList<>();
		destinations = new ArrayList<>();
	}

	@Override
	public void add(Supplier key) {
		super.add(key);
		createDestination(key);
	}

	@Override
	public ArrayVariable<A> get(Supplier supplier, NameProvider p) {
		ArrayVariable<A> var = super.get(supplier, p);
		createDestination(supplier);
		return var;
	}

	protected void createDestination(Supplier supplier) {
		keyForSupplier(supplier).ifPresent(keys::add);
		keyForSupplier(supplier)
				.filter(prod -> !destinations.contains(prod.getDestination()))
				.ifPresent(producer ->
						producer.setDestination(new DestinationThreadLocal<>(producer.getDestination())));
	}

	private Optional<DestinationSupport> keyForSupplier(Supplier supplier) {
		Object key = supplier;

		if (key instanceof Delegated) {
			key = ((Delegated<?>) key).getRootDelegate();
		}

		if (key instanceof DestinationSupport) return Optional.of((DestinationSupport) key);
		return Optional.empty();
	}

	@Override
	public void confirmArguments() {
		if (confirmed) return;

		super.confirmArguments();

		int total = destinations.stream().map(DestinationThreadLocal::get).filter(Objects::nonNull).mapToInt(MemoryData::getMemLength).sum();
		if (total <= 0) {
			confirmed = true;
			return;
		}

		DestinationBank bank = new DestinationBank(total);
		AtomicInteger position = new AtomicInteger();

		destinations.forEach(dest -> {
			MemoryData data = dest.get();
			if (data == null) return;

			int pos = position.get();
			int len = data.getMemLength();
			dest.set(bank, pos, len);
			position.set(pos + len);
		});

		confirmed = true;
	}

	/**
	 * Delegate to {@link DestinationThreadLocal#destroy()} for all the destinations.
	 */
	@Override
	public void destroy() {
		destinations.forEach(DestinationThreadLocal::destroy);
	}

	public class DestinationThreadLocal<T extends MemoryData> implements Supplier<T> {
		private Supplier<T> supplier;
		private ThreadLocal<T> localByThread;
		private T local;

		public DestinationThreadLocal(Supplier<T> supplier) {
			if (confirmed) throw new IllegalStateException("New destinations cannot be created after confirmation");

			this.supplier = supplier;

			if (Hardware.enableMultiThreading) {
				this.localByThread = new ThreadLocal<>();
			}

			destinations.add(this);
		}

		public void set(MemoryData delegate, int offset, int length) {
			set(() -> (T) new AnyMemoryData(delegate, offset, length));
		}

		public void set(Supplier<T> supplier) {
			this.supplier = supplier;

			if (Hardware.enableMultiThreading) {
				this.localByThread.remove();
			} else {
				local = null;
			}
		}

		@Override
		public T get() {
			T value = Hardware.enableMultiThreading ? localByThread.get() : local;

			if (value == null) {
				value = supplier.get();

				if (Hardware.enableMultiThreading) {
					localByThread.set(value);
				} else {
					local = value;
				}
			}

			return value;
		}

		/**
		 * Remove data for the current thread and eliminate
		 * the {@link ThreadLocal} storage.
		 */
		public void destroy() {
			if (Hardware.enableMultiThreading) {
				localByThread.remove();
				localByThread = new ThreadLocal<>();
			} else {
				local = null;
			}
		}
	}

	private static class DestinationBank extends MemoryBankAdapter<MemoryData> {
		protected DestinationBank(int length) {
			super(1, length, null, CacheLevel.NONE);
		}
	}

	private static class AnyMemoryData extends MemoryDataAdapter {
		private final int memLength;

		public AnyMemoryData(MemoryData delegate, int offset, int length) {
			setDelegate(delegate, offset);
			memLength = length;
		}

		@Override
		public int getMemLength() { return memLength; }
	}
}
