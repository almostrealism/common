/*
 * Copyright 2021 Michael Murray
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

package org.almostrealism.hardware.mem;

import io.almostrealism.code.ArrayVariable;
import io.almostrealism.code.Memory;
import io.almostrealism.code.NameProvider;
import io.almostrealism.relation.Delegated;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Provider;
import org.almostrealism.hardware.ContextSpecific;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.ProviderAwareArgumentMap;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

public class MemoryDataArgumentMap<S, A> extends ProviderAwareArgumentMap<S, A> {
	public static final boolean enableDestinationDetection = true;
	public static final boolean enableGlobalArgumentMap = false;

	private static ContextSpecific<MemoryDataArgumentMap> globalMaps;
	private static ContextSpecific<MemoryDataArgumentMap> globalMapsKernel;

	private final Map<Memory, ArrayVariable<A>> mems;
	private final boolean kernel;

	public MemoryDataArgumentMap() { this(true); }

	public MemoryDataArgumentMap(boolean kernel) {
		this.mems = new HashMap<>();
		this.kernel = kernel;
	}

	@Override
	public ArrayVariable<A> get(Supplier key, NameProvider p) {
		ArrayVariable<A> arg = super.get(key, p);
		if (arg != null) return arg;

		MemoryData mw;

		// A MemoryDataDestination carries information about how to produce
		// the data in that destination, along with the MemoryData itself.
		// There needs to be a way to return a delegated variable, as we do
		// below, but not lose the knowledge that we rely on during the
		// creation of required scopes, ie the knowledge of how that data
		// is populated. The root delegate will have no logical way to do
		// this because it may have many children produced in different
		// ways, so it probably has to be stored with the Delegated variable,
		// but it cannot be tracked using the delegate field because that
		// is already used to point at the root delegate MemoryData
		if (enableDestinationDetection && !kernel && key instanceof MemoryDataDestination) {
			Object dest = ((MemoryDataDestination) key).get().evaluate();
			if (dest != null && !(dest instanceof MemoryData)) {
				throw new RuntimeException();
			}

			mw = (MemoryData) dest;
		} else {
			Object provider = key.get();
			if (!(provider instanceof Provider)) return null;
			if (!(((Provider) provider).get() instanceof MemoryData)) return null;

			mw = (MemoryData) ((Provider) provider).get();
		}

		if (mw == null) return null;

		if (mems.containsKey(mw.getMem())) {
			return delegateProvider.getArgument(p, key, mems.get(mw.getMem()), mw.getOffset());
		} else {
			// Obtain the array variable for the root delegate of the MemoryData
			ArrayVariable var = delegateProvider.getArgument(p, new RootDelegateProviderSupplier(mw), null, -1);

			// Record that this MemoryData has var as its root delegate
			mems.put(mw.getMem(), var);

			// Return an ArrayVariable that delegates to the correct position of the root delegate
			return delegateProvider.getArgument(p, key, var, mw.getOffset());
		}
	}

	protected MemoryData rootDelegate(MemoryData mw) {
		if (mw.getDelegate() == null) {
			return mw;
		} else {
			return rootDelegate(mw.getDelegate());
		}
	}

	protected class RootDelegateProviderSupplier implements Supplier<Evaluable<? extends MemoryData>>, Delegated<Provider> {
		private final Provider provider;

		public RootDelegateProviderSupplier(MemoryData mem) {
			this.provider = new Provider<>(rootDelegate(mem));
		}

		@Override
		public Evaluable<? extends MemoryData> get() { return provider; }

		@Override
		public Provider getDelegate() { return provider; }
	}

	public static MemoryDataArgumentMap create(boolean kernel) {
		if (!enableGlobalArgumentMap) {
			return new MemoryDataArgumentMap(kernel);
		}

		return kernel ? getGlobalMapsKernel().getValue() : getGlobalMaps().getValue();
	}

	protected synchronized static ContextSpecific<MemoryDataArgumentMap> getGlobalMaps() {
		if (globalMaps == null) {
			globalMaps = new ContextSpecific<>(() -> new MemoryDataArgumentMap(false), MemoryDataArgumentMap::destroy);
			globalMaps.init();
		}

		return globalMaps;
	}

	protected synchronized static ContextSpecific<MemoryDataArgumentMap> getGlobalMapsKernel() {
		if (globalMapsKernel == null) {
			globalMapsKernel = new ContextSpecific<>(MemoryDataArgumentMap::new, MemoryDataArgumentMap::destroy);
			globalMapsKernel.init();
		}

		return globalMapsKernel;
	}
}
