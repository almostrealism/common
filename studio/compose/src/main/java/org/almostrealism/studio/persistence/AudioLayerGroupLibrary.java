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

package org.almostrealism.studio.persistence;

import org.almostrealism.audio.AudioLibrary;
import org.almostrealism.audio.api.Audio;
import org.almostrealism.audio.data.FileWaveDataProvider;
import org.almostrealism.audio.data.WaveDetails;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.music.notes.GroupNoteSource;
import org.almostrealism.music.notes.NoteAudioSource;
import org.almostrealism.studio.AudioScene;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Group-aware companion to {@link AudioLibrary}: stores {@link Audio.AudioLayerGroup}s
 * as first-class library entries without breaking the semantic-index invariant.
 *
 * <p>This logic cannot live on {@link AudioLibrary} itself because that class
 * resides in the {@code engine/audio} module, which is upstream of the proto
 * types ({@link Audio}) generated in {@code studio/compose}. It therefore acts
 * as a thin coordinator that holds the {@link AudioLibrary}, the
 * {@link ProtobufLayerGroupStore}, and the on-disk library root.</p>
 *
 * <h2>Strip-and-store</h2>
 *
 * <p>{@link #includeGroup(Audio.AudioLayerGroup, Function)} saves a group by
 * routing every inline-audio member through the existing single-sample add
 * path so each member becomes an independently similarity-indexed entry in the
 * main details store, then rewriting each such layer's bulky inline
 * {@code audio} payload to a slim {@code audio_ref} (the member's MD5
 * identifier) before persisting the group. The save is all-or-nothing: if any
 * member fails, every WAV written and every index entry added during the call
 * is rolled back and {@link Optional#empty()} is returned.</p>
 *
 * @see ProtobufLayerGroupStore
 * @see AudioLibrary
 */
public class AudioLayerGroupLibrary implements ConsoleFeatures {

	/** The audio library whose details store receives stripped members. */
	private final AudioLibrary library;
	/** The slim store for {@link Audio.AudioLayerGroup} records. */
	private final ProtobufLayerGroupStore groupStore;
	/** Directory into which member WAVs are copied, content-addressed as {@code <md5>.wav}. */
	private final File libraryRoot;

	/**
	 * Creates a coordinator over the given library and group store.
	 *
	 * @param library     the audio library whose details store receives members
	 * @param groupStore  the slim store for {@link Audio.AudioLayerGroup} records
	 * @param libraryRoot the directory into which member WAVs are copied
	 *                    (content-addressed as {@code <md5>.wav})
	 */
	public AudioLayerGroupLibrary(AudioLibrary library,
								  ProtobufLayerGroupStore groupStore,
								  File libraryRoot) {
		this.library = library;
		this.groupStore = groupStore;
		this.libraryRoot = libraryRoot;
	}

	/** Returns the underlying {@link AudioLibrary}. */
	public AudioLibrary getLibrary() { return library; }

	/** Returns the backing {@link ProtobufLayerGroupStore}. */
	public ProtobufLayerGroupStore getGroupStore() { return groupStore; }

	/**
	 * Saves a group as a first-class library entry, preserving the
	 * semantic-index invariant.
	 *
	 * <p>For each layer whose {@code content} arm is inline {@code audio}, the
	 * layer's WAV (resolved via {@code wavSource}) is copied to
	 * {@code <libraryRoot>/<md5>.wav} and routed through the library's existing
	 * single-sample analysis path so the member lands in the main details
	 * store, findable by similarity like any other sample. The layer is then
	 * rewritten so its content is {@code audio_ref = <md5>}; all other layer
	 * fields (layer_id, au_state, device_type, created_at, transform,
	 * derived_from) are preserved. MIDI and metadata-only layers (and layers
	 * already carrying an {@code audio_ref}) are left untouched.</p>
	 *
	 * <p>WAV-copy collision policy: if a file with the same MD5 already occupies
	 * the target name, the copy is a no-op (byte-equivalent by construction); if
	 * a <em>different</em> file already occupies that name, the save fails rather
	 * than overwriting user-curated content.</p>
	 *
	 * <p>The save is all-or-nothing: a mid-group failure rolls back every WAV
	 * written and every index entry added during this call, and no group record
	 * is written.</p>
	 *
	 * @param group     the staged group to save
	 * @param wavSource resolves the source WAV file for an audio layer (for AU
	 *                  staging this is {@code StagedGroup::resolveWavForLayer})
	 * @return the stored group key on success, or {@link Optional#empty()} on
	 *         failure (after rollback)
	 */
	public Optional<String> includeGroup(Audio.AudioLayerGroup group,
										 Function<Audio.AudioLayer, File> wavSource) {
		if (group == null || group.getKey().isBlank()) {
			warn("Cannot save a group with no key");
			return Optional.empty();
		}

		List<File> wavsWritten = new ArrayList<>();
		List<String> idsAdded = new ArrayList<>();
		Audio.AudioLayerGroup.Builder slim = group.toBuilder();

		try {
			if (libraryRoot.exists()) {
				if (!libraryRoot.isDirectory()) {
					throw new GroupSaveException("Library root is not a directory: " + libraryRoot);
				}
			} else if (!libraryRoot.mkdirs()) {
				throw new GroupSaveException("Could not access library folder " + libraryRoot);
			}

			for (int i = 0; i < group.getLayersCount(); i++) {
				Audio.AudioLayer layer = group.getLayers(i);
				if (!layer.hasAudio()) continue;

				String md5 = stripAndStoreLayer(layer, wavSource, wavsWritten, idsAdded);
				slim.setLayers(i, layer.toBuilder().setAudioRef(md5).build());
			}

			Audio.AudioLayerGroup slimGroup = slim.build();
			groupStore.put(slimGroup);
			return Optional.of(slimGroup.getKey());
		} catch (Exception e) {
			warn("Failed to save group " + group.getKey() + " (" + e.getMessage() + "); rolling back");
			rollback(wavsWritten, idsAdded);
			return Optional.empty();
		}
	}

	/**
	 * Materialises a single audio layer into the main details store and returns
	 * its MD5 identifier. Records newly-written WAVs and newly-added store ids
	 * into the supplied rollback accumulators.
	 *
	 * @param layer        the audio layer to store
	 * @param wavSource    per-layer source WAV resolver
	 * @param wavsWritten  accumulator for WAVs created by this call
	 * @param idsAdded     accumulator for store ids added by this call
	 * @return the member's MD5 identifier (its {@code audio_ref})
	 * @throws GroupSaveException if the source WAV is missing, a different file
	 *         already occupies the target name, the copy fails, or analysis fails
	 */
	private String stripAndStoreLayer(Audio.AudioLayer layer,
									  Function<Audio.AudioLayer, File> wavSource,
									  List<File> wavsWritten, List<String> idsAdded) {
		File source = wavSource == null ? null : wavSource.apply(layer);
		if (source == null || !source.isFile()) {
			throw new GroupSaveException("No source WAV for audio layer " + layer.getLayerId());
		}

		String md5 = identifierOf(source);
		if (md5 == null) {
			throw new GroupSaveException("Could not compute identifier for " + source);
		}

		File target = new File(libraryRoot, md5 + ".wav");
		boolean storeHadBefore = (library.getStore() != null)
				? library.getStore().containsKey(md5)
				: library.get(md5) != null;
		if (target.exists()) {
			String existing = identifierOf(target);
			if (!md5.equals(existing)) {
				throw new GroupSaveException("A different file already occupies "
						+ target.getName() + " (expected " + md5 + ", found " + existing + ")");
			}
			// Same MD5 already present: byte-equivalent, nothing to copy.
		} else {
			copyWav(source, target);
			wavsWritten.add(target);
		}

		// Route through the existing single-sample analysis path so the member
		// lands in the main details store with its similarity embedding.
		WaveDetails details = library.getDetailsForFileAwait(target.getAbsolutePath(), true);
		if (details == null) {
			throw new GroupSaveException("Analysis failed for audio layer " + layer.getLayerId());
		}
		if (!storeHadBefore) idsAdded.add(md5);

		return md5;
	}

	/**
	 * Undoes the WAVs and index entries added during a failed
	 * {@link #includeGroup}. Index entries are removed first so a partially
	 * written store does not reference a since-deleted WAV.
	 */
	private void rollback(List<File> wavsWritten, List<String> idsAdded) {
		for (String id : idsAdded) {
			try {
				library.remove(id);
			} catch (Exception e) {
				warn("Rollback: could not remove index entry " + id + " (" + e.getMessage() + ")");
			}
		}
		for (File wav : wavsWritten) {
			try {
				Files.deleteIfExists(wav.toPath());
			} catch (Exception e) {
				warn("Rollback: could not delete " + wav + " (" + e.getMessage() + ")");
			}
		}
	}

	/**
	 * Loads every stored group for tree display.
	 *
	 * @return all stored groups
	 */
	public List<Audio.AudioLayerGroup> allGroups() {
		return groupStore.allGroups();
	}

	/**
	 * Builds a {@link GroupNoteSource} for every stored group, ready to be added
	 * alongside file/tree sources when assembling a scene's
	 * {@link org.almostrealism.music.notes.NoteAudioChoice} sources.
	 *
	 * <p>This is the render-side counterpart to {@link #includeGroup}: where that
	 * persists a group, this surfaces each persisted group as a single selectable
	 * candidate (via {@link NoteAudioGroupBuilder#source}), resolving member audio
	 * through the underlying {@link AudioLibrary} and member pitch through
	 * {@link AudioLayerPitch}. Single-sample sources are unaffected — group
	 * sources are simply additional candidates.</p>
	 *
	 * @return one {@link GroupNoteSource} per stored group
	 */
	public List<NoteAudioSource> groupSources() {
		List<NoteAudioSource> sources = new ArrayList<>();
		for (Audio.AudioLayerGroup group : allGroups()) {
			sources.add(NoteAudioGroupBuilder.source(group, library));
		}
		return sources;
	}

	/**
	 * Returns the stored group for the given key, or {@code null} if absent.
	 *
	 * @param key the group key
	 * @return the stored group, or {@code null}
	 */
	public Audio.AudioLayerGroup getGroup(String key) {
		return groupStore.get(key);
	}

	/**
	 * Computes the library content identifier (MD5 hex of the file bytes) for
	 * the given WAV, using the same mechanism as {@link FileWaveDataProvider}
	 * so it matches the identifier the details store assigns.
	 */
	private static String identifierOf(File file) {
		return new FileWaveDataProvider(file.getAbsolutePath()).getIdentifier();
	}

	/** Copies the source WAV to the target path, replacing nothing (caller checked). */
	private static void copyWav(File source, File target) {
		try {
			Files.copy(source.toPath(), target.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
		} catch (Exception e) {
			throw new GroupSaveException("Could not copy " + source + " to " + target
					+ " (" + e.getMessage() + ")");
		}
	}

	@Override
	public Console console() {
		return AudioScene.console;
	}

	/** Unchecked failure raised during a group save to trigger rollback. */
	private static final class GroupSaveException extends RuntimeException {
		/**
		 * Creates a save failure with the given message.
		 *
		 * @param message description of what went wrong
		 */
		GroupSaveException(String message) {
			super(message);
		}
	}
}
