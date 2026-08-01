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

import org.almostrealism.io.SystemUtils;

import java.nio.file.Path;

/**
 * An {@link AssetInfoProvider} that resolves asset groups from the local filesystem.
 *
 * <p>Each named asset group maps to a subdirectory under a configurable root directory.
 * The default root is determined by {@link org.almostrealism.io.SystemUtils#getLocalDestination}
 * using {@link Asset#ASSETS_DIRECTORY}.</p>
 *
 * @see AssetInfoProvider
 * @see AssetGroupInfo#forDirectory(java.io.File)
 */
public class LocalAssetsProvider implements AssetInfoProvider {
	/** Root directory under which each named group's subdirectory is resolved. */
	private Path directory;

	/**
	 * Creates a {@code LocalAssetsProvider} using the default assets directory
	 * determined by {@link org.almostrealism.io.SystemUtils#getLocalDestination}.
	 */
	public LocalAssetsProvider() {
		this(Path.of(SystemUtils.getLocalDestination(Asset.ASSETS_DIRECTORY)));
	}

	/**
	 * Creates a {@code LocalAssetsProvider} rooted at the given directory.
	 *
	 * @param directory Root directory for all asset group subdirectories
	 */
	public LocalAssetsProvider(Path directory) {
		this.directory = directory;
	}

	/**
	 * Returns asset group metadata by scanning the subdirectory named {@code name} under the root.
	 *
	 * @param name The asset group name, resolved as a subdirectory of the root
	 * @return An {@link AssetGroupInfo} describing the files in that subdirectory,
	 *         or an empty group if the subdirectory does not exist
	 */
	@Override
	public AssetGroupInfo getAssetGroupInfo(String name) {
		return AssetGroupInfo.forDirectory(directory.resolve(name).toFile());
	}
}
