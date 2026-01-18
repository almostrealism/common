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
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Represents a collection of related {@link Asset}s, typically all assets for a single model.
 *
 * <p>An {@code AssetGroup} provides a convenient way to work with multiple asset files
 * that belong together, such as model weights, tokenizer files, and configuration.
 * It supports lazy loading - files are only downloaded when accessed.</p>
 *
 * <h2>Key Features</h2>
 * <ul>
 *   <li><strong>Directory wrapping:</strong> Create from a local directory of files</li>
 *   <li><strong>Metadata-driven:</strong> Create from {@link AssetGroupInfo} with download URLs</li>
 *   <li><strong>Stream access:</strong> Efficient iteration over assets and files</li>
 *   <li><strong>Status checking:</strong> Check if all assets are loaded locally</li>
 * </ul>
 *
 * <h2>Usage Examples</h2>
 * <pre>{@code
 * // From a local directory
 * AssetGroup weights = new AssetGroup("/path/to/model/weights");
 *
 * // From metadata (with auto-download)
 * AssetGroupInfo info = assetProvider.getAssetGroupInfo("llama-3-8b");
 * AssetGroup model = new AssetGroup(info);
 *
 * // Access specific asset
 * String tokenizerPath = model.getAssetPath("tokenizer.bin");
 *
 * // Stream all files
 * model.files().forEach(file -> processFile(file));
 *
 * // Check if all assets are available locally
 * if (model.isLoaded()) {
 *     // All files present and verified
 * }
 * }</pre>
 *
 * @see Asset
 * @see AssetGroupInfo
 * @see org.almostrealism.ml.StateDictionary
 */
public class AssetGroup {
	private List<Asset> assets;

	/**
	 * Creates an AssetGroup from all files in a local directory.
	 *
	 * @param directory Path to the directory containing asset files
	 */
	public AssetGroup(String directory) {
		this(Stream.of(new File(directory).listFiles())
				.map(Asset::new).collect(Collectors.toList()));
	}

	/**
	 * Creates an AssetGroup from metadata, enabling automatic downloads.
	 *
	 * @param assets Metadata describing the assets in this group
	 */
	public AssetGroup(AssetGroupInfo assets) {
		this(assets.getAssets().values().stream()
				.map(info -> new Asset(assets.getName(), info))
				.collect(Collectors.toList()));
	}

	/**
	 * Creates an AssetGroup from individual Asset instances.
	 *
	 * @param assets The assets to include in this group
	 */
	public AssetGroup(Asset... assets) {
		this(List.of(assets));
	}

	/**
	 * Creates an AssetGroup from a list of Assets.
	 *
	 * @param assets The list of assets
	 */
	public AssetGroup(List<Asset> assets) {
		this.assets = assets;
	}

	/**
	 * Creates an AssetGroup from a stream of Assets.
	 *
	 * @param assets Stream of assets to collect
	 */
	public AssetGroup(Stream<Asset> assets) {
		this.assets = assets.collect(Collectors.toList());
	}

	/**
	 * Returns all assets in this group.
	 *
	 * @return List of all assets
	 */
	public List<Asset> getAllAssets() { return assets; }

	/**
	 * Finds an asset by name.
	 *
	 * @param name The asset file name
	 * @return The matching asset, or null if not found
	 */
	public Asset getAsset(String name) {
		return assets.stream()
				.filter(asset -> asset.getName().equals(name))
				.findFirst().orElse(null);
	}

	/**
	 * Returns the absolute file path for an asset by name.
	 *
	 * <p>This will trigger a download if the asset is not available locally.</p>
	 *
	 * @param name The asset file name
	 * @return The absolute path to the file, or null if asset not found
	 */
	public String getAssetPath(String name) {
		Asset asset = getAsset(name);
		return asset != null ? asset.getFile().getAbsolutePath() : null;
	}

	/**
	 * Returns a stream of all assets in this group.
	 *
	 * @return Stream of assets
	 */
	public Stream<Asset> assets() {
		return assets.stream();
	}

	/**
	 * Returns a stream of all asset files in this group.
	 *
	 * <p>Accessing files may trigger downloads for assets not yet available locally.</p>
	 *
	 * @return Stream of File objects
	 */
	public Stream<File> files() {
		return assets.stream().map(Asset::getFile);
	}

	/**
	 * Checks if all assets are available locally and verified.
	 *
	 * @return true if all assets are loaded and pass MD5 verification
	 */
	public boolean isLoaded() {
		return assets().allMatch(Asset::isLoaded);
	}

	/**
	 * Returns the total size of all assets in bytes.
	 *
	 * @return Total size in bytes
	 */
	public long getTotalSize() {
		return assets().mapToLong(Asset::getSize).sum();
	}
}
