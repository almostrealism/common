/*
 * Copyright 2023 Michael Murray
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

/**
 * A resource with an associated file system path.
 *
 * <p>PathResource provides access to the file system location of a resource,
 * useful for resources backed by files that need to expose their location
 * for display, logging, or further file operations.</p>
 *
 * @see FileWaveDataProvider
 * @see FileWaveDataProviderTree
 */
public interface PathResource {
	String getResourcePath();
}
