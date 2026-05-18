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
	/** Identifier name for this asset group (e.g., the model name). */
	private String name;

	/** Version string for this asset group, or {@code null} if unversioned. */
	private String version;

	/** Map from asset filename to its metadata. */
	private Map<String, AssetInfo> assets;

	/** Default constructor for JSON deserialization. */
	public AssetGroupInfo() {}

	/**
	 * Constructs an asset group info from a list of assets.
	 *
	 * @param name    Group name
	 * @param version Version string
	 * @param assets  List of asset metadata entries
	 */
	public AssetGroupInfo(String name, String version, List<AssetInfo> assets) {
		setName(name);
		setVersion(version);
		this.assets = new HashMap<>();
		if (assets != null) {
			assets.forEach(asset -> this.assets.put(asset.getName(), asset));
		}
	}

	/** Returns the group name. @return Group name */
	public String getName() { return name; }

	/** Sets the group name. @param name Group name */
	public void setName(String name) {
		this.name = name;
	}

	/** Returns the version string, or {@code null} if unversioned. @return Version */
	public String getVersion() { return version; }

	/** Sets the version string. @param version Version string */
	public void setVersion(String version) { this.version = version; }

	/** Returns the asset metadata map keyed by filename. @return Assets map */
	public Map<String, AssetInfo> getAssets() { return assets; }

	/** Sets the asset metadata map. @param assets Assets map */
	public void setAssets(Map<String, AssetInfo> assets) {
		this.assets = assets;
	}

	/**
	 * Returns the sum of all asset sizes in bytes.
	 *
	 * @return Total download size in bytes
	 */
	public long getTotalSize() {
		if (assets == null) return 0;

		return assets.values().stream()
				.mapToLong(AssetInfo::getSize)
				.sum();
	}

	/**
	 * Returns a new {@code AssetGroupInfo} containing only the assets that match the given filter.
	 *
	 * @param filter Predicate to select assets; only assets for which this returns {@code true} are included
	 * @return A new {@code AssetGroupInfo} with the same name and version but a filtered asset set
	 */
	public AssetGroupInfo subset(Predicate<AssetInfo> filter) {
		return new AssetGroupInfo(getName(), getVersion(),
				getAssets().values().stream()
						.filter(filter)
						.collect(Collectors.toList()));
	}

	/**
	 * Creates an {@code AssetGroupInfo} from all files in a local directory, using the directory name as the group name.
	 *
	 * @param directory The directory whose files become the assets
	 * @return A new {@code AssetGroupInfo} with no version and one {@link AssetInfo} per file in the directory
	 */
	public static AssetGroupInfo forDirectory(File directory) {
		return forDirectory(directory.getName(), directory);
	}

	/**
	 * Creates an {@code AssetGroupInfo} from all files in a local directory.
	 *
	 * <p>If {@code directory} is not a directory, an empty asset group is returned.</p>
	 *
	 * @param name      The group name to assign
	 * @param directory The directory whose files become the assets
	 * @return A new {@code AssetGroupInfo} with no version and one {@link AssetInfo} per file in the directory
	 */
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
