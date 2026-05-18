/*
 * Copyright 2022 Michael Murray
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

package org.almostrealism.music.pattern;

import org.almostrealism.music.notes.NoteAudioChoice;
import org.almostrealism.collect.PackedCollection;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A hierarchical layer within a pattern that contains musical elements.
 *
 * <p>{@code PatternLayer} forms a linked list structure where each layer contains
 * a collection of {@link PatternElement}s and optionally a child layer. This hierarchy
 * enables patterns with multiple levels of detail, where each child layer typically
 * operates at half the time granularity of its parent.</p>
 *
 * <h2>Layer Hierarchy</h2>
 *
 * <pre>
 * PatternLayer (root, scale = 1.0)
 *     |
 *     +-- elements[] (musical events at full measure granularity)
 *     |
 *     +-- PatternLayer (child, scale = 0.5)
 *         |
 *         +-- elements[] (events at half-measure granularity)
 *         |
 *         +-- PatternLayer (child, scale = 0.25)
 *             ...
 * </pre>
 *
 * <h2>Element Collection</h2>
 *
 * <p>Elements can be retrieved for a specific time range using {@link #getElements(double, double)}.
 * To collect elements from the entire hierarchy, use {@link #getAllElements(double, double)} or
 * {@link #putAllElementsByChoice(Map, double, double)} which groups elements by their
 * associated {@link NoteAudioChoice}.</p>
 *
 * <h2>Real-Time Considerations</h2>
 *
 * <p>For real-time rendering, elements need to be filtered by frame range rather than
 * the full duration. The current implementation collects all elements at once, which
 * is incompatible with incremental rendering. A frame-range-aware collection method
 * would be needed for real-time support.</p>
 *
 * @see PatternElement
 * @see PatternLayerManager
 * @see NoteAudioChoice
 *
 * @author Michael Murray
 */
public class PatternLayer {
	/** The audio choice associated with this layer. */
	private NoteAudioChoice choice;

	/** The list of pattern elements in this layer. */
	private List<PatternElement> elements;

	/** The optional child layer chained to this one. */
	private PatternLayer child;

	/** Creates an empty {@code PatternLayer} with no choice and an empty element list. */
	public PatternLayer() { this(null, new ArrayList<>()); }

	/**
	 * Creates a {@code PatternLayer} with the given choice and elements.
	 *
	 * @param choice   the audio choice for this layer
	 * @param elements the list of pattern elements
	 */
	public PatternLayer(NoteAudioChoice choice, List<PatternElement> elements) {
		this.choice = choice;
		this.elements = elements;
	}

	/** Returns the audio choice for this layer. */
	public NoteAudioChoice getChoice() {
		return choice;
	}

	/** Sets the audio choice for this layer. */
	public void setChoice(NoteAudioChoice node) {
		this.choice = node;
	}

	/** Returns the full list of pattern elements. */
	public List<PatternElement> getElements() {
		return elements;
	}

	/** Sets the list of pattern elements. */
	public void setElements(List<PatternElement> elements) {
		this.elements = elements;
	}

	/**
	 * Returns elements whose position falls within {@code [start, end)}.
	 *
	 * @param start the inclusive start position
	 * @param end   the exclusive end position
	 * @return the filtered list of elements
	 */
	public List<PatternElement> getElements(double start, double end) {
		return elements.stream()
				.filter(e -> e.getPosition() >= start && e.getPosition() < end)
				.collect(Collectors.toList());
	}

	/**
	 * Adds all elements in {@code [start, end)} to the given map, keyed by choice.
	 *
	 * @param result the map to populate
	 * @param start  the inclusive start position
	 * @param end    the exclusive end position
	 */
	public void putAllElementsByChoice(Map<NoteAudioChoice, List<PatternElement>> result,
									   double start, double end) {
		if (elements == null || elements.isEmpty()) return;

		if (choice == null) {
			throw new UnsupportedOperationException();
		}

		result.computeIfAbsent(getChoice(), c -> new ArrayList<>()).addAll(getElements(start, end));
		if (child != null)
			child.putAllElementsByChoice(result, start, end);
	}

	/**
	 * Returns all elements from this layer and all child layers within {@code [start, end)}.
	 *
	 * @param start the inclusive start position
	 * @param end   the exclusive end position
	 * @return a combined list of elements from this layer and all descendants
	 */
	public List<PatternElement> getAllElements(double start, double end) {
		List<PatternElement> result = new ArrayList<>();
		result.addAll(getElements(start, end));
		if (child != null)
			result.addAll(child.getAllElements(start, end));
		return result;
	}

	/**
	 * Sets the automation parameters on all elements in this layer.
	 *
	 * @param parameters the automation parameter collection
	 */
	public void setAutomationParameters(PackedCollection parameters) {
		getElements().forEach(e -> e.setAutomationParameters(parameters));
	}

	/** Returns the child layer, or {@code null} if none. */
	public PatternLayer getChild() { return child; }

	/** Sets the child layer. */
	public void setChild(PatternLayer child) { this.child = child; }

	/**
	 * Returns the tail of the chain (the last layer with no child).
	 *
	 * @return the tail layer
	 */
	public PatternLayer getTail() {
		if (child == null) return this;
		return child.getTail();
	}

	/**
	 * Returns the last parent in the chain (the layer whose child is the tail).
	 *
	 * @return the last parent, or {@code null} if there is no child
	 */
	public PatternLayer getLastParent() {
		if (child == null) return null;
		if (child.getChild() == null) return this;
		return child.getLastParent();
	}

	/**
	 * Returns the total depth of this chain (number of layers including this one).
	 *
	 * @return the chain depth
	 */
	public int depth() {
		if (child == null) return 1;
		return child.depth() + 1;
	}

	/**
	 * Removes elements outside {@code [0, duration)}.
	 *
	 * @param duration the maximum allowed position (exclusive)
	 */
	public void trim(double duration) {
		trim(0.0, duration);
	}

	/**
	 * Removes elements whose position falls outside {@code [start, end)}.
	 *
	 * @param start the inclusive start position
	 * @param end   the exclusive end position
	 */
	public void trim(double start, double end) {
		Iterator<PatternElement> itr = elements.iterator();
		while (itr.hasNext()) {
			PatternElement e = itr.next();
			if (e.getPosition() < start || e.getPosition() >= end) itr.remove();
		}
	}
}
