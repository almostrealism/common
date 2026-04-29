/*
 * Copyright 2020 Michael Murray
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

package org.almostrealism.graph;

import io.almostrealism.relation.Producer;

/**
 * A producer of sequential values for pull-based data retrieval.
 *
 * <p>{@code Source} defines the interface for objects that can produce a sequence
 * of values one at a time. Callers check {@link #isDone()} before calling
 * {@link #next()} to retrieve the next value.</p>
 *
 * <p>This interface is implemented by {@link CachedStateCell} to allow external
 * consumers to retrieve current output values without triggering push operations.</p>
 *
 * @param <T> the type of values produced
 * @see CachedStateCell
 * @author Michael Murray
 */
public interface Source<T> {
	/**
	 * Returns a producer for the next value in the sequence.
	 *
	 * @return a producer for the next value
	 */
	Producer<T> next();

	/**
	 * Returns true when no more values are available.
	 *
	 * @return true if the source is exhausted, false if more values remain
	 */
	boolean isDone();
}
