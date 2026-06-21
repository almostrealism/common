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

package io.almostrealism.code;

import io.almostrealism.scope.Variable;

/**
 * Maps keys to scope variables, retrieving the variable for an argument during code generation.
 *
 * <p>{@link ArgumentMap} is used during scope compilation to retrieve the variable corresponding
 * to an argument key, creating it on demand.</p>
 *
 * @param <K> the type of keys used to identify arguments (typically Producer or Supplier)
 * @param <V> the type of variable created for arguments
 * @see Variable
 */
public interface ArgumentMap<K, V extends Variable> {
	/**
	 * Retrieves or creates a variable for the given argument key.
	 *
	 * @param key the key identifying the argument
	 * @return the variable associated with the key
	 */
	V get(K key);

	/**
	 * Releases resources held by this argument map.
	 *
	 * <p>Called when the map is no longer needed, allowing implementations
	 * to clean up any cached state.</p>
	 */
	default void destroy() { }
}
