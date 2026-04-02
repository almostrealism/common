/*
 * Copyright 2018 Michael Murray
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

package io.almostrealism.nfs;

import io.almostrealism.relation.Factory;
import io.almostrealism.resource.Resource;

/**
 * Composite interface for managing resources in the virtual file system.
 *
 * <p>A {@link FileSystemManager} combines resource creation ({@link Factory}),
 * path resolution ({@link SearchEngine}), directory creation ({@link DirectoryNotifier}),
 * and deletion ({@link DeletionNotifier}) into a single service interface.</p>
 *
 * @param <T> The type of {@link Resource} managed by this file system
 */
public interface FileSystemManager<T extends Resource> extends Factory<T>, SearchEngine,
													DirectoryNotifier, DeletionNotifier {
}
