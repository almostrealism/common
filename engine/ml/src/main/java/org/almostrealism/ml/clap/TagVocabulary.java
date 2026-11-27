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

package org.almostrealism.ml.clap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A versioned vocabulary of tags for zero-shot audio tagging: an ordered list of tags, each a label
 * the caller identifies it by and one or more short caption sentences that describe it.
 *
 * <p>A zero-shot audio-text model scores a clip against a tag by comparing the clip's embedding with
 * the tag's text embedding, and a tag described by several captions is embedded as the normalized
 * mean of its captions' normalized embeddings (the convention of the reference zero-shot
 * classifier), which steadies the score against the wording of any one caption. The model card
 * of CLAP2 advises captions that are full sentences in the style of its training captions, such as
 * {@code "A deep kick drum hits."}, over bare labels, and no negations.</p>
 *
 * <p>Tag embeddings depend only on the vocabulary, so a model computes them once per vocabulary and
 * reuses them for every clip ({@link Clap2#embed(TagVocabulary)}). Two vocabularies are equal when
 * their versions and tags are, so the version is how a caller marks a vocabulary whose captions
 * changed as a new one; the scores of a vocabulary are in the order of its {@link #getTags() tags}.</p>
 */
public final class TagVocabulary {

	/** One tag: the label a caller identifies it by and the captions that describe it. */
	public static final class Tag {

		/** The label. */
		private final String label;

		/** The captions, at least one. */
		private final List<String> captions;

		/**
		 * Creates a tag.
		 *
		 * @param label    the label the caller identifies the tag by
		 * @param captions the captions that describe it, at least one, none empty
		 * @throws IllegalArgumentException if the label or a caption is empty, or there is no caption
		 */
		public Tag(String label, List<String> captions) {
			if (label == null || label.isEmpty()) {
				throw new IllegalArgumentException("A tag needs a non-empty label");
			}
			if (captions == null || captions.isEmpty()) {
				throw new IllegalArgumentException("Tag \"" + label + "\" needs at least one caption");
			}
			for (String caption : captions) {
				if (caption == null || caption.isEmpty()) {
					throw new IllegalArgumentException("Tag \"" + label + "\" has an empty caption");
				}
			}

			this.label = label;
			this.captions = Collections.unmodifiableList(new ArrayList<>(captions));
		}

		/**
		 * Creates a tag.
		 *
		 * @param label    the label the caller identifies the tag by
		 * @param captions the captions that describe it, at least one, none empty
		 * @throws IllegalArgumentException if the label or a caption is empty, or there is no caption
		 */
		public Tag(String label, String... captions) {
			this(label, List.of(captions));
		}

		/** The label the caller identifies the tag by. */
		public String getLabel() { return label; }

		/** The captions that describe the tag, in order. */
		public List<String> getCaptions() { return captions; }

		@Override
		public boolean equals(Object o) {
			if (!(o instanceof Tag)) return false;
			Tag other = (Tag) o;
			return label.equals(other.label) && captions.equals(other.captions);
		}

		@Override
		public int hashCode() { return Objects.hash(label, captions); }

		@Override
		public String toString() { return label + "=" + captions; }
	}

	/** The version, which distinguishes vocabularies whose tags are otherwise equal. */
	private final String version;

	/** The tags, in score order. */
	private final List<Tag> tags;

	/**
	 * Creates a vocabulary.
	 *
	 * @param version the version, which distinguishes this vocabulary from an earlier one
	 * @param tags    the tags, at least one, with distinct labels
	 * @throws IllegalArgumentException if there is no tag or two tags share a label
	 */
	public TagVocabulary(String version, List<Tag> tags) {
		if (tags == null || tags.isEmpty()) {
			throw new IllegalArgumentException("A tag vocabulary needs at least one tag");
		}

		Set<String> labels = new HashSet<>();
		for (Tag tag : tags) {
			if (!labels.add(tag.getLabel())) {
				throw new IllegalArgumentException("Tag \"" + tag.getLabel() + "\" appears twice");
			}
		}

		this.version = Objects.requireNonNull(version, "version");
		this.tags = Collections.unmodifiableList(new ArrayList<>(tags));
	}

	/**
	 * A vocabulary of tags each described by a single caption, its label, with an empty version.
	 *
	 * @param labels the tags, which are also their captions
	 * @return the vocabulary
	 * @throws IllegalArgumentException if there is no tag, a tag is empty, or two are equal
	 */
	public static TagVocabulary of(List<String> labels) {
		List<Tag> tags = new ArrayList<>();
		for (String label : labels) {
			tags.add(new Tag(label, label));
		}
		return new TagVocabulary("", tags);
	}

	/** The version of this vocabulary. */
	public String getVersion() { return version; }

	/** The tags, in the order of their scores. */
	public List<Tag> getTags() { return tags; }

	/** The number of tags. */
	public int size() { return tags.size(); }

	/**
	 * The position of the tag with the given label in the order of the scores.
	 *
	 * @param label the label
	 * @return the position, or {@code -1} if no tag has the label
	 */
	public int indexOf(String label) {
		for (int i = 0; i < tags.size(); i++) {
			if (tags.get(i).getLabel().equals(label)) return i;
		}
		return -1;
	}

	@Override
	public boolean equals(Object o) {
		if (!(o instanceof TagVocabulary)) return false;
		TagVocabulary other = (TagVocabulary) o;
		return version.equals(other.version) && tags.equals(other.tags);
	}

	@Override
	public int hashCode() { return Objects.hash(version, tags); }
}
