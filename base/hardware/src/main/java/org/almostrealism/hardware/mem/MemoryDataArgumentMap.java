/*
 * Copyright 2025 Michael Murray
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

import io.almostrealism.code.ComputeContext;
import io.almostrealism.code.DefaultScopeInputManager;
import io.almostrealism.code.Memory;
import io.almostrealism.collect.CollectionVariable;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.profile.OperationProfile;
import io.almostrealism.relation.Provider;
import io.almostrealism.scope.ArrayVariable;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.ProviderAwareArgumentMap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Argument mapping for kernel compilation with provider adaptation.
 *
 * <p>{@link MemoryDataArgumentMap} manages the conversion of {@link MemoryData} instances into
 * kernel arguments. Arguments that share a root delegate are de-duplicated so that multiple views
 * into the same underlying memory resolve to a single kernel argument that delegates to the
 * appropriate offset.</p>
 *
 * <h2>Root Delegate Handling</h2>
 *
 * <p>Arguments that share a root delegate are automatically de-duplicated to avoid redundant
 * kernel arguments for views into the same underlying memory.</p>
 *
 * @param <S> Scope type
 * @param <A> Argument type
 */
public class MemoryDataArgumentMap<S, A> extends ProviderAwareArgumentMap<S, A> {
	/** Optional operation profile used for timing argument resolution. */
	public static OperationProfile profile;

	/** Metadata for the operation being compiled, used for argument naming. */
	private final OperationMetadata metadata;

	/** Maps raw memory objects to the argument variables created for them. */
	private final Map<Memory, ArrayVariable<A>> mems;
	/** All root delegate provider suppliers created by this map, for lifecycle management. */
	private final List<RootDelegateProviderSupplier> rootDelegateSuppliers;

	/**
	 * Creates an argument map.
	 *
	 * @param metadata Operation metadata for argument naming
	 */
	public MemoryDataArgumentMap(OperationMetadata metadata) {
		this.metadata = metadata;
		this.mems = new HashMap<>();
		this.rootDelegateSuppliers = new ArrayList<>();
	}

	@Override
	public ArrayVariable<A> get(Supplier key) {
		long start = System.nanoTime();

		try {
			ArrayVariable<A> arg = super.get(key);
			if (arg != null) return arg;

			Object provider = key.get();
			if (!(provider instanceof Provider)) return null;
			if (!(((Provider) provider).get() instanceof MemoryData)) return null;

			MemoryData md = (MemoryData) ((Provider) provider).get();

			if (md == null) return null;
			if (md.getMem() == null) {
				throw new IllegalArgumentException();
			}

			if (mems.containsKey(md.getMem())) {
				// If the root delegate already had an argument produced for it,
				// return that
				return delegateProvider.getArgument(key, mems.get(md.getMem()), md.getOffset());
			} else {
				// Obtain the array variable for the root delegate
				ArrayVariable var = delegateProvider.getArgument(createDelegate(md), null, -1);

				// Record that this MemoryData has var as its root delegate
				mems.put(md.getMem(), var);

				// Return an ArrayVariable that delegates to the correct position of the root delegate
				return delegateProvider.getArgument(key, var, md.getOffset());
			}
		} finally {
			if (profile != null) {
				profile.recordDuration(null, metadata.appendShortDescription(" get"), System.nanoTime() - start);
			}
		}
	}

	/**
	 * Creates a {@link RootDelegateProviderSupplier} for the given memory data and registers it for cleanup.
	 *
	 * @param md The memory data to create a root delegate provider for
	 * @return New {@link RootDelegateProviderSupplier} wrapping the given memory data
	 */
	private RootDelegateProviderSupplier createDelegate(MemoryData md) {
		RootDelegateProviderSupplier d = new RootDelegateProviderSupplier(md);
		rootDelegateSuppliers.add(d);
		return d;
	}

	@Override
	public void destroy() {
		super.destroy();
		rootDelegateSuppliers.forEach(RootDelegateProviderSupplier::destroy);
		mems.forEach((k, v) -> v.destroy());
		mems.clear();
	}

	/**
	 * Creates and configures a {@link MemoryDataArgumentMap} with the appropriate delegate provider.
	 *
	 * @param context Compute context providing language and memory services
	 * @param metadata Operation metadata for argument naming
	 * @return Fully configured {@link MemoryDataArgumentMap}
	 */
	public static MemoryDataArgumentMap create(ComputeContext<MemoryData> context, OperationMetadata metadata) {
		MemoryDataArgumentMap map = new MemoryDataArgumentMap(metadata);
		map.setDelegateProvider(new DefaultScopeInputManager(context.getLanguage(),
				(name, input) -> CollectionVariable.create(name, (Supplier) input)));
		return map;
	}
}
