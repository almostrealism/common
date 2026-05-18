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

import io.almostrealism.resource.Resource;

/**
 * Interface for resolving resource paths to their corresponding {@link Resource} objects
 * within the virtual file system.
 *
 * <p>Implementations may support glob-style patterns (e.g., {@code /dir/*}) to list
 * all resources under a given directory path.</p>
 */
public interface SearchEngine {
	/**
	 * Resolves the given path to the matching {@link Resource} objects.
	 *
	 * @param path The resource path to search for, optionally including glob patterns
	 * @return An iterable of matching resources; may be empty if no resources match
	 */
	Iterable<Resource> search(String path);
}
