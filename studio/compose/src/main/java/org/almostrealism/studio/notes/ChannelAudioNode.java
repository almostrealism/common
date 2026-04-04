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

package org.almostrealism.studio.notes;
import org.almostrealism.music.notes.NoteAudioChoice;
import org.almostrealism.music.notes.NoteAudioNode;

import org.almostrealism.studio.AudioScene;
import org.almostrealism.music.pattern.PatternElement;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Audio node representing a single pattern channel within an audio scene, with
 * child nodes for each note audio choice assigned to that channel.
 */
public class ChannelAudioNode implements NoteAudioNode {
	/** The audio scene this channel belongs to. */
	private AudioScene<?> scene;

	/** Optional override name; if {@code null} the scene channel name is used. */
	private String name;

	/** The zero-based pattern channel index. */
	private int channel;

	/** Child nodes representing the note audio choices for this channel. */
	private List<NoteAudioNode> children;

	/** Creates an empty node with no scene or children. */
	public ChannelAudioNode() { }

	/**
	 * Creates a channel node for the given scene and channel index.
	 *
	 * @param scene   the audio scene
	 * @param channel the zero-based channel index
	 */
	public ChannelAudioNode(AudioScene<?> scene, int channel) {
		this.scene = scene;
    	this.channel = channel;
	}

	/**
	 * Sets the channel index.
	 *
	 * @param channel the zero-based channel index
	 */
	public void setChannel(int channel) {
		this.channel = channel;
	}

	/** Returns the zero-based channel index. */
	public int getChannel() {
		return channel;
	}

	/**
	 * Sets an override name for this node.
	 *
	 * @param name the name override
	 */
	public void setName(String name) {
		this.name = name;
	}

	/** {@inheritDoc} */
	@Override
	public String getName() {
		if (name != null) return name;
		if (scene == null) return null;
		return scene.getChannelNames().get(getChannel());
	}

	/**
	 * Builds child nodes from the given list of note audio choices.
	 *
	 * @param choices the choices to build child nodes for
	 */
	public void setChoices(List<NoteAudioChoice> choices) {
		this.children = choices.stream()
				.map(AudioChoiceNode::new)
				.collect(Collectors.toList());
	}

	/**
	 * Propagates pattern elements to all child {@link AudioChoiceNode}s.
	 *
	 * @param elements map from choice to its pattern elements
	 */
	public void setPatternElements(Map<NoteAudioChoice, List<PatternElement>> elements) {
		for (NoteAudioNode node : children) {
			AudioChoiceNode child = (AudioChoiceNode) node;
			child.setPatternElements(elements);
		}
	}

	/**
	 * Directly sets the child nodes.
	 *
	 * @param children the children to set
	 */
	public void setChildren(List<NoteAudioNode> children) {
		this.children = children;
	}

	/** Returns the child note audio nodes for this channel. */
	public List<NoteAudioNode> getChildren() {
		return children;
	}
}
