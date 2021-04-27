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
import org.almostrealism.hardware.mem.MemWrapperArgumentMap;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class DestinationConsolidationArgumentMap<S, A> extends MemWrapperArgumentMap<S, A> {
	private List<DestinationSupport> keys;
	private List<DestinationThreadLocal> destinations;

	public DestinationConsolidationArgumentMap() {
		keys = new ArrayList<>();
		destinations = new ArrayList<>();
	}

	@Override
	public void add(Supplier key) {
		if (key instanceof DestinationSupport) {
			keys.add((DestinationSupport) key);
		}
	}

	@Override
	public ArrayVariable<A> get(Supplier key, NameProvider p) {
		ArrayVariable<A> var = super.get(key, p);

		if (key instanceof DestinationSupport) {
			DestinationSupport producer = (DestinationSupport) key;
			producer.setDestination(new DestinationThreadLocal<>(producer.getDestination()));
		}

		return var;
	}

	@Override
	public void destroy() {
		destinations.stream().forEach(DestinationThreadLocal::destroy);
	}

	protected class DestinationThreadLocal<T> implements Supplier<T> {
		private Supplier<T> supplier;
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