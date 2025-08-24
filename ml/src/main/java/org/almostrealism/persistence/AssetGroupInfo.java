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
