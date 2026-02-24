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

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Metadata container for a group of assets, typically representing a single model.
 *
 * <p>This class holds metadata about a collection of assets including their names,
 * download URLs, sizes, and checksums. It is typically loaded from a JSON manifest
 * or created programmatically to describe available model downloads.</p>
 *
 * <h2>Key Features</h2>
 * <ul>
 *   <li><strong>Versioning:</strong> Track model versions</li>
 *   <li><strong>Asset manifest:</strong> Map of asset names to their metadata</li>
 *   <li><strong>Filtering:</strong> Create subsets of assets matching criteria</li>
 *   <li><strong>Size calculation:</strong> Total download size for bandwidth estimation</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Create from a list of assets
 * List<AssetInfo> assets = Arrays.asList(
 *     new AssetInfo("weights.pb"),
 *     new AssetInfo("tokenizer.bin")
 * );
 * AssetGroupInfo info = new AssetGroupInfo("llama-3-8b", "1.0", assets);
 *
 * // Create from a local directory
 * AssetGroupInfo local = AssetGroupInfo.forDirectory(new File("/path/to/model"));
 *
 * // Filter to only weight files
 * AssetGroupInfo weightsOnly = info.subset(asset -> asset.getName().endsWith(".pb"));
 *
 * // Check total download size
 * long totalBytes = info.getTotalSize();
 * }</pre>
 *
 * @see AssetInfo
 * @see AssetGroup
 */
public class AssetGroupInfo {
	private String name;
	private String version;
	private Map<String, AssetInfo> assets;

	public AssetGroupInfo() {}

	public AssetGroupInfo(String name, String version, List<AssetInfo> assets) {
		setName(name);
		setVersion(version);
		this.assets = new HashMap<>();
		if (assets != null) {
			assets.forEach(asset -> this.assets.put(asset.getName(), asset));
		}
	}

	public String getName() { return name; }
	public void setName(String name) {
		this.name = name;
	}

	public String getVersion() { return version; }
	public void setVersion(String version) { this.version = version; }

	public Map<String, AssetInfo> getAssets() { return assets; }
	public void setAssets(Map<String, AssetInfo> assets) {
		this.assets = assets;
	}

	public long getTotalSize() {
		if (assets == null) return 0;

		return assets.values().stream()
				.mapToLong(AssetInfo::getSize)
				.sum();
	}

	public AssetGroupInfo subset(Predicate<AssetInfo> filter) {
		return new AssetGroupInfo(getName(), getVersion(),
				getAssets().values().stream()
						.filter(filter)
						.collect(Collectors.toList()));
	}

	public static AssetGroupInfo forDirectory(File directory) {
		return forDirectory(directory.getName(), directory);
	}

	public static AssetGroupInfo forDirectory(String name, File directory) {
		if (directory.isDirectory()) {
			return new AssetGroupInfo(name, null,
					Stream.of(directory.listFiles())
							.map(File::getName)
							.map(AssetInfo::new)
							.collect(Collectors.toList()));
		}

		return new AssetGroupInfo(name, null, Collections.emptyList());
	}
}
