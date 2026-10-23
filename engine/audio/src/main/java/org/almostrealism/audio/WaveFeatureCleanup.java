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

import org.almostrealism.audio.data.WaveDetails;

/**
 * A report of the feature data an {@link AudioLibrary} holds for models other
 * than the current one, with the operation that deletes exactly that data.
 *
 * <p>Modelled on {@code LegacyLibraryMigrator.Result}: it carries the headline
 * figures an application shows a user before offering the cleanup — the number
 * of items that carry other-model data and the total bytes that data occupies,
 * including each item's legacy unkeyed slot — and it carries the operation that
 * performs the deletion. The per-item logic lives on {@link WaveDetails} and
 * {@link org.almostrealism.audio.data.WaveFeatures}; this type only aggregates
 * it and drives the re-persist.</p>
 *
 * @param library             the library the report was produced from
 * @param keepModelIdentifier the identifier of the model whose data is kept
 * @param itemCount           the number of items carrying other-model data
 * @param totalBytes          the total other-model feature data size in bytes
 * @see AudioLibrary#otherModelFeatures()
 */
public record WaveFeatureCleanup(AudioLibrary library, String keepModelIdentifier,
								 int itemCount, long totalBytes) {

	/**
	 * Scans the library and reports how much feature data belongs to models
	 * other than the given one.
	 *
	 * @param library             the library to scan
	 * @param keepModelIdentifier the identifier of the model to keep
	 * @return the report
	 */
	public static WaveFeatureCleanup report(AudioLibrary library, String keepModelIdentifier) {
		int itemCount = 0;
		long totalBytes = 0;

		for (WaveDetails details : library.allDetails().toList()) {
			if (details.hasOtherModelFeatures(keepModelIdentifier)) {
				itemCount++;
				totalBytes += details.otherModelByteLength(keepModelIdentifier);
			}
		}

		return new WaveFeatureCleanup(library, keepModelIdentifier, itemCount, totalBytes);
	}

	/**
	 * Deletes every feature set and legacy unkeyed slot belonging to a model
	 * other than {@link #keepModelIdentifier()}, asking the library to
	 * {@linkplain AudioLibrary#persist(WaveDetails) re-persist} each affected
	 * item's kept data (with a refreshed embedding when the library is
	 * store-backed) so the removal survives a reload.
	 *
	 * @return the number of items from which other-model data was removed
	 */
	public int remove() {
		int removed = 0;

		for (WaveDetails details : library.allDetails().toList()) {
			if (!details.hasOtherModelFeatures(keepModelIdentifier)) continue;

			details.removeOtherModelFeatures(keepModelIdentifier);
			library.persist(details);
			removed++;
		}

		return removed;
	}
}
