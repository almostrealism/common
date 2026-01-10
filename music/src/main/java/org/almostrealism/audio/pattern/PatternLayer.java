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

package org.almostrealism.audio.pattern;

import org.almostrealism.audio.notes.NoteAudioChoice;
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
	private NoteAudioChoice choice;
	private List<PatternElement> elements;
	private PatternLayer child;

	public PatternLayer() { this(null, new ArrayList<>()); }

	public PatternLayer(NoteAudioChoice choice, List<PatternElement> elements) {
		this.choice = choice;
		this.elements = elements;
	}

	public NoteAudioChoice getChoice() {
		return choice;
	}

	public void setChoice(NoteAudioChoice node) {
		this.choice = node;
	}

	public List<PatternElement> getElements() {
		return elements;
	}

	public void setElements(List<PatternElement> elements) {
		this.elements = elements;
	}

	public List<PatternElement> getElements(double start, double end) {
		return elements.stream()
				.filter(e -> e.getPosition() >= start && e.getPosition() < end)
				.collect(Collectors.toList());
	}

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

	public List<PatternElement> getAllElements(double start, double end) {
		List<PatternElement> result = new ArrayList<>();
		result.addAll(getElements(start, end));
		if (child != null)
			result.addAll(child.getAllElements(start, end));
		return result;
	}

	public void setAutomationParameters(PackedCollection parameters) {
		getElements().forEach(e -> e.setAutomationParameters(parameters));
	}

	public PatternLayer getChild() { return child; }

	public void setChild(PatternLayer child) { this.child = child; }

	public PatternLayer getTail() {
		if (child == null) return this;
		return child.getTail();
	}

	public PatternLayer getLastParent() {
		if (child == null) return null;
		if (child.getChild() == null) return this;
		return child.getLastParent();
	}

	public int depth() {
		if (child == null) return 1;
		return child.depth() + 1;
	}

	public void trim(double duration) {
		trim(0.0, duration);
	}

	public void trim(double start, double end) {
		Iterator<PatternElement> itr = elements.iterator();
		while (itr.hasNext()) {
			PatternElement e = itr.next();
			if (e.getPosition() < start || e.getPosition() >= end) itr.remove();
		}
	}
}
