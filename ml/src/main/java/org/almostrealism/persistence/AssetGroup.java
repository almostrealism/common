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

	public AssetGroup(List<Asset> assets) {
		this.assets = assets;
	}

	public Stream<File> files() {
		return assets.stream().map(Asset::getFile);
	}
}
