/*
 * Copyright 2020 Michael Murray
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
import io.almostrealism.code.NameProvider;
import io.almostrealism.relation.Delegated;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Provider;
import org.almostrealism.hardware.MemWrapper;
import org.almostrealism.hardware.ProviderAwareArgumentMap;
import org.jocl.cl_mem;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

public class MemWrapperArgumentMap<S, A> extends ProviderAwareArgumentMap<S, A> {
	private Map<cl_mem, ArrayVariable<A>> mems;

	public MemWrapperArgumentMap() {
		mems = new HashMap<>();
	}

	@Override
	public ArrayVariable<A> get(Supplier key, NameProvider p) {
		ArrayVariable<A> arg = super.get(key, p);
		if (arg != null) return arg;

		Object provider = key.get();
		if (!(provider instanceof Provider)) return null;
		if (!(((Provider) provider).get() instanceof MemWrapper)) return null;

		MemWrapper mw = (MemWrapper) ((Provider) provider).get();
		if (mems.containsKey(mw.getMem())) {
			return delegateProvider.getArgument(p, key, mems.get(mw.getMem()), mw.getOffset());
		} else {
			ArrayVariable var = delegateProvider.getArgument(p, new RootDelegateProviderSupplier(mw), null, -1);
			mems.put(mw.getMem(), var);
			return delegateProvider.getArgument(p, key, var, mw.getOffset());
		}
	}

	protected MemWrapper rootDelegate(MemWrapper mw) {
		if (mw.getDelegate() == null) {
			return mw;
		} else {
			return rootDelegate(mw.getDelegate());
		}
	}

	protected class RootDelegateProviderSupplier implements Supplier<Evaluable<? extends MemWrapper>>, Delegated<Provider> {
		private final Provider provider;

		public RootDelegateProviderSupplier(MemWrapper mem) {
			this.provider = new Provider<>(rootDelegate(mem));
		}

		@Override
		public Evaluable<? extends MemWrapper> get() { return provider; }

		@Override
		public Provider getDelegate() { return provider; }
	}
}
