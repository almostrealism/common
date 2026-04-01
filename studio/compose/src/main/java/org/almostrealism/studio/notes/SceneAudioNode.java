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
import org.almostrealism.music.notes.NoteAudioNode;
import org.almostrealism.music.notes.NoteAudioChoice;

import io.almostrealism.uml.Nameable;
import org.almostrealism.studio.AudioScene;
import org.almostrealism.music.pattern.PatternElement;
import org.almostrealism.io.ConsoleFeatures;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Audio node representing a complete audio scene, with per-channel child nodes
 * filtered by an optional channel list. Supports time-range filtering of pattern
 * elements for sub-scene views.
 */
public class SceneAudioNode implements NoteAudioNode, Nameable, ConsoleFeatures {
	/** Unique key identifying this scene node. */
	private String key;

	/** Human-readable name of this scene node. */
	private String name;

	/** The audio scene this node represents. */
	private AudioScene<?> scene;

	/** Per-channel child audio nodes. */
	private List<NoteAudioNode> children;

	/** Creates an empty scene node with no scene or children. */
	public SceneAudioNode() { }

	/**
	 * Creates a scene node for the given scene, exposing the specified channels.
	 *
	 * @param key      unique key for this node
	 * @param name     display name for this node
	 * @param scene    the audio scene to represent
	 * @param channels the channel indices to expose, or {@code null} for all channels
	 */
	public SceneAudioNode(String key, String name, AudioScene<?> scene, List<Integer> channels) {
		setKey(key);
		setName(name);
		this.scene = scene;
		this.children = IntStream.range(0, scene.getChannelCount())
				.filter(i -> channels == null || channels.contains(i))
				.mapToObj(i -> {
					ChannelAudioNode node = new ChannelAudioNode(scene, i);
					node.setChoices(scene.getPatternManager().getChoices()
							.stream()
							.filter(c -> c.getChannels().contains(i))
							.collect(Collectors.toList()));
					return node;
				})
				.collect(Collectors.toList());
	}

	/**
	 * Filters child channel nodes to include only pattern elements within the given
	 * time range.
	 *
	 * @param start range start in measures (inclusive)
	 * @param end   range end in measures (exclusive)
	 */
	public void setRange(double start, double end) {
		if (scene == null) {
			warn("Scene not available for adjusting range");
			return;
		}

		Map<NoteAudioChoice, List<PatternElement>> elements =
				scene.getPatternManager().getPatternElements(start, end);
		getChildren().forEach(c -> ((ChannelAudioNode) c).setPatternElements(elements));
	}

	public String getKey() { return key; }
	public void setKey(String key) { this.key = key; }

	@Override
	public void setName(String name) { this.name = name; }

	@Override
	public String getName() { return name; }

	public void setChildren(List<NoteAudioNode> children) { this.children = children; }

	@Override
	public List<NoteAudioNode> getChildren() { return children; }
}
