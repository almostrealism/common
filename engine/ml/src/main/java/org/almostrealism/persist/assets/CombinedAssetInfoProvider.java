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

package org.almostrealism.persist.assets;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * An {@link AssetInfoProvider} that delegates to an ordered list of other providers.
 *
 * <p>Providers are queried in order; the first provider that returns a non-empty
 * result wins. This enables a priority chain — for example, a local cache checked
 * before a remote registry.</p>
 *
 * @see AssetInfoProvider
 */
public class CombinedAssetInfoProvider implements AssetInfoProvider {
	/** Ordered list of delegate providers consulted on each lookup. */
	private List<AssetInfoProvider> providers;

	/**
	 * Creates an empty {@code CombinedAssetInfoProvider}.
	 * Providers may be added later via {@link #addProvider(AssetInfoProvider)}.
	 */
	public CombinedAssetInfoProvider() {
		this.providers = new ArrayList<>();
	}

	/**
	 * Creates a {@code CombinedAssetInfoProvider} with the given initial providers.
	 *
	 * @param providers The ordered providers to combine
	 */
	public CombinedAssetInfoProvider(AssetInfoProvider... providers) {
		this.providers = Arrays.asList(providers);
	}

	/**
	 * Appends a provider to the end of the delegation chain.
	 *
	 * @param p The provider to add
	 */
	public void addProvider(AssetInfoProvider p) {
		this.providers.add(p);
	}

	/**
	 * Returns the first non-empty result from the delegate providers, or an empty {@link Optional}.
	 *
	 * @param name The asset group name to look up
	 * @return The first matching {@link AssetGroup}, or empty if no provider has it
	 */
	@Override
	public Optional<AssetGroup> getAssetGroup(String name) {
		for (AssetInfoProvider p : providers) {
			Optional<AssetGroup> group = p.getAssetGroup(name);
			if (group.isPresent()) return group;
		}

		return Optional.empty();
	}
}
