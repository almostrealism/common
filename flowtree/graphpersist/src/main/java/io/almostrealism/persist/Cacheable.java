/*
 * Copyright 2016 Michael Murray
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

package io.almostrealism.persist;

import java.io.Serializable;

/**
 * Interface for objects that can be serialized to and restored from a cache.
 *
 * <p>Implementors convert their state to a {@link Serializable} snapshot via
 * {@link #toCache()} and restore state from such a snapshot via {@link #fromCache(Serializable)}.</p>
 *
 * @author  Michael Murray
 */
public interface Cacheable {
	/**
	 * Returns a {@link Serializable} snapshot of the current state for caching.
	 *
	 * @return A serializable representation of this object's state
	 */
	Serializable toCache();

	/**
	 * Restores the object's state from a previously cached snapshot.
	 *
	 * @param s The serializable snapshot to restore from
	 */
	void fromCache(Serializable s);
}
