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

import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
 *
 * <p>The library's single analysis worker adds identifiers (as it finishes
 * computing an item's features) while callers on other threads iterate the set —
 * {@link AudioLibrary#refresh()} both scans it and submits those jobs, so a
 * refresh can overlap with jobs still running from an earlier one. The set is
 * therefore backed by a {@link ConcurrentHashMap#newKeySet() concurrent key set}
 * so those additions never corrupt a concurrent scan or throw
 * {@link java.util.ConcurrentModificationException}; its iterator is weakly
 * consistent, which only ever omits an identifier a worker is adding because it
 * is complete for the current model — exactly the identifier a scan would keep.</p>
 */
final class CompleteIdentifierSet extends AbstractSet<String> {
	/** The complete identifiers, held in a thread-safe set with a weakly consistent iterator. */
	private final Set<String> identifiers = ConcurrentHashMap.newKeySet();

	/** The model this set was last checked against, or {@code null} if it has not been. */
	private volatile String modelId;

	@Override
	public Iterator<String> iterator() { return identifiers.iterator(); }

	@Override
	public int size() { return identifiers.size(); }

	@Override
	public boolean contains(Object o) { return identifiers.contains(o); }

	@Override
	public boolean add(String identifier) { return identifiers.add(identifier); }

	@Override
	public boolean remove(Object o) { return identifiers.remove(o); }

	/**
	 * Removes every identifier that fails {@code complete}, unless this set was
	 * already checked against {@code modelId}. Checking is costly (it reads each
	 * item), so it runs once per model rather than on every refresh.
	 *
	 * <p>The check, scan, removal, and publication of the new model are performed
	 * atomically: {@link AudioLibrary#refresh()} is public and may be invoked
	 * concurrently, and without this guarantee two overlapping refreshes could
	 * both observe the old model, both scan, and both return the same stale
	 * identifiers — enqueuing the same expensive recomputation work twice. The
	 * lock is uncontended in the common case because the method returns early
	 * once a model has been checked, and it never contends with the analysis
	 * worker, which adds through the lock-free backing set.</p>
	 *
	 * @param modelId  the current model, or {@code null} if there is none (no-op)
	 * @param complete whether the item with a given identifier is complete for that model
	 * @return the identifiers removed, so the caller can recompute them; empty
	 *         when there is no model or the set was already checked against it
	 */
	synchronized List<String> revalidate(String modelId, Predicate<String> complete) {
		if (modelId == null || modelId.equals(this.modelId)) return List.of();

		List<String> stale = new ArrayList<>(identifiers).stream()
				.filter(complete.negate())
				.toList();
		identifiers.removeAll(stale);
		this.modelId = modelId;
		return stale;
	}
}
