/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.music.notes;
import org.almostrealism.audio.notes.NoteAudioProvider;
import org.almostrealism.audio.notes.NoteAudio;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.almostrealism.uml.Named;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.data.FileWaveDataProvider;
import org.almostrealism.audio.data.FileWaveDataProviderFilter;
import org.almostrealism.audio.data.FileWaveDataProviderNode;
import org.almostrealism.audio.data.FileWaveDataProviderTree;
import org.almostrealism.audio.tone.KeyPosition;
import org.almostrealism.audio.tone.KeyboardTuning;
import org.almostrealism.audio.tone.WesternChromatic;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * A {@link NoteAudioSource} backed by a {@link FileWaveDataProviderTree}.
 *
 * <p>Loads audio providers from a tree of wave files, applying optional
 * {@link FileWaveDataProviderFilter}s to restrict which files are included.
 * Supports BPM-based splitting, keyboard tuning, and forward/reverse playback.</p>
 *
 * @see NoteAudioSource
 * @see NoteAudioSourceBase
 */
public class TreeNoteSource extends NoteAudioSourceBase implements Named, ConsoleFeatures {

	/** The source file tree from which providers are built. */
	private FileWaveDataProviderTree<? extends Supplier<FileWaveDataProvider>> tree;

	/** The built list of providers from the tree. */
	private List<Provider> providers;

	/** The final list of notes, potentially split from providers. */
	private List<Provider> notes;

	/** The tree signature from the last successful provider computation. */
	private String latestSignature;

	/** The keyboard tuning applied to all notes in this source. */
	private KeyboardTuning tuning;

	/** The root key position used when building note providers. */
	private KeyPosition<?> root;

	/** Optional BPM used for time-based splitting. */
	private Double bpm;

	/** Optional split duration in beats; if set, notes are split at this boundary. */
	private Double splitDurationBeats;

	/** Whether to use a synthesizer for note rendering. */
	private boolean useSynthesizer;

	/** Whether forward playback is enabled. */
	private boolean forwardPlayback;

	/** Whether reverse playback is enabled. */
	private boolean reversePlayback;

	/** The list of filters to apply when selecting files from the tree. */
	private final List<FileWaveDataProviderFilter> filters;

	/** Creates a {@code TreeNoteSource} with no tree. */
	public TreeNoteSource() { this((FileWaveDataProviderTree) null); }

	/**
	 * Creates a {@code TreeNoteSource} with the given root key position.
	 *
	 * @param root the root key position
	 */
	public TreeNoteSource(KeyPosition<?> root) {
		this(null, root);
	}

	/**
	 * Creates a {@code TreeNoteSource} with the given tree and the default root key {@code C1}.
	 *
	 * @param tree the file wave data provider tree
	 */
	public TreeNoteSource(FileWaveDataProviderTree<? extends Supplier<FileWaveDataProvider>> tree) {
		this(tree, WesternChromatic.C1);
	}

	/**
	 * Creates a {@code TreeNoteSource} with the given tree and root key position.
	 *
	 * @param tree the file wave data provider tree
	 * @param root the root key position
	 */
	public TreeNoteSource(FileWaveDataProviderTree<? extends Supplier<FileWaveDataProvider>> tree,
						  KeyPosition<?> root) {
		this.filters = new ArrayList<>();
		setForwardPlayback(true);
		setTree(tree);
		setRoot(root);
	}

	/** Returns the root key position used when building note providers. */
	public KeyPosition<?> getRoot() { return root; }

	/** Sets the root key position. */
	public void setRoot(KeyPosition<?> root) { this.root = root; }

	/** Returns the BPM value used for time-based splitting, or {@code null} if not set. */
	public Double getBpm() { return bpm; }

	/** Sets the BPM value and recomputes notes if the value changed. */
	public void setBpm(Double bpm) {
		if (!Objects.equals(this.bpm, bpm)) {
			this.bpm = bpm;

			if (providers != null) {
				providers.forEach(n -> n.getProvider().setBpm(bpm));
			}

			computeNotes();
		}
	}

	/** Returns the split duration in beats, or {@code null} if not set. */
	public Double getSplitDurationBeats() { return splitDurationBeats; }

	/** Sets the split duration in beats and recomputes notes if the value changed. */
	public void setSplitDurationBeats(Double splitDurationBeats) {
		if (!Objects.equals(this.splitDurationBeats, splitDurationBeats)) {
			this.splitDurationBeats = splitDurationBeats;
			computeNotes();
		}
	}

	/** Returns the underlying file wave data provider tree. */
	@JsonIgnore
	public FileWaveDataProviderTree<?> getTree() { return tree; }

	/** Sets the underlying tree and triggers provider recomputation. */
	@JsonIgnore
	public void setTree(FileWaveDataProviderTree tree) {
		this.tree = tree;
		if (!isUpdated()) computeProviders();
	}

	/**
	 * Returns {@code true} if the tree has not changed since providers were last computed.
	 *
	 * @return {@code true} if up to date
	 */
	public boolean isUpdated() {
		if (tree == null)
			return true;

		return latestSignature != null && latestSignature.equals(tree.signature());
	}

	/** Returns the list of filters applied when selecting files from the tree. */
	public List<FileWaveDataProviderFilter> getFilters() { return filters; }

	@Override
	public KeyboardTuning getTuning() {
		return tuning;
	}

	@Override
	public void setTuning(KeyboardTuning tuning) {
		this.tuning = tuning;

		if (notes != null) {
			for (Provider note : notes) {
				note.getProvider().setTuning(tuning);
			}
		}
	}

	/** Returns the origin name of the tree, optionally decorated with filter information. */
	@JsonIgnore
	@Override
	public String getName() {
		if (filters.isEmpty()) return getOrigin();
		if (filters.size() > 1) return getOrigin() + " (" + filters.size() + " filters)";
		return filters.get(0).getFilterOn().readableName() + " " +
				filters.get(0).getFilterType().readableName() + " \"" +
				filters.get(0).getFilter() + "\"";
	}

	/** Returns the raw name of the underlying tree, or an empty string if unavailable. */
	@Override
	public String getOrigin() { return tree instanceof Named ? ((Named) tree).getName() : ""; }

	/** Returns the list of active notes, recomputing providers if the tree has changed. */
	@Override
	@JsonIgnore
	public List<NoteAudio> getNotes() {
		if (!isUpdated()) computeProviders();
		if (notes == null) {
			return Collections.emptyList();
		}

		return notes.stream()
				.filter(Provider::isActive)
				.map(Provider::getProvider)
				.collect(Collectors.toList());
	}

	@Override
	public boolean isUseSynthesizer() { return useSynthesizer; }

	/** Sets whether a synthesizer is used for note rendering. */
	public void setUseSynthesizer(boolean useSynthesizer) {
		this.useSynthesizer = useSynthesizer;
	}

	@Override
	public boolean isForwardPlayback() {
		return forwardPlayback;
	}

	/** Sets whether forward playback is enabled. */
	public void setForwardPlayback(boolean forwardPlayback) {
		this.forwardPlayback = forwardPlayback;
	}

	@Override
	public boolean isReversePlayback() {
		return reversePlayback;
	}

	/** Sets whether reverse playback is enabled. */
	public void setReversePlayback(boolean reversePlayback) {
		this.reversePlayback = reversePlayback;
	}

	/** Recomputes the providers from the current tree. */
	public void refresh() {
		computeProviders();
	}

	/** Recomputes the provider list from the current tree using the active filters. */
	private void computeProviders() {
		if (filters == null) {
			providers = null;
			return;
		}

		providers = new ArrayList<>();

		if (tree != null) {
			tree.children().forEach(f -> {
				FileWaveDataProvider p = f.get();

				try {
					if (p == null) {
						return;
					}

					boolean match = filters.stream()
							.map(filter -> filter.matches(tree, p))
							.reduce((a, b) -> a & b)
							.orElse(true);

					if (match) {
						providers.add(new Provider(new NoteAudioProvider(p, getRoot(), getBpm()),
													((FileWaveDataProviderTree) f).active()));
					}
				} catch (Exception e) {
					warn(e.getMessage() + "(" + p.getResourcePath() + ")");
				}
			});

			Collections.sort(providers);

			latestSignature = tree.signature();
		}

		computeNotes();
	}

	/** Recomputes the notes list from the current providers, applying split/tuning if configured. */
	private void computeNotes() {
		if (providers == null) {
			notes = null;
			return;
		}

		if (getSplitDurationBeats() == null || getBpm() == null) {
			providers.forEach(n -> n.getProvider().setTuning(tuning));
			notes = providers;
		} else {
			notes = providers.stream().flatMap(n -> {
				n.getProvider().setTuning(tuning);
				return n.getProvider().split(getSplitDurationBeats()).stream()
						.map(m -> new Provider(m, n.getActive()));
			}).collect(Collectors.toList());
		}
	}

	/**
	 * Returns {@code true} if any provider in this source references the given file path.
	 *
	 * @param canonicalPath the canonical file path to check
	 * @return {@code true} if the path is used
	 */
	@Override
	public boolean checkResourceUsed(String canonicalPath) {
		if (providers == null) computeProviders();

		boolean match = providers.stream().map(Provider::getProvider).anyMatch(note -> {
			if (note.getProvider() instanceof FileWaveDataProvider) {
				return ((FileWaveDataProvider) note.getProvider())
						.getResourcePath().equals(canonicalPath);
			} else {
				return false;
			}
		});

		return match;
	}

	@Override
	public Console console() {
		return CellFeatures.console;
	}

	/**
	 * Creates a {@code TreeNoteSource} from a root file directory with the given filter.
	 *
	 * @param root   the root directory
	 * @param filter the filter to apply
	 * @return a new {@code TreeNoteSource}
	 */
	public static TreeNoteSource fromFile(File root, FileWaveDataProviderFilter filter) {
		TreeNoteSource t = new TreeNoteSource(new FileWaveDataProviderNode(root));
		t.getFilters().add(filter);
		return t;
	}

	/**
	 * Wraps a {@link NoteAudioProvider} with an active flag for filtering.
	 */
	protected static class Provider implements Comparable<Provider> {
		/** The underlying note audio provider. */
		private final NoteAudioProvider provider;

		/** Supplier that indicates whether this provider is currently active. */
		private final BooleanSupplier active;

		/**
		 * Creates a {@code Provider} with the given note audio provider and active supplier.
		 *
		 * @param provider the note audio provider
		 * @param active   supplier indicating whether this provider is active
		 */
		public Provider(NoteAudioProvider provider, BooleanSupplier active) {
			this.provider = provider;
			this.active = active;
		}

		/** Returns the underlying note audio provider. */
		public NoteAudioProvider getProvider() { return provider; }

		/** Returns {@code true} if this provider is currently active. */
		public boolean isActive() { return active.getAsBoolean(); }

		/** Returns the active supplier for this provider. */
		public BooleanSupplier getActive() { return active; }

		@Override
		public int compareTo(Provider provider) {
			if (this.provider == null) {
				return 1;
			} else if (provider.provider == null) {
				return -1;
			} else {
				return this.provider.compareTo(provider.provider);
			}
		}
	}
}
