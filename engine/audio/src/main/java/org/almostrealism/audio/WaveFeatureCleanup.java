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

import org.almostrealism.audio.data.TagModelVersion;
import org.almostrealism.audio.data.WaveDetails;

import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;

/**
 * A report of the stale analysis data an {@link AudioLibrary} holds, with the
 * operation that deletes exactly that data.
 *
 * <p>Analysis data is stored per producing model so that changing the model
 * never silently mixes outputs of different models. Data belonging to a model
 * other than the current one is kept until this cleanup is run deliberately, so
 * that a user who reverts to an earlier application version keeps working
 * data. Two kinds of analysis data are covered:</p>
 * <ul>
 *   <li><b>Feature data</b> ({@link #report(AudioLibrary, String)}): every keyed
 *       feature set of another model, plus each item's legacy unkeyed slot. The
 *       report carries the {@linkplain #totalBytes() bytes} that data occupies.</li>
 *   <li><b>Tag data</b> ({@link #report(AudioLibrary, TagModelVersion)}): every
 *       tag set whose tagging model, vocabulary version, or both differ from the
 *       kept one. The report carries the {@linkplain #staleTagSetCount() number of
 *       stale tag sets}; tags hold no native memory, so {@link #totalBytes()} is
 *       zero.</li>
 * </ul>
 *
 * <p>Modelled on {@code LegacyLibraryMigrator.Result}: it carries the headline
 * figures an application shows a user before offering the cleanup, and the
 * operation that performs the deletion. The per-item logic lives on
 * {@link WaveDetails}; this type only aggregates it and drives the re-persist.</p>
 *
 * @see AudioLibrary#otherModelFeatures()
 * @see LibraryCleanup
 */
public final class WaveFeatureCleanup implements LibraryCleanup {
	/** The explanation shared by both kinds of stale analysis data. */
	private static final String KEPT = " from an earlier version of the application that "
			+ "this version no longer uses; they are kept only so you can switch back to "
			+ "an older version of the app.";

	/** The library the report was produced from. */
	private final AudioLibrary library;

	/** Whether an item carries data this cleanup removes. */
	private final Predicate<WaveDetails> stale;

	/** Removes the stale data from one item, leaving the kept data. */
	private final Consumer<WaveDetails> remover;

	/** The number of items carrying stale data. */
	private final int itemCount;

	/** The total size in bytes of the stale feature data. */
	private final long totalBytes;

	/** The total number of stale tag sets across the reported items. */
	private final int staleTagSetCount;

	/**
	 * Creates a report by scanning the library with the given per-item
	 * operations.
	 *
	 * @param library the library to scan
	 * @param stale   whether an item carries stale data
	 * @param remover removes the stale data from one item
	 * @param bytes   the stale feature byte size of one item
	 * @param tagSets the stale tag set count of one item
	 */
	private WaveFeatureCleanup(AudioLibrary library, Predicate<WaveDetails> stale,
							   Consumer<WaveDetails> remover,
							   ToLongFunction<WaveDetails> bytes,
							   ToIntFunction<WaveDetails> tagSets) {
		this.library = library;
		this.stale = stale;
		this.remover = remover;

		int items = 0;
		long byteTotal = 0;
		int tagSetTotal = 0;

		for (WaveDetails details : library.allDetails().toList()) {
			if (!stale.test(details)) continue;

			items++;
			byteTotal += bytes.applyAsLong(details);
			tagSetTotal += tagSets.applyAsInt(details);
		}

		this.itemCount = items;
		this.totalBytes = byteTotal;
		this.staleTagSetCount = tagSetTotal;
	}

	/**
	 * Scans the library and reports how much feature data belongs to models
	 * other than the given one.
	 *
	 * @param library             the library to scan
	 * @param keepModelIdentifier the identifier of the model to keep
	 * @return the report
	 */
	public static WaveFeatureCleanup report(AudioLibrary library, String keepModelIdentifier) {
		return new WaveFeatureCleanup(library,
				details -> details.hasOtherModelFeatures(keepModelIdentifier),
				details -> details.removeOtherModelFeatures(keepModelIdentifier),
				details -> details.otherModelByteLength(keepModelIdentifier),
				details -> 0);
	}

	/**
	 * Scans the library and reports the tag sets whose (tagging model,
	 * vocabulary version) differs from the given one.
	 *
	 * @param library the library to scan
	 * @param keep    the tagging model and vocabulary version to keep
	 * @return the report
	 */
	public static WaveFeatureCleanup report(AudioLibrary library, TagModelVersion keep) {
		return new WaveFeatureCleanup(library,
				details -> details.hasOtherModelTags(keep),
				details -> details.removeOtherModelTags(keep),
				details -> 0L,
				details -> details.otherModelTagCount(keep));
	}

	/** Returns the library the report was produced from. */
	public AudioLibrary library() { return library; }

	/** Returns the number of items carrying stale data. */
	public int itemCount() { return itemCount; }

	/**
	 * Returns the total size in bytes of the stale feature data, including each
	 * item's legacy unkeyed slot. Zero for a tag report.
	 */
	@Override
	public long totalBytes() { return totalBytes; }

	/** Returns the total number of stale tag sets. Zero for a feature report. */
	public int staleTagSetCount() { return staleTagSetCount; }

	/**
	 * Present when the report holds stale tag sets or stale feature bytes. A tag
	 * report never carries bytes, so its byte total alone would hide a pending
	 * tag cleanup.
	 */
	@Override
	public boolean isPresent() {
		return staleTagSetCount > 0 || totalBytes > 0;
	}

	/**
	 * Describes the stale data as descriptive tags when the report carries stale
	 * tag sets, and as sound analysis otherwise.
	 */
	@Override
	public String description() {
		String data = staleTagSetCount > 0
				? staleTagSetCount + " sets of descriptive tags"
				: "sound analysis";
		return itemCount + " items carry " + data + KEPT;
	}

	/**
	 * Deletes the stale data this report covers from every affected item, asking
	 * the library to {@linkplain AudioLibrary#persist(WaveDetails) re-persist}
	 * each one's kept data (with a refreshed embedding when the library is
	 * store-backed) so the removal survives a reload.
	 *
	 * @return the number of items from which stale data was removed
	 */
	@Override
	public int remove() {
		int removed = 0;

		for (WaveDetails details : library.allDetails().toList()) {
			if (!stale.test(details)) continue;

			remover.accept(details);
			library.persist(details);
			removed++;
		}

		return removed;
	}
}
