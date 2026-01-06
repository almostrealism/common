/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.audio.notes;

import org.almostrealism.audio.data.ChannelInfo;
import org.almostrealism.audio.pattern.PatternElement;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class AudioChoiceNode implements NoteAudioNode {
	private NoteAudioChoice choice;
	private String name;

	private List<PatternElement> patternElements;
	private List<NoteAudioNode> children;

	public AudioChoiceNode() { }

	public AudioChoiceNode(NoteAudioChoice choice) {
		this.choice = choice;
	}

	public void setName(String name) {
		this.name = name;
	}

	@Override
	public String getName() {
		if (name != null) return name;
		if (choice == null) return null;
		return choice.getName();
	}

	public void setPatternElements(Map<NoteAudioChoice, List<PatternElement>> elements) {
		if (choice != null) {
			setPatternElements(elements.get(choice));
		}
	}

	public void setPatternElements(List<PatternElement> patternElements) {
		this.patternElements = patternElements;
		children = null;
		initChildren();
	}

	protected void initChildren() {
		if (children != null) return;

		if (patternElements == null) {
			children = new ArrayList<>();
			return;
		}

		children = patternElements
				.stream()
				.map(e -> e.getNote(ChannelInfo.Voicing.MAIN))
				.distinct()
				.map(note -> new PatternNoteNode(choice, note))
				.collect(Collectors.toList());
	}

	public void setChildren(List<NoteAudioNode> children) {
		this.children = children;
	}

	@Override
	public Collection<NoteAudioNode> getChildren() {
		return children;
	}
}
