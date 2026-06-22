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

import io.almostrealism.code.ArgumentProvider;
import io.almostrealism.code.DefaultScopeInputManager;
import io.almostrealism.code.Memory;
import io.almostrealism.code.SupplierArgumentMap;
import io.almostrealism.collect.CollectionVariable;
import io.almostrealism.relation.Delegated;
import io.almostrealism.relation.Provider;
import io.almostrealism.scope.ArrayVariable;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.PassThroughProducer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Argument mapping for kernel compilation with provider adaptation.
 *
 * <p>{@link MemoryDataArgumentMap} manages the conversion of {@link MemoryData} instances into
 * kernel arguments, recognizing when different argument suppliers represent the same underlying
 * value so that a single kernel argument is shared:</p>
 * <ul>
 *   <li><b>PassThrough matching</b> &mdash; a {@link PassThroughProducer} key reuses an existing
 *       argument with the same referenced argument index.</li>
 *   <li><b>Provider value matching</b> &mdash; a {@link Provider} key reuses an existing argument
 *       that provides the same value instance.</li>
 *   <li><b>Root delegate handling</b> &mdash; views into the same underlying memory resolve to a
 *       single kernel argument that delegates to the appropriate offset.</li>
 * </ul>
 */
public class MemoryDataArgumentMap extends SupplierArgumentMap {
	/** Maps raw memory objects to the argument variables created for them. */
	private final Map<Memory, ArrayVariable> mems;
	/** All root delegate provider suppliers created by this map, for lifecycle management. */
	private final List<RootDelegateProviderSupplier> rootDelegateSuppliers;

	/**
	 * Creates an argument map.
	 *
	 * @param delegateProvider the argument provider used to create new argument variables
	 */
	public MemoryDataArgumentMap(ArgumentProvider delegateProvider) {
		super(delegateProvider);
		this.mems = new HashMap<>();
		this.rootDelegateSuppliers = new ArrayList<>();
	}

	@Override
	public ArrayVariable get(Supplier key) {
		ArrayVariable arg = super.get(key);
		if (arg != null) return arg;

		// A PassThroughProducer key reuses an existing argument that passes through
		// the same referenced argument index.
		if (key instanceof Delegated<?> && ((Delegated) key).getDelegate() instanceof PassThroughProducer<?>) {
			PassThroughProducer param = (PassThroughProducer) ((Delegated) key).getDelegate();

			Optional<ArrayVariable> passThrough = get(v -> {
				if (!(v instanceof Delegated<?>)) return false;
				if (!(((Delegated) v).getDelegate() instanceof PassThroughProducer)) return false;
				return ((PassThroughProducer) ((Delegated) v).getDelegate()).getReferencedArgumentIndex() == param.getReferencedArgumentIndex();
			});

			if (passThrough.isPresent()) return passThrough.get();
		}

		Object provider = key.get();
		if (!(provider instanceof Provider)) return null;

		Object value = ((Provider) provider).get();

		// Reuse an existing argument that provides the same value instance.
		Optional<ArrayVariable> match = get(supplier -> {
			Object v = supplier.get();
			if (!(v instanceof Provider)) return false;
			return ((Provider) v).get() == value;
		});

		if (match.isPresent()) return match.get();

		// Otherwise register the MemoryData by its root delegate so that views into the
		// same underlying memory resolve to a single kernel argument.
		if (!(value instanceof MemoryData)) return null;

		MemoryData md = (MemoryData) value;
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
	 * @return Fully configured {@link MemoryDataArgumentMap}
	 */
	public static MemoryDataArgumentMap create() {
		return new MemoryDataArgumentMap(
				new DefaultScopeInputManager(
						(name, input) -> CollectionVariable.create(name, (Supplier) input)));
	}
}
