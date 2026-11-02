/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.audio;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.Predicate;

/**
 * The content identifiers of an {@link AudioLibrary}'s items known to have both
 * frequency data and feature data for the library's current model. The library
 * checks this set rather than loading each item, so {@link AudioLibrary#refresh()}
 * can skip complete items without reading evicted entries from disk.
 *
 * <p>A library opened over a store starts by assuming every stored item is
 * complete. That holds only for the model that produced the stored features, so
 * {@link #revalidate(String, Predicate)} checks the assumption against the
 * current model, once per model, and removes the items that would otherwise
 * never be recomputed.</p>
 */
final class CompleteIdentifierSet extends HashSet<String> {
	/** The model this set was last checked against, or {@code null} if it has not been. */
	private String modelId;

	/**
	 * Removes every identifier that fails {@code complete}, unless this set was
	 * already checked against {@code modelId}. Checking is costly (it reads each
	 * item), so it runs once per model rather than on every refresh.
	 *
	 * @param modelId  the current model, or {@code null} if there is none (no-op)
	 * @param complete whether the item with a given identifier is complete for that model
	 * @return the number of identifiers removed
	 */
	int revalidate(String modelId, Predicate<String> complete) {
		if (modelId == null || modelId.equals(this.modelId)) return 0;

		List<String> stale = new ArrayList<>(this).stream()
				.filter(complete.negate())
				.toList();
		removeAll(stale);
		this.modelId = modelId;
		return stale.size();
	}
}
