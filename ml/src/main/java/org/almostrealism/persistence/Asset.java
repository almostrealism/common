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

public class Asset {
	public static final String ASSETS_DIRECTORY = "assets";

	private String group;
	private String name;
	private String url;
	private String md5;

	private File file;

	public Asset() { }

	public Asset(File file) {
		this.file = file;
		this.name = file.getName();
	}

	public Asset(String group, AssetInfo info) {
		this.group = group;
		this.name = info.getName();
		this.url = info.getUrl();
		this.md5 = info.getMd5();
	}

	public String getGroup() { return group; }
	public void setGroup(String group) {
		this.group = group;
	}

	public String getName() { return name; }
	public void setName(String name) {
		this.name = name;
	}

	public String getUrl() { return url; }
	public void setUrl(String url) {
		this.url = url;
	}

	public String getMd5() { return md5; }
	public void setMd5(String md5) {
		this.md5 = md5;
	}

	public boolean isLoaded() {
		return confirmFile();
	}

	public File getFile() {
		if (confirmFile()) {
			return file;
		} else if (getUrl() != null) {
			return SystemUtils.download(getUrl(), ensureFile().getAbsolutePath(), getMd5());
		}

		return null;
	}

	private File ensureFile() {
		if (file != null) return file;
		if (group == null || name == null) return null;
		return getAssetsDirectory(group).resolve(name).toFile();
	}

	private boolean confirmFile() {
		file = ensureFile();
		return file != null && file.exists() &&
				(getMd5() == null || getMd5().equals(SystemUtils.md5(file)));
	}

	public static Path getAssetsDirectory(String group) {
		Path groupDir = Path.of(SystemUtils.getLocalDestination(ASSETS_DIRECTORY, group));
		SystemUtils.ensureDirectoryExists(groupDir);
		return groupDir;
	}
}
