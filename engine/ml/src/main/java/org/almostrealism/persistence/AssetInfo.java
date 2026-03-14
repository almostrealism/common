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

/**
 * Metadata for a single asset file.
 *
 * <p>This class holds metadata about an asset including its name, download URL,
 * file size, and MD5 checksum for verification. It is used as part of
 * {@link AssetGroupInfo} to describe available downloads.</p>
 *
 * <h2>Properties</h2>
 * <ul>
 *   <li><strong>name:</strong> File name of the asset</li>
 *   <li><strong>url:</strong> Download URL (optional for local-only assets)</li>
 *   <li><strong>size:</strong> File size in bytes</li>
 *   <li><strong>md5:</strong> MD5 checksum for verification (optional)</li>
 * </ul>
 *
 * @see AssetGroupInfo
 * @see Asset
 */
public class AssetInfo {
	private String name;
	private String url;
	private long size;
	private String md5;

	/**
	 * Creates an empty AssetInfo. Properties must be set via setters.
	 */
	public AssetInfo() { }

	/**
	 * Creates an AssetInfo with the specified name.
	 *
	 * @param name The asset file name
	 */
	public AssetInfo(String name) {
		setName(name);
	}

	/**
	 * @return The asset file name
	 */
	public String getName() { return name; }

	/**
	 * @param name The asset file name
	 */
	public void setName(String name) {
		this.name = name;
	}

	/**
	 * @return The download URL, or null if not available for download
	 */
	public String getUrl() { return url; }

	/**
	 * @param url The download URL
	 */
	public void setUrl(String url) {
		this.url = url;
	}

	/**
	 * @return The file size in bytes
	 */
	public long getSize() { return size; }

	/**
	 * @param size The file size in bytes
	 */
	public void setSize(long size) {
		this.size = size;
	}

	/**
	 * @return The MD5 checksum for verification, or null if not available
	 */
	public String getMd5() { return md5; }

	/**
	 * @param md5 The MD5 checksum string
	 */
	public void setMd5(String md5) {
		this.md5 = md5;
	}
}
