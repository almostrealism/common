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
import org.almostrealism.music.notes.AudioProviderNode;
import org.almostrealism.music.notes.NoteAudioNode;

import java.util.List;

// TODO  Rename to MultiNoteAudioNode
/**
 * Composite audio node that aggregates multiple scene or note audio nodes as children.
 * Each child slot corresponds to one scene or audio source in a multi-scene project.
 */
public class MultiSceneAudioNode implements NoteAudioNode {
	/** The per-scene child audio nodes. */
	private final NoteAudioNode[] children;

	/**
	 * Creates a multi-scene node with the given number of scene slots.
	 *
	 * @param sceneCount the number of scene slots
	 */
	public MultiSceneAudioNode(int sceneCount) {
		children = new NoteAudioNode[sceneCount];

		for (int i = 0; i < sceneCount; i++) {
			children[i] = new SceneAudioNode();
		}
	}

	/**
	 * Sets the child scene audio node for the given slot.
	 *
	 * @param sceneIndex the zero-based slot index
	 * @param scene      the scene node to place in the slot
	 */
	public void setScene(int sceneIndex, SceneAudioNode scene) {
		children[sceneIndex] = scene;
	}

	/**
	 * Sets an audio provider node for the given slot.
	 *
	 * @param sceneIndex the zero-based slot index
	 * @param node       the audio provider node to place in the slot
	 */
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
