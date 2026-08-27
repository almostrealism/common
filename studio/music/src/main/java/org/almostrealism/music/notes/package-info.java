/*
 * Copyright 2025 Michael Murray
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

/**
 * Note audio abstractions and source types for the music pattern system.
 *
 * <p>This package defines the note audio hierarchy used by the pattern rendering pipeline,
 * including {@link org.almostrealism.music.notes.PatternNoteAudio},
 * {@link org.almostrealism.music.notes.PatternNote},
 * {@link org.almostrealism.music.notes.NoteAudioChoice}, and
 * {@link org.almostrealism.music.notes.NoteAudioSource}.</p>
 *
 * <p>{@link org.almostrealism.music.notes.NoteAudioSource} implementations
 * include {@link org.almostrealism.music.notes.FileNoteSource} (a single
 * audio file, persisted with the scene), {@link org.almostrealism.music.notes.GroupNoteSource}
 * (a saved audio group, rebuilt from the library on each assemble and reported as
 * {@link org.almostrealism.music.notes.NoteAudioSource#isPersistent() non-persistent}),
 * and {@link org.almostrealism.music.notes.TreeNoteSource} (a hierarchical file-tree
 * source with lazy path resolution).</p>
 */
package org.almostrealism.music.notes;
