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

public interface FileWaveDataProviderTree<T extends Tree<? extends Supplier<FileWaveDataProvider>>> extends Tree<T>, PathResource, Signature {
	default BooleanSupplier active() {
		return () -> true;
	}

	String getRelativePath(String path);

	static String getRelativePath(File root, String path) {
		String rootPath = root.getAbsolutePath();

		if (path.startsWith(rootPath)) {
			return path.substring(rootPath.length());
		} else {
			return path;
		}
	}
}
