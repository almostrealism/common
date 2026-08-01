/*
 * Copyright 2025 Michael Murray
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

import io.almostrealism.relation.Tree;
import io.almostrealism.uml.Signature;

import java.io.File;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * A tree structure for organizing and navigating audio file providers.
 *
 * <p>FileWaveDataProviderTree extends the {@link Tree} interface to provide
 * hierarchical organization of audio files, typically mirroring a file system
 * directory structure. Each node can supply a {@link FileWaveDataProvider}
 * for leaf nodes (files) or child trees for directories.</p>
 *
 * @param <T> the type of tree nodes
 * @see FileWaveDataProviderNode
 * @see org.almostrealism.audio.AudioLibrary
 */
public interface FileWaveDataProviderTree<T extends Tree<? extends Supplier<FileWaveDataProvider>>> extends Tree<T>, PathResource, Signature {
	/**
	 * Returns a supplier that always reports this tree node as active.
	 * Implementations may override this to support conditional visibility.
	 *
	 * @return a BooleanSupplier returning true
	 */
	default BooleanSupplier active() {
		return () -> true;
	}

	/**
	 * Returns the path of this node relative to a root path.
	 *
	 * @param path the absolute path to compute the relative path from
	 * @return the relative path, or the original path if not under this node's root
	 */
	String getRelativePath(String path);

	/**
	 * Computes the path of a file relative to the given root directory.
	 *
	 * @param root the root directory
	 * @param path the absolute path to make relative
	 * @return the relative path if {@code path} starts with {@code root}'s absolute path, otherwise {@code path}
	 */
	static String getRelativePath(File root, String path) {
		String rootPath = root.getAbsolutePath();

		if (path.startsWith(rootPath)) {
			return path.substring(rootPath.length());
		} else {
			return path;
		}
	}
}
