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

package org.almostrealism.hardware;

import io.almostrealism.code.ArrayVariable;
import org.almostrealism.relation.NameProvider;
import org.almostrealism.util.CodeFeatures;
import org.almostrealism.util.Provider;
import org.almostrealism.util.ProviderAwareArgumentMap;
import org.jocl.cl_mem;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

public class MemWrapperArgumentMap<S, A> extends ProviderAwareArgumentMap<S, A> implements CodeFeatures {
	private Map<cl_mem, ArrayVariable<A>> mems;

	public MemWrapperArgumentMap() {
		mems = new HashMap<>();
	}

	@Override
	public ArrayVariable<A> get(Supplier key, NameProvider p) {
		ArrayVariable<A> arg = super.get(key, p);
		if (arg != null) return arg;

		Object provider = key.get();
		if (provider instanceof Provider == false) return null;
		if (((Provider) provider).get() instanceof MemWrapper == false) return null;

		MemWrapper mw = (MemWrapper) ((Provider) provider).get();
		if (mems.containsKey(mw.getMem())) {
			return delegateProvider.getArgument(p, key, mems.get(mw.getMem()), mw.getOffset());
		} else {
			ArrayVariable var = delegateProvider.getArgument(p, p(rootDelegate(mw)), null, -1);
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
}
