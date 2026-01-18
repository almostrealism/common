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

package org.almostrealism.persistence;

import java.util.Optional;

/**
 * Provider interface for accessing asset metadata.
 *
 * <p>Implementations of this interface can provide asset information from various sources
 * such as local manifests, remote registries, or cloud storage APIs. This abstraction
 * allows the asset management system to work with different backend storage solutions.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * AssetInfoProvider provider = new MyAssetProvider();
 *
 * // Get asset group (triggers download if needed)
 * Optional<AssetGroup> model = provider.getAssetGroup("llama-3-8b");
 * if (model.isPresent()) {
 *     StateDictionary weights = new StateDictionary(model.get());
 * }
 *
 * // Get metadata only
 * AssetGroupInfo info = provider.getAssetGroupInfo("llama-3-8b");
 * System.out.println("Total size: " + info.getTotalSize() + " bytes");
 * }</pre>
 *
 * @see AssetGroup
 * @see AssetGroupInfo
 */
public interface AssetInfoProvider {

	/**
	 * Retrieves an asset group by name, creating Asset instances from metadata.
	 *
	 * <p>This convenience method combines metadata lookup with AssetGroup creation.</p>
	 *
	 * @param name The asset group name (typically a model identifier)
	 * @return Optional containing the AssetGroup, or empty if not found
	 */
	default Optional<AssetGroup> getAssetGroup(String name) {
		return Optional.ofNullable(getAssetGroupInfo(name))
				.map(AssetGroup::new);
	}

	/**
	 * Retrieves asset group metadata by name.
	 *
	 * <p>Implementations should override this method to provide actual metadata
	 * from their storage backend.</p>
	 *
	 * @param name The asset group name (typically a model identifier)
	 * @return AssetGroupInfo containing metadata, or null if not found
	 * @throws UnsupportedOperationException if not implemented
	 */
	default AssetGroupInfo getAssetGroupInfo(String name) {
		throw new UnsupportedOperationException();
	}
}
