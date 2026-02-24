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

import java.util.List;

// TODO  Rename to MultiNoteAudioNode
public class MultiSceneAudioNode implements NoteAudioNode {
	private final NoteAudioNode[] children;

	public MultiSceneAudioNode(int sceneCount) {
		children = new NoteAudioNode[sceneCount];

		for (int i = 0; i < sceneCount; i++) {
			children[i] = new SceneAudioNode();
		}
	}

	public void setScene(int sceneIndex, SceneAudioNode scene) {
		children[sceneIndex] = scene;
	}

	public void setNote(int sceneIndex, AudioProviderNode node) {
		children[sceneIndex] = node;
	}

	@Override
	public List<NoteAudioNode> getChildren() {
		return List.of(children);
	}

	@Override
	public String getName() {
		return "Project";
	}
}
