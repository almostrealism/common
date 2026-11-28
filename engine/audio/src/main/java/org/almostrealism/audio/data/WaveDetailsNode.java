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

package org.almostrealism.audio.data;

import io.almostrealism.relation.Tree;
import org.almostrealism.audio.AudioLibrary;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A tree node representing an audio sample ({@link WaveDetails}) with navigable
 * connections to similar audio samples from an {@link AudioLibrary}.
 * <p>
 * Each node provides access to its most similar samples as children, creating
 * a navigable similarity tree. This enables exploration of audio libraries by
 * similarity, useful for sample selection, recommendation, and interpolation.
 * <p>
 * To avoid cycles and repetition in the tree, each node excludes certain samples
 * when computing its children. Specifically, each child excludes everything its
 * parent excludes, its parent and all of its siblings, ensuring that navigating
 * deeper in the tree reveals new samples rather than cycling through the same set.
 * This means two nodes representing the same audio sample may have different
 * children depending on the path taken to reach them.
 * <p>
 * Within one node's children each file appears once, and a sound saved under
 * several paths (which gives it several content identifiers, and so several
 * records) is listed once, under the first of its paths to rank.
 * <p>
 * Similarity scores are computed by
 * {@link AudioLibrary#getNearestSimilarities(WaveDetails, int, Set)}, which scores
 * the nearest items rather than the whole library, so that a node's children
 * can be computed interactively for a library larger than memory. Children are
 * resolved to files through the library's identifier index.
 * <p>
 * Example usage:
 * <pre>
 * WaveDetailsNode root = new WaveDetailsNode(library, "kick_drum.wav");
 * root.prepareChildren();
 * // Navigate to most similar sample
 * WaveDetailsNode similar = root.getChildren().iterator().next();
 * </pre>
 *
 * @see AudioLibrary
 * @see WaveDetails
 * @see Tree
 */
public class WaveDetailsNode implements Tree<WaveDetailsNode> {
	/**
	 * Comparator for sorting similarity scores in descending order.
	 * Higher similarity values appear first.
	 */
	private static final Comparator<Map.Entry<String, Double>> comparator =
			Map.Entry.<String, Double>comparingByValue().reversed();

	/** Fewest nearest items scored when computing a node's children. */
	private static final int MIN_CANDIDATES = 100;

	/** Nearest items scored per child the node may list. */
	private static final int CANDIDATES_PER_CHILD = 5;

	/**
	 * Largest difference between two similarity scores for records of the
	 * same name to be taken as the same sound.
	 */
	private static final double SAME_SOUND_TOLERANCE = 1e-5;

	/**
	 * The audio library containing all available samples and similarity data.
	 */
	private final AudioLibrary library;

	/**
	 * The audio sample metadata represented by this node.
	 */
	private final WaveDetails details;

	/**
	 * Maximum number of children (similar samples) to create for this node.
	 */
	private final int childLimit;

	/**
	 * Set of sample identifiers to exclude when computing children.
	 * This prevents cycles and ensures navigation reveals new samples.
	 * Typically contains the ancestors and their siblings.
	 */
	private final Set<String> excludedIdentifiers;

	/**
	 * Cached file path to the audio resource. Lazy-loaded on first access.
	 */
	private final String resourcePath;

	/**
	 * List of child nodes representing similar audio samples. Null until
	 * {@link #prepareChildren()} or {@link #getChildren()} is called.
	 */
	private List<WaveDetailsNode> children;

	/**
	 * Creates a node for the audio file at the given path.
	 * <p>
	 * The {@link WaveDetails} are retrieved from the library synchronously.
	 * No samples are excluded from children, and other copies of this file's
	 * sound are not listed among them.
	 *
	 * @param library the audio library containing the sample
	 * @param key     the path of the audio file
	 */
	public WaveDetailsNode(AudioLibrary library, String key) {
		this(library, library.getDetailsForFileAwait(key, false), key, 20, Collections.emptySet());
	}

	/**
	 * Creates a node for the given audio sample details with default settings.
	 * <p>
	 * Uses a child limit of 20 and no exclusions.
	 *
	 * @param library the audio library containing similar samples
	 * @param details the audio sample metadata for this node
	 */
	public WaveDetailsNode(AudioLibrary library, WaveDetails details) {
		this(library, details, 20);
	}

	/**
	 * Creates a node for the given audio sample details with a custom child limit.
	 * <p>
	 * No samples are excluded from children.
	 *
	 * @param library    the audio library containing similar samples
	 * @param details    the audio sample metadata for this node
	 * @param childLimit maximum number of similar samples to include as children
	 */
	public WaveDetailsNode(AudioLibrary library, WaveDetails details,
						   int childLimit) {
		this(library, details, null, childLimit, Collections.emptySet());
	}

	/**
	 * Creates a node with specific samples excluded from children.
	 * <p>
	 * This is the primary constructor used internally when building the tree.
	 * The exclusion set prevents cycles by excluding parent and sibling samples.
	 *
	 * @param library             the audio library containing similar samples
	 * @param details             the audio sample metadata for this node
	 * @param resourcePath        the path to the resource, if it exists
	 * @param childLimit          maximum number of similar samples to include as children
	 * @param excludedIdentifiers set of sample identifiers to exclude from children
	 */
	public WaveDetailsNode(AudioLibrary library, WaveDetails details,
						   String resourcePath, int childLimit,
						   Set<String> excludedIdentifiers) {
		this.library = library;
		this.details = details;
		this.resourcePath = resourcePath;
		this.childLimit = childLimit;
		this.excludedIdentifiers = excludedIdentifiers;
	}

	/**
	 * Creates a WaveDetailsNode derived from an existing node with a different set of excluded identifiers.
	 *
	 * @param existing            the node to copy configuration from
	 * @param excludedIdentifiers identifiers to exclude from this node's details and children
	 */
	protected WaveDetailsNode(WaveDetailsNode existing, Set<String> excludedIdentifiers) {
		this.library = existing.library;
		this.childLimit = existing.childLimit;
		this.details = existing.getDetails();
		this.resourcePath = existing.getResourcePath();
		this.excludedIdentifiers = excludedIdentifiers;
	}

	public WaveDetails getDetails() { return details; }

	/**
	 * Returns the file system path to the audio resource represented by this node.
	 * <p>
	 * The path is lazy-loaded and cached on first access. This assumes the
	 * underlying data provider is a {@link FileWaveDataProvider}.
	 *
	 * @return the absolute or relative path to the audio file
	 */
	public String getResourcePath() { return resourcePath; }

	/**
	 * Ensures that child nodes are populated.
	 * <p>
	 * If children have not been loaded yet, this triggers {@link #refreshChildren()}.
	 * This is useful for preloading the tree structure before traversal.
	 * <p>
	 * Subsequent calls have no effect - children are not refreshed unless
	 * {@link #refreshChildren()} is called directly.
	 */
	public void prepareChildren() {
		if (children == null)
			refreshChildren();
	}

	/**
	 * Refreshes the list of child nodes by querying the library for similar samples.
	 * <p>
	 * This method:
	 * <ol>
	 *   <li>Retrieves similarity scores for the nearest items from
	 *       {@link AudioLibrary#getNearestSimilarities(WaveDetails, int, Set)}</li>
	 *   <li>Filters out any samples in the exclusion set</li>
	 *   <li>Sorts samples by similarity (highest first)</li>
	 *   <li>Skips samples with no file in the library, files already listed, and
	 *       other copies of a sound already listed (or of this node's own file)</li>
	 *   <li>Selects the top {@link #childLimit} matches</li>
	 *   <li>Creates child nodes whose exclusions are this node's exclusions,
	 *       this node and all siblings</li>
	 * </ol>
	 * <p>
	 * This method can be called multiple times to update the children based on
	 * changes to the library's similarity data.
	 */
	protected void refreshChildren() {
		Set<String> excluded = excludedIdentifiers == null ? Collections.emptySet() : excludedIdentifiers;
		Map<String, Double> similarities = library.getNearestSimilarities(details,
				Math.max(MIN_CANDIDATES, CANDIDATES_PER_CHILD * childLimit), excluded);

		PathResolver paths = new PathResolver();
		Set<String> listedPaths = new HashSet<>();
		List<ListedSound> listedSounds = new ArrayList<>();
		if (resourcePath != null) listedSounds.add(ListedSound.of(resourcePath, 1.0));

		List<WaveDetailsNode> nodes = new ArrayList<>();
		for (Map.Entry<String, Double> entry : similarities.entrySet().stream().sorted(comparator).toList()) {
			if (nodes.size() >= childLimit) break;
			if (excluded.contains(entry.getKey())) continue;

			String path = paths.pathFor(entry.getKey());
			if (path == null || !listedPaths.add(path)) continue;

			// The same sound saved under another path has its own identifier, and
			// so its own record, but it is the same file as far as the user can tell
			ListedSound sound = ListedSound.of(path, entry.getValue());
			if (listedSounds.stream().anyMatch(sound::sameAs)) continue;

			WaveDetails d = library.get(entry.getKey());
			if (d == null) continue;

			listedSounds.add(sound);
			nodes.add(new WaveDetailsNode(library, d, path, childLimit, null));
		}

		// Build exclusion set for children: everything this node excludes (its
		// ancestors and their siblings), this node, and all of its children, so
		// that a deeper column never repeats a sound already shown to its left
		Set<String> childExclusions = new HashSet<>(excluded);
		childExclusions.add(details.getIdentifier());
		nodes.stream()
				.map(WaveDetailsNode::getDetails)
				.map(WaveDetails::getIdentifier)
				.forEach(childExclusions::add);
		this.children = nodes.stream()
				.map(node -> new WaveDetailsNode(node, childExclusions))
				.collect(Collectors.toList());
	}

	/**
	 * Resolves content identifiers to file paths through the library's
	 * identifier index, so that resolving a column of children does not walk
	 * the library tree once per child.
	 *
	 * <p>An identifier the index cannot resolve (the index is absent, or was
	 * built before the file appeared) triggers one re-index of the tree per
	 * resolver, after which misses are final: the item has no file in the
	 * library, and is skipped.</p>
	 */
	private final class PathResolver {
		/** Whether this resolver has already re-indexed the tree. */
		private boolean reindexed;

		/**
		 * Returns the path of the file holding the given content.
		 *
		 * @param identifier the content identifier
		 * @return the file path, or {@code null} if no file in the library holds it
		 */
		String pathFor(String identifier) {
			File file = library.indexedFileFor(identifier);

			if (file == null && !reindexed) {
				reindexed = true;
				library.indexFiles();
				file = library.indexedFileFor(identifier);
			}

			return file == null ? null : file.getPath();
		}
	}

	/**
	 * A sound already listed in a column, identified as the user sees it: by
	 * its file name, together with its similarity to the column's query. Two
	 * records with the same name and the same similarity hold the same audio
	 * (identical features give identical scores), however their bytes differ.
	 *
	 * @param name       the file name
	 * @param similarity the similarity to the column's query
	 */
	private record ListedSound(String name, double similarity) {
		/**
		 * Creates the entry for the file at the given path.
		 *
		 * @param path       the file path
		 * @param similarity the similarity to the column's query
		 * @return the entry
		 */
		static ListedSound of(String path, double similarity) {
			return new ListedSound(new File(path).getName().toLowerCase(Locale.ROOT), similarity);
		}

		/**
		 * Returns whether this and the given entry are the same sound.
		 *
		 * @param other the other entry
		 * @return true if both have the same name and the same similarity
		 */
		boolean sameAs(ListedSound other) {
			return name.equals(other.name) && Math.abs(similarity - other.similarity) < SAME_SOUND_TOLERANCE;
		}
	}

	/**
	 * Returns the child nodes representing similar audio samples.
	 * <p>
	 * Children are lazy-loaded on first access. If {@link #prepareChildren()} or
	 * {@link #refreshChildren()} has not been called, returns an empty collection.
	 * <p>
	 * The returned children are ordered by similarity, with the most similar
	 * samples appearing first.
	 *
	 * @return an immutable collection of child nodes, or empty if not yet loaded
	 */
	@Override
	public Collection<WaveDetailsNode> getChildren() {
		return children == null ? Collections.emptyList() : children;
	}
}
