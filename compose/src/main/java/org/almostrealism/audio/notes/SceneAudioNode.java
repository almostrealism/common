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

import io.almostrealism.uml.Nameable;
import org.almostrealism.audio.AudioScene;
import org.almostrealism.audio.pattern.PatternElement;
import org.almostrealism.io.ConsoleFeatures;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class SceneAudioNode implements NoteAudioNode, Nameable, ConsoleFeatures {
	private String key, name;
	private AudioScene<?> scene;
	private List<NoteAudioNode> children;

	public SceneAudioNode() { }

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
