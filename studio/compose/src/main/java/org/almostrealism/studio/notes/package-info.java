/*
 * Copyright 2026 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

/**
 * Audio node hierarchy for composing multi-scene and multi-channel audio graphs in the
 * Almost Realism studio layer. Classes in this package provide graph node abstractions
 * that wire together channels, scenes, and audio choices for hierarchical audio rendering.
 *
 * <p>Key classes include:</p>
 * <ul>
 *   <li>{@link org.almostrealism.studio.notes.AudioChoiceNode} - Node representing a
 *       selectable audio choice within a composition graph</li>
 *   <li>{@link org.almostrealism.studio.notes.ChannelAudioNode} - Node representing a
 *       single mix channel's audio data</li>
 *   <li>{@link org.almostrealism.studio.notes.SceneAudioNode} - Node backed by a single
 *       {@link org.almostrealism.studio.AudioScene}</li>
 *   <li>{@link org.almostrealism.studio.notes.MultiSceneAudioNode} - Node that combines
 *       audio from multiple scenes</li>
 * </ul>
 */
package org.almostrealism.studio.notes;
