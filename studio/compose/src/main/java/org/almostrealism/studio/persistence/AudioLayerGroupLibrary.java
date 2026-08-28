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
import org.almostrealism.audio.data.FileWaveDataProviderFilter;
import org.almostrealism.audio.data.WaveDetails;
import org.almostrealism.audio.tone.KeyPosition;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.music.notes.GroupNoteSource;
import org.almostrealism.music.notes.NoteAudioSource;
import org.almostrealism.studio.AudioScene;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
	 * Returns the content identifier a layer's audio is stored under, whether
	 * the layer carries a reference to it or still carries the audio inline.
	 *
	 * @param layer the layer
	 * @return the identifier, or {@code null} for a layer with no audio
	 */
	public static String audioRef(Audio.AudioLayer layer) {
		String ref = null;

		if (layer.getContentCase() == Audio.AudioLayer.ContentCase.AUDIO_REF) {
			ref = layer.getAudioRef();
		} else if (layer.hasAudio() && !layer.getAudio().getIdentifier().isBlank()) {
			ref = layer.getAudio().getIdentifier();
		}

		return (ref == null || ref.isBlank()) ? null : ref;
	}

	/**
	 * Returns the content identifiers claimed by the given groups.
	 *
	 * <p>A file whose identifier is claimed belongs to a group already. One
	 * whose identifier is not is loose, and gets a group of its own
	 * ({@link #syntheticGroup}).</p>
	 *
	 * @param groups the groups to read
	 * @return the identifiers they reference
	 */
	public Set<String> claimedIdentifiers(List<Audio.AudioLayerGroup> groups) {
		Set<String> claimed = new HashSet<>();

		for (Audio.AudioLayerGroup group : groups) {
			for (Audio.AudioLayer layer : group.getLayersList()) {
				String ref = audioRef(layer);
				if (ref != null) claimed.add(ref);
			}
		}

		return claimed;
	}

	/**
	 * Returns the files that saved groups claim as members.
	 *
	 * <p>A claimed file is shown under the group that claims it, so it does not
	 * also get a row of its own. It may be claimed by several groups and appear
	 * under each of them; what it must not do is appear a further time as a
	 * loose file, which would leave the user unable to tell which row carries
	 * the metadata.</p>
	 *
	 * <p>Resolved through the library's index, like placement and for the same
	 * reason: this is asked while the tree is being built. A library that has
	 * not been indexed claims nothing, so nothing is hidden — the tree shows
	 * what it showed before rather than losing rows it cannot yet justify
	 * losing.</p>
	 *
	 * @return the claimed files, by canonical path
	 */
	public Set<File> claimedFiles() {
		Set<File> claimed = new HashSet<>();
		if (library == null) return claimed;

		for (Audio.AudioLayerGroup group : allGroups()) {
			claimed.addAll(membersOf(group));
		}

		return claimed;
	}

	/**
	 * Returns a group standing for a file that no saved group claims.
	 *
	 * <p>The library is a library of groups, and a loose file is the
	 * degenerate case of one: a single layer, named for the file, carrying
	 * nothing the file does not already say. Making that case explicit is what
	 * lets everything downstream — placement, filtering, selection — have one
	 * kind of thing to reason about instead of two.</p>
	 *
	 * <p>Synthetic groups are derived, never saved. Persisting one would make
	 * the library's own directory a source of records, so that a file could not
	 * be moved or removed without leaving a record behind that outlived it.</p>
	 *
	 * @param file       the loose file
	 * @param identifier the file's content identifier
	 * @return a one-layer group standing for the file
	 */
	public Audio.AudioLayerGroup syntheticGroup(File file, String identifier) {
		return Audio.AudioLayerGroup.newBuilder()
				.setKey(file.getName())
				.addLayers(Audio.AudioLayer.newBuilder()
						.setLayerId(file.getName())
						.setAudioRef(identifier))
				.build();
	}

	/**
	 * Returns whether the given group was synthesized for a loose file rather
	 * than saved.
	 *
	 * <p>A synthesized group is not in the store, so a group the store does not
	 * know about is one that was derived. This is what tells apart a saved
	 * one-layer group — which carries real metadata — from the one standing in
	 * for a file.</p>
	 *
	 * @param group the group to test
	 * @return whether it was derived rather than saved
	 */
	public boolean isSynthetic(Audio.AudioLayerGroup group) {
		return groupStore == null || getGroup(group.getKey()) == null;
	}

	/**
	 * Returns the files a group's layers resolve to, in layer order.
	 *
	 * <p>Layers that resolve to nothing are left out rather than reported as
	 * missing: a group outlives the files it names, and one that has lost a
	 * member is still a group with the members it has.</p>
	 *
	 * <p>Resolution goes through the library's index rather than a search of
	 * the tree, because this is asked while the tree is being built — a
	 * directory's contents include the groups that belong in it, and where a
	 * group belongs depends on where its members are. Searching the tree here
	 * would re-enter the build that is waiting on the answer. An unindexed
	 * library therefore yields no members, and so no group rows, until it has
	 * been indexed and the tree rebuilt.</p>
	 *
	 * @param group the group
	 * @return the files its layers resolve to, possibly empty
	 */
	public List<File> membersOf(Audio.AudioLayerGroup group) {
		List<File> files = new ArrayList<>();
		if (library == null) return files;

		for (Audio.AudioLayer layer : group.getLayersList()) {
			String ref = audioRef(layer);
			if (ref == null) continue;

			File file = library.indexedFileFor(ref);
			if (file != null) files.add(file);
		}

		return files;
	}

	/**
	 * Returns the directory a group belongs in: the most specific one holding
	 * every member that can be found.
	 *
	 * <p>A group is not itself a file, so where it belongs has to be derived
	 * from where its members are. The most specific directory containing all
	 * of them is the one a user would look in — a group whose members are
	 * spread across two folders belongs in the folder that holds both, and one
	 * whose members share a folder belongs in that folder rather than above
	 * it.</p>
	 *
	 * <p>Members that cannot be found are left out of the calculation. If a
	 * missing file counted, a group would climb toward the root each time the
	 * library lost one of its samples, rearranging itself for a reason the
	 * user cannot see. A group none of whose members can be found has no
	 * derivable location and belongs at the root, where it remains visible as
	 * something to repair.</p>
	 *
	 * @param group the group to place
	 * @return the directory the group belongs in, never {@code null}
	 */
	public File locate(Audio.AudioLayerGroup group) {
		return commonDirectory(membersOf(group));
	}

	/**
	 * Returns the pitch each captured sample was rendered at, by file.
	 *
	 * <p>A sample chosen from a folder is treated as being at whatever pitch
	 * its source was configured with, because a folder of files records nothing
	 * about any one of them. A captured layer does record it: the pitch was
	 * known at the moment the audio was rendered, and it is exact rather than
	 * inferred from a name. Where that is known it is the answer, and this is
	 * how the selection path gets at it.</p>
	 *
	 * <p>Layers with no captured pitch are absent rather than present with a
	 * guess, so a sample nothing knows about goes on being treated as before.
	 * Resolution goes through the index, as everything asked during a tree
	 * build must.</p>
	 *
	 * @return the captured pitch of each file that has one
	 */
	public Map<File, KeyPosition<?>> capturedPitches() {
		Map<File, KeyPosition<?>> pitches = new HashMap<>();
		if (library == null) return pitches;

		for (Audio.AudioLayerGroup group : allGroups()) {
			for (Audio.AudioLayer layer : group.getLayersList()) {
				String ref = audioRef(layer);
				if (ref == null) continue;

				KeyPosition<?> captured = AudioLayerPitch.capturedKeyPosition(layer);
				if (captured == null) continue;

				File file = library.indexedFileFor(ref);
				if (file != null) pitches.putIfAbsent(file, captured);
			}
		}

		return pitches;
	}

	/**
	 * Returns the value a filter matches a group against.
	 *
	 * <p>A filter selects a name or a path from whatever it is filtering. For a
	 * file those are the file's own; for a group they are the group's own: the
	 * key it is known by, and the directory it belongs in. A group whose members
	 * are spread across several folders has one place it belongs and so one path
	 * to be filtered on, which deriving the value from the members could not
	 * give.</p>
	 *
	 * <p>This is what keeps a library of loose files behaving exactly as it did.
	 * The group standing for a loose file is keyed by that file's name and
	 * belongs in that file's directory, so both values are the ones the file
	 * itself would have selected, and a filter a user wrote before any of this
	 * existed goes on selecting what it selected.</p>
	 *
	 * @param on    which value the filter matches against
	 * @param group the group to describe
	 * @return the value to match, or {@code null} if there is none
	 */
	public String filterValue(FileWaveDataProviderFilter.FilterOn on,
							  Audio.AudioLayerGroup group) {
		if (on == FileWaveDataProviderFilter.FilterOn.NAME) {
			return group.getKey();
		}

		List<String> segments = segmentsWithinRoot(locate(group));
		return segments == null ? null : String.join(File.separator, segments);
	}

	/**
	 * Returns whether the given filter selects the given group.
	 *
	 * @param filter the filter to apply
	 * @param group  the group to test
	 * @return whether the group is selected
	 */
	public boolean matches(FileWaveDataProviderFilter filter,
						   Audio.AudioLayerGroup group) {
		return filter.matches(filterValue(filter.getFilterOn(), group));
	}

	/**
	 * Returns the saved groups that belong in the given directory.
	 *
	 * <p>This is what a tree asks as it builds each directory: not "where does
	 * this group go" one group at a time, but "what belongs here". Placement
	 * stays in one place and the tree stays ignorant of how it is decided.</p>
	 *
	 * @param directory the directory being built
	 * @return the groups placed there, in store order
	 */
	public List<Audio.AudioLayerGroup> groupsIn(File directory) {
		List<Audio.AudioLayerGroup> placed = new ArrayList<>();
		if (directory == null) return placed;

		File target;

		try {
			target = directory.getCanonicalFile();
		} catch (IOException e) {
			warn("Unable to resolve " + directory, e);
			return placed;
		}

		for (Audio.AudioLayerGroup group : allGroups()) {
			File location = locate(group);

			try {
				if (target.equals(location.getCanonicalFile())) placed.add(group);
			} catch (IOException e) {
				warn("Unable to resolve " + location, e);
			}
		}

		return placed;
	}

	/**
	 * Returns the most specific directory containing all of the given files,
	 * bounded below by the library root.
	 *
	 * <p>Compared by path segment rather than by character, so
	 * {@code /a/foo.wav} and {@code /a/foobar.wav} are held to share
	 * {@code /a} and not {@code /a/foo}.</p>
	 *
	 * @param files the files to cover; may be empty
	 * @return the directory containing all of them, or the library root
	 */
	protected File commonDirectory(List<File> files) {
		List<String> common = null;

		for (File file : files) {
			List<String> segments = segmentsWithinRoot(file.getParentFile());
			if (segments == null) continue;

			if (common == null) {
				common = segments;
				continue;
			}

			int shared = 0;
			while (shared < common.size() && shared < segments.size()
					&& common.get(shared).equals(segments.get(shared))) {
				shared++;
			}

			common = common.subList(0, shared);
		}

		if (common == null) return libraryRoot;

		File directory = libraryRoot;
		for (String segment : common) {
			directory = new File(directory, segment);
		}

		return directory;
	}

	/**
	 * Returns the path segments leading from the library root to the given
	 * directory.
	 *
	 * @param directory the directory to describe
	 * @return the segments, empty for the root itself, or {@code null} when
	 *         the directory lies outside the library
	 */
	private List<String> segmentsWithinRoot(File directory) {
		if (directory == null) return null;

		Path root;
		Path target;

		try {
			root = libraryRoot.getCanonicalFile().toPath();
			target = directory.getCanonicalFile().toPath();
		} catch (IOException e) {
			warn("Unable to resolve " + directory + " against " + libraryRoot, e);
			return null;
		}

		if (!target.startsWith(root)) return null;

		List<String> segments = new ArrayList<>();
		root.relativize(target).forEach(segment -> segments.add(segment.toString()));

		// An empty relative path names the root itself, which Path reports as
		// a single empty segment rather than as no segments at all.
		if (segments.size() == 1 && segments.get(0).isEmpty()) return List.of();

		return segments;
	}

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
		return includeGroup(group, wavSource, null);
	}

	/**
	 * Saves a group as in {@link #includeGroup(Audio.AudioLayerGroup, Function)},
	 * with the caller choosing the file each member is written to.
	 *
	 * <p>A member's identity is its MD5, carried in the layer's
	 * {@code audio_ref} and resolved by searching the library for a matching
	 * content identifier — never by filename. Names are therefore free, and a
	 * caller that knows what the user called the capture should supply readable
	 * ones: a library full of {@code a3f2…c1.wav} cannot be browsed, which is
	 * half of what saving a group is for. Naming policy, including how clashes
	 * are resolved, stays with the caller that owns the library's layout; this
	 * method only copies to the file it is given.</p>
	 *
	 * <p>A member already present in the library is reused where it lies and is
	 * not copied again, so {@code targetSource} is consulted only for members
	 * being added.</p>
	 *
	 * @param group        the staged group to save
	 * @param wavSource    resolves the source WAV file for an audio layer
	 * @param targetSource resolves the file a newly added member is written to;
	 *                     {@code null} (or a {@code null} result) falls back to
	 *                     {@code <libraryRoot>/<md5>.wav}
	 * @return the stored group key on success, or {@link Optional#empty()} on
	 *         failure (after rollback)
	 */
	public Optional<String> includeGroup(Audio.AudioLayerGroup group,
										 Function<Audio.AudioLayer, File> wavSource,
										 Function<Audio.AudioLayer, File> targetSource) {
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

				String md5 = stripAndStoreLayer(layer, wavSource, targetSource,
						wavsWritten, idsAdded);
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
	 * @param targetSource per-layer destination resolver, or {@code null} to
	 *                     write to {@code <libraryRoot>/<md5>.wav}
	 * @param wavsWritten  accumulator for WAVs created by this call
	 * @param idsAdded     accumulator for store ids added by this call
	 * @return the member's MD5 identifier (its {@code audio_ref})
	 * @throws GroupSaveException if the source WAV is missing, a different file
	 *         already occupies the target name, the copy fails, or analysis fails
	 */
	private String stripAndStoreLayer(Audio.AudioLayer layer,
									  Function<Audio.AudioLayer, File> wavSource,
									  Function<Audio.AudioLayer, File> targetSource,
									  List<File> wavsWritten, List<String> idsAdded) {
		File source = wavSource == null ? null : wavSource.apply(layer);
		if (source == null || !source.isFile()) {
			throw new GroupSaveException("No source WAV for audio layer " + layer.getLayerId());
		}

		String md5 = identifierOf(source);
		if (md5 == null) {
			throw new GroupSaveException("Could not compute identifier for " + source);
		}

		boolean storeHadBefore = (library.getStore() != null)
				? library.getStore().containsKey(md5)
				: library.get(md5) != null;

		/* A member already in the library is referenced where it lies: the
		   content is identical by construction, and copying it again under a
		   second name would add a duplicate the user never asked for. */
		File target = library.fileFor(md5);

		if (target == null) {
			target = targetSource == null ? null : targetSource.apply(layer);
			if (target == null) target = new File(libraryRoot, md5 + ".wav");

			if (target.exists()) {
				String existing = identifierOf(target);
				if (!md5.equals(existing)) {
					throw new GroupSaveException("A different file already occupies "
							+ target.getName() + " (expected " + md5 + ", found " + existing + ")");
				}
			} else {
				copyWav(source, target);
				wavsWritten.add(target);
			}
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
