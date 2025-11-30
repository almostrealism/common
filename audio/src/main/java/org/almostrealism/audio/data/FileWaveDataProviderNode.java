/*
 * Copyright 2024 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.audio.data;

import io.almostrealism.uml.Named;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class FileWaveDataProviderNode implements FileWaveDataProviderTree<FileWaveDataProviderNode>, Supplier<FileWaveDataProvider>, Named {
	private final File file;

	public FileWaveDataProviderNode(File f) {
		this.file = f;
	}

	@Override
	public String getName() { return file.getName(); }

	@Override
	public String getResourcePath() {
		try {
			return file.getCanonicalPath();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public String getRelativePath(String path) {
		return FileWaveDataProviderTree.getRelativePath(file, path);
	}

	@Override
	public Collection<FileWaveDataProviderNode> getChildren() {
		if (isLeaf()) return Collections.emptyList();
		return Stream.of(file.listFiles()).map(FileWaveDataProviderNode::new).collect(Collectors.toList());
	}

	@Override
	public FileWaveDataProvider get() {
		if (!isLeaf()) return null;
		if (!file.exists()) return null;
		if (file.getName().equals(".DS_Store")) return null;

		String ext = file.getName().substring(file.getName().length() - 4);
		if (!ext.contains("wav") && !ext.contains("WAV")) return null;

		try {
			return new FileWaveDataProvider(file.getCanonicalPath());
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		}
	}

	public boolean isLeaf() {
		return !file.isDirectory();
	}

	@Override
	public String signature() { return file.getPath(); }
}
