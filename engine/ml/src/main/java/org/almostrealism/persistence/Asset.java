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

import org.almostrealism.io.SystemUtils;

import java.io.File;
import java.nio.file.Path;

/**
 * Represents a downloadable or local asset file (such as model weights, tokenizers, or datasets)
 * with metadata for verification and automatic downloading.
 *
 * <p>An {@code Asset} encapsulates information about a file that may be stored locally or
 * available for download from a remote URL. It supports automatic downloading with MD5
 * verification to ensure file integrity. Assets are organized into groups (e.g., by model
 * name) and stored in a standard directory structure.</p>
 *
 * <h2>Key Features</h2>
 * <ul>
 *   <li><strong>Automatic Downloads:</strong> Downloads files from URLs if not present locally</li>
 *   <li><strong>MD5 Verification:</strong> Validates file integrity using MD5 checksums</li>
 *   <li><strong>Group Organization:</strong> Organizes assets by group (e.g., model name)</li>
 *   <li><strong>Lazy Loading:</strong> Files are only downloaded when accessed via {@link #getFile()}</li>
 * </ul>
 *
 * <h2>Typical Usage</h2>
 *
 * <p><strong>Creating an asset from metadata:</strong></p>
 * <pre>{@code
 * AssetInfo info = new AssetInfo("tokenizer.json",
 *     "https://huggingface.co/.../tokenizer.json",
 *     "abc123def456", 1024000);
 * Asset asset = new Asset("llama3-8b", info);
 *
 * // File will be downloaded if not present
 * File file = asset.getFile();
 * }</pre>
 *
 * <p><strong>Wrapping an existing file:</strong></p>
 * <pre>{@code
 * File existingFile = new File("/path/to/weights.pb");
 * Asset asset = new Asset(existingFile);
 * }</pre>
 *
 * <p><strong>Checking if asset is available:</strong></p>
 * <pre>{@code
 * if (asset.isLoaded()) {
 *     // File exists locally and passes MD5 verification
 *     processFile(asset.getFile());
 * } else {
 *     // File will be downloaded on next getFile() call
 *     System.out.println("Asset will be downloaded from: " + asset.getUrl());
 * }
 * }</pre>
 *
 * @see AssetInfo
 * @see AssetGroup
 * @see org.almostrealism.io.SystemUtils
 */
public class Asset {
	/** Default directory name for storing assets locally. */
	public static final String ASSETS_DIRECTORY = "assets";

	/** The asset group identifier (e.g., model name). */
	private String group;

	/** The asset file name. */
	private String name;

	/** The download URL for this asset, if available. */
	private String url;

	/** MD5 checksum for file verification, or null if verification is not required. */
	private String md5;

	/** File size in bytes. */
	private long size;

	/** The local file reference, resolved lazily. */
	private File file;

	/**
	 * Creates an empty asset with no initial configuration.
	 * Properties must be set via setter methods.
	 */
	public Asset() { }

	/**
	 * Creates an asset wrapping an existing local file.
	 * The asset name and size are inferred from the file.
	 *
	 * @param file The local file to wrap
	 */
	public Asset(File file) {
		this.file = file;
		this.name = file.getName();
		this.size = file.length();
	}

	/**
	 * Creates an asset from metadata information.
	 * The file will be downloaded from the URL when {@link #getFile()} is called
	 * if it's not already present locally.
	 *
	 * @param group The asset group identifier (e.g., model name)
	 * @param info The asset metadata including name, URL, MD5, and size
	 */
	public Asset(String group, AssetInfo info) {
		this.group = group;
		this.name = info.getName();
		this.url = info.getUrl();
		this.md5 = info.getMd5();
		this.size = info.getSize();
	}

	/**
	 * @return The asset group identifier
	 */
	public String getGroup() { return group; }

	/**
	 * Sets the asset group identifier.
	 *
	 * @param group The group identifier (e.g., model name)
	 */
	public void setGroup(String group) {
		this.group = group;
	}

	/**
	 * @return The asset file name
	 */
	public String getName() { return name; }

	/**
	 * Sets the asset file name.
	 *
	 * @param name The file name
	 */
	public void setName(String name) {
		this.name = name;
	}

	/**
	 * @return The download URL, or null if no URL is configured
	 */
	public String getUrl() { return url; }

	/**
	 * Sets the download URL for this asset.
	 *
	 * @param url The URL from which the asset can be downloaded
	 */
	public void setUrl(String url) {
		this.url = url;
	}

	/**
	 * @return The MD5 checksum for verification, or null if verification is not required
	 */
	public String getMd5() { return md5; }

	/**
	 * Sets the MD5 checksum for file verification.
	 *
	 * @param md5 The MD5 checksum string
	 */
	public void setMd5(String md5) {
		this.md5 = md5;
	}

	/**
	 * @return The file size in bytes
	 */
	public long getSize() { return size; }

	/**
	 * Sets the expected file size.
	 *
	 * @param size The file size in bytes
	 */
	public void setSize(long size) {
		this.size = size;
	}

	/**
	 * Checks if the asset file is available locally and passes MD5 verification.
	 *
	 * @return true if the file exists locally and is valid, false otherwise
	 */
	public boolean isLoaded() {
		return confirmFile();
	}

	/**
	 * Gets the asset file, downloading it if necessary.
	 *
	 * <p>This method returns the local file if it exists and passes MD5 verification.
	 * If the file doesn't exist locally and a URL is configured, it will be downloaded
	 * automatically with MD5 verification.</p>
	 *
	 * @return The local file, or null if the file cannot be obtained
	 */
	public File getFile() {
		if (confirmFile()) {
			return file;
		} else if (getUrl() != null) {
			return SystemUtils.download(getUrl(), ensureFile().getAbsolutePath(), getMd5());
		}

		return null;
	}

	/**
	 * Resolves the expected local file path for this asset.
	 *
	 * @return The expected file path, or null if group or name is not configured
	 */
	private File ensureFile() {
		if (file != null) return file;
		if (group == null || name == null) return null;
		return getAssetsDirectory(group).resolve(name).toFile();
	}

	/**
	 * Verifies that the asset file exists locally and passes MD5 verification.
	 *
	 * @return true if the file exists and is valid, false otherwise
	 */
	private boolean confirmFile() {
		file = ensureFile();
		return file != null && file.exists() &&
				(getMd5() == null || getMd5().equals(SystemUtils.md5(file)));
	}

	/**
	 * Gets the local assets directory for a given group.
	 * The directory will be created if it doesn't exist.
	 *
	 * @param group The asset group identifier
	 * @return Path to the group's assets directory
	 */
	public static Path getAssetsDirectory(String group) {
		Path groupDir = Path.of(SystemUtils.getLocalDestination(ASSETS_DIRECTORY, group));
		SystemUtils.ensureDirectoryExists(groupDir);
		return groupDir;
	}
}
