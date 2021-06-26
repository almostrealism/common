/*
 * Copyright 2020 Michael Murray
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

import io.almostrealism.code.ArrayVariable;
import io.almostrealism.code.NameProvider;
import io.almostrealism.relation.Delegated;
import org.almostrealism.hardware.mem.MemWrapperArgumentMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

public class DestinationConsolidationArgumentMap<S, A> extends MemWrapperArgumentMap<S, A> {
	private final List<DestinationSupport> keys;
	private final List<DestinationThreadLocal> destinations;

	public DestinationConsolidationArgumentMap() {
		keys = new ArrayList<>();
		destinations = new ArrayList<>();
	}

	@Override
	public void add(Supplier key) {
		super.add(key);
		keyForSupplier(key).ifPresent(keys::add);
	}

	@Override
	public ArrayVariable<A> get(Supplier supplier, NameProvider p) {
		ArrayVariable<A> var = super.get(supplier, p);

		keyForSupplier(supplier)
				.filter(prod -> !destinations.contains(prod.getDestination()))
				.ifPresent(producer ->
			producer.setDestination(new DestinationThreadLocal<>(producer.getDestination())));

		return var;
	}

	private Optional<DestinationSupport> keyForSupplier(Supplier supplier) {
		Object key = supplier;

		if (key instanceof Delegated) {
			key = ((Delegated<?>) key).getRootDelegate();
		}

		if (key instanceof DestinationSupport) return Optional.of((DestinationSupport) key);
		return Optional.empty();
	}

	/**
	 * Delegate to {@link DestinationThreadLocal#destroy()} for all the destinations.
	 */
	@Override
	public void destroy() {
		destinations.forEach(DestinationThreadLocal::destroy);
	}

	protected class DestinationThreadLocal<T> implements Supplier<T> {
		private final Supplier<T> supplier;
		private ThreadLocal<T> localByThread;
		private T local;

		public DestinationThreadLocal(Supplier<T> supplier) {
			this.supplier = supplier;

			if (Hardware.enableMultiThreading) {
				this.localByThread = new ThreadLocal<>();
			}

			destinations.add(this);
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
}
