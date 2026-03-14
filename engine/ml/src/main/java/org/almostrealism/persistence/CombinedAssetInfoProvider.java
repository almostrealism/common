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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class CombinedAssetInfoProvider implements AssetInfoProvider {
	private List<AssetInfoProvider> providers;

	public CombinedAssetInfoProvider() {
		this.providers = new ArrayList<>();
	}

	public CombinedAssetInfoProvider(AssetInfoProvider... providers) {
		this.providers = Arrays.asList(providers);
	}

	public void addProvider(AssetInfoProvider p) {
		this.providers.add(p);
	}

	@Override
	public Optional<AssetGroup> getAssetGroup(String name) {
		for (AssetInfoProvider p : providers) {
			Optional<AssetGroup> group = p.getAssetGroup(name);
			if (group.isPresent()) return group;
		}

		return Optional.empty();
	}
}
