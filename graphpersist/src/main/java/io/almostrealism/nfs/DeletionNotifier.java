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

/**
 * Listener interface for receiving notifications when a file or directory is deleted
 * from the virtual file system.
 */
public interface DeletionNotifier {
	/**
	 * Called when a resource at the specified path is to be deleted.
	 *
	 * @param path The path of the resource to delete
	 * @return {@code true} if the deletion was successful, {@code false} otherwise
	 */
	boolean delete(String path);
}
