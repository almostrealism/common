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

import io.almostrealism.profile.OperationInfo;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.relation.Delegated;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Provider;
import org.almostrealism.hardware.MemoryData;

import java.util.function.Supplier;

/**
 * Supplier that provides a {@link Provider} for the root delegate of a {@link MemoryData}.
 *
 * <p>Used by {@link MemoryDataArgumentMap} to create providers that reference the root delegate
 * of delegated memory, enabling efficient argument handling for memory views.</p>
 *
 * @see MemoryDataArgumentMap
 * @see MemoryData#getRootDelegate()
 */
public class RootDelegateProviderSupplier implements Supplier<Evaluable<? extends MemoryData>>,
		Delegated<Provider>, OperationInfo {
	private Provider provider;
	private OperationMetadata metadata;

	public RootDelegateProviderSupplier(MemoryData mem) {
		MemoryData root = mem.getRootDelegate();
		this.provider = new Provider<>(root);
		this.metadata = new OperationMetadata("rootDelegate", "RootDelegateProviderSupplier");
	}

	@Override
	public OperationMetadata getMetadata() { return metadata; }

	@Override
	public Evaluable<? extends MemoryData> get() { return provider; }

	@Override
	public Provider getDelegate() { return provider; }

	@Override
	public String describe() {
		return getMetadata().describe();
	}

	public void destroy() { this.provider = null; }
}
