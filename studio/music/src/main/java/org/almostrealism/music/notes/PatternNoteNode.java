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

package org.almostrealism.music.notes;
import org.almostrealism.audio.notes.NoteAudioProvider;

import org.almostrealism.music.data.ChannelInfo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * A {@link NoteAudioNode} that represents a {@link PatternNote} bound to a {@link NoteAudioChoice}.
 *
 * <p>The node's children are derived by resolving the audio choices within the note
 * to their underlying {@link AudioProviderNode} leaf nodes.</p>
 *
 * @see NoteAudioNode
 * @see PatternNote
 * @see NoteAudioChoice
 */
public class PatternNoteNode implements NoteAudioNode {
	/** The audio choice associated with this node. */
	private NoteAudioChoice choice;

	/** The pattern note this node represents. */
	private PatternNote note;

	/** Optional override for the node name. */
	private String name;

	/** The computed list of child audio nodes. */
	private List<NoteAudioNode> children;

	/** Creates an uninitialized {@code PatternNoteNode}. */
	public PatternNoteNode() { }

	/**
	 * Creates a {@code PatternNoteNode} for the given choice and note.
	 *
	 * @param choice the audio choice
	 * @param note   the pattern note
	 */
	public PatternNoteNode(NoteAudioChoice choice, PatternNote note) {
		this.choice = choice;
		this.note = note;
		initChildren();
	}

	/** Sets the name for this node. */
	public void setName(String name) {
		this.name = name;
	}

	/** Returns the name, defaulting to the choice name with " Note" appended. */
	@Override
	public String getName() {
		if (name != null) return name;
		if (choice == null) return null;
		return choice.getName() + " Note";
	}

	/** Initializes the list of child audio nodes from the note's audio choices. */
	protected void initChildren() {
		if (children != null) return;

		if (note == null) {
			children = new ArrayList<>();
			return;
		}

		NoteAudioContext audioContext = new NoteAudioContext(ChannelInfo.Voicing.MAIN,
				null, choice.getValidPatternNotes(), null);

		children = findChoices(note).stream()
				.map(PatternNoteAudioChoice::getNoteAudioSelection)
				.map(audioContext::selectAudio)
				.map(note -> note instanceof SimplePatternNote ? ((SimplePatternNote) note).getNoteAudio() : null)
				.map(audio -> audio instanceof NoteAudioProvider ? ((NoteAudioProvider) audio) : null)
				.filter(Objects::nonNull)
				.distinct()
				.map(AudioProviderNode::create)
				.collect(Collectors.toList());
	}

	/**
	 * Recursively finds all {@link PatternNoteAudioChoice} instances within the given note.
	 *
	 * @param note the note audio to search
	 * @return a list of all pattern note audio choices
	 */
	protected List<PatternNoteAudioChoice> findChoices(PatternNoteAudio note) {
		if (note instanceof PatternNote && ((PatternNote) note).getLayers() != null) {
			return ((PatternNote) note).getLayers().stream()
					.map(this::findChoices)
					.flatMap(Collection::stream)
					.collect(Collectors.toList());
		} else if (note instanceof PatternNoteAudioAdapter && ((PatternNoteAudioAdapter) note).getDelegate() != null) {
			return findChoices(((PatternNoteAudioAdapter) note).getDelegate());
		} else if (note instanceof PatternNoteAudioChoice) {
			return List.of(((PatternNoteAudioChoice) note));
		}

		throw new UnsupportedOperationException(note.getClass().getName());
	}

	/** Sets the list of child audio nodes. */
	public void setChildren(List<NoteAudioNode> children) {
		this.children = children;
	}

	@Override
	public Collection<NoteAudioNode> getChildren() {
		return children;
	}
}
