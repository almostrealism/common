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

public class AssetGroup {
	private List<Asset> assets;

	public AssetGroup(String directory) {
		this(Stream.of(new File(directory).listFiles())
				.map(Asset::new).collect(Collectors.toList()));
	}

	public AssetGroup(AssetGroupInfo assets) {
		this(assets.getAssets().values().stream()
				.map(info -> new Asset(assets.getName(), info))
				.collect(Collectors.toList()));
	}

	public AssetGroup(Asset... assets) {
		this(List.of(assets));
	}

	public AssetGroup(List<Asset> assets) {
		this.assets = assets;
	}

	public List<Asset> getAllAssets() { return assets; }

	public Asset getAsset(String name) {
		return assets.stream()
				.filter(asset -> asset.getName().equals(name))
				.findFirst().orElse(null);
	}

	public String getAssetPath(String name) {
		Asset asset = getAsset(name);
		return asset != null ? asset.getFile().getAbsolutePath() : null;
	}

	public Stream<Asset> assets() {
		return assets.stream();
	}

	public Stream<File> files() {
		return assets.stream().map(Asset::getFile);
	}

	public boolean isLoaded() {
		return assets().allMatch(Asset::isLoaded);
	}
}
