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

import java.util.List;
import java.util.Optional;

/**
 * An {@link AssetInfoProvider} backed by a fixed, explicitly supplied list of assets.
 *
 * <p>This provider stores a single named {@link AssetGroup} constructed from the provided
 * list of {@link Asset} instances. A lookup by name returns the group only when the
 * queried name exactly matches the name supplied at construction time.</p>
 *
 * @see AssetInfoProvider
 * @see AssetGroup
 */
public class ExplicitAssetProvider implements AssetInfoProvider {
	/** The name key under which the asset group is registered. */
	private String name;

	/** The asset group returned for matching lookups. */
	private AssetGroup group;

	/**
	 * Creates an {@code ExplicitAssetProvider} with the given name and asset list.
	 *
	 * @param name   The name key used to look up this group
	 * @param assets The assets that form the group
	 */
	public ExplicitAssetProvider(String name, List<Asset> assets) {
		this.name = name;
		this.group = new AssetGroup(assets);
	}

	/**
	 * Returns the asset group if {@code name} matches the registered name, otherwise an empty {@link Optional}.
	 *
	 * @param name The asset group name to look up
	 * @return An {@code Optional} containing the group, or empty if the name does not match
	 */
	@Override
	public Optional<AssetGroup> getAssetGroup(String name) {
		return this.name.equals(name) ? Optional.of(group) : Optional.empty();
	}
}
