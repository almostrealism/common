/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.studio.persistence;

import org.almostrealism.audio.AudioLibrary;
import org.almostrealism.audio.api.Audio;
import org.almostrealism.audio.data.WaveDataProvider;
import org.almostrealism.audio.notes.NoteAudioGroup;
import org.almostrealism.audio.notes.NoteAudioProvider;
import org.almostrealism.audio.tone.KeyPosition;
import org.almostrealism.music.notes.GroupNoteSource;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the render-time {@link NoteAudioGroup} (and its
 * {@link GroupNoteSource} wrapper) from a persisted
 * {@link Audio.AudioLayerGroup}.
 *
 * <p>This builder must live in {@code studio/compose} because it is the lowest
 * module where the generated proto type {@link Audio} and the render type
 * {@link NoteAudioGroup} (from {@code engine/audio}, via {@code studio/music})
 * are both visible — mirroring the placement of {@link AudioLayerGroupLibrary},
 * the save-side coordinator.</p>
 *
 * <h2>Member construction</h2>
 *
 * <p>Each audio layer becomes one {@link NoteAudioProvider} member:</p>
 * <ul>
 *   <li>The layer's audio bytes are resolved by content identifier — the
 *       {@code audio_ref} of a saved (stripped) layer, or the inline
 *       {@code audio.identifier} of an unsaved one — through
 *       {@link AudioLibrary#find(String)}.</li>
 *   <li>The member's root pitch is the layer's captured pitch, read through the
 *       single authoritative accessor {@link AudioLayerPitch#capturedKeyPosition}.</li>
 * </ul>
 *
 * <p>A layer is skipped (it contributes no member) when it carries no audio
 * (MIDI or metadata-only), when its bytes cannot be resolved in the library, or
 * when it has no recoverable captured pitch. The last case implements the
 * pitchless-member policy: such members are excluded from the group's pitched
 * delivery rather than rate-shifted from an assumed root. A group with no usable
 * members yields a {@link NoteAudioGroup#isValid() invalid} group, which the
 * selection layer filters out before render.</p>
 *
 * @see NoteAudioGroup
 * @see GroupNoteSource
 * @see AudioLayerGroupLibrary
 * @see AudioLayerPitch
 */
public final class NoteAudioGroupBuilder {

	/** Utility class — not instantiable. */
	private NoteAudioGroupBuilder() { }

	/**
	 * Builds a {@link NoteAudioGroup} from a persisted group, resolving each
	 * audio layer's bytes through the library and its root pitch through
	 * {@link AudioLayerPitch}.
	 *
	 * @param group   the persisted group; may be {@code null}
	 * @param library the library used to resolve layer audio by content
	 *                identifier; may be {@code null}
	 * @return the render-time group (possibly with no usable members when
	 *         nothing resolves)
	 */
	public static NoteAudioGroup build(Audio.AudioLayerGroup group, AudioLibrary library) {
		List<NoteAudioProvider> members = new ArrayList<>();
		if (group == null || library == null) {
			return new NoteAudioGroup(members);
		}

		for (Audio.AudioLayer layer : group.getLayersList()) {
			NoteAudioProvider member = buildMember(layer, library);
			if (member != null) {
				members.add(member);
			}
		}

		return new NoteAudioGroup(members);
	}

	/**
	 * Builds a {@link GroupNoteSource} that surfaces the group as a single
	 * selectable candidate, using the group's key as the source origin.
	 *
	 * @param group   the persisted group; may be {@code null}
	 * @param library the library used to resolve layer audio; may be {@code null}
	 * @return a source wrapping the built group
	 */
	public static GroupNoteSource source(Audio.AudioLayerGroup group, AudioLibrary library) {
		String origin = group == null ? null : group.getKey();
		return new GroupNoteSource(build(group, library), origin);
	}

	/**
	 * Resolves a single audio layer to a {@link NoteAudioProvider} member, or
	 * {@code null} when the layer carries no audio, its bytes are unresolvable,
	 * or it has no recoverable captured pitch.
	 */
	private static NoteAudioProvider buildMember(Audio.AudioLayer layer, AudioLibrary library) {
		String identifier = audioIdentifier(layer);
		if (identifier == null || identifier.isBlank()) return null;

		WaveDataProvider provider = library.find(identifier);
		if (provider == null) return null;

		KeyPosition<?> root = AudioLayerPitch.capturedKeyPosition(layer);
		if (root == null) return null;

		return new NoteAudioProvider(provider, root);
	}

	/**
	 * Returns the content identifier for a layer's audio bytes — the
	 * {@code audio_ref} of a saved (stripped) layer, otherwise the inline
	 * {@code audio.identifier}. Returns {@code null} for MIDI or metadata-only
	 * layers.
	 */
	private static String audioIdentifier(Audio.AudioLayer layer) {
		if (layer.hasAudioRef()) {
			return layer.getAudioRef();
		}
		if (layer.hasAudio()) {
			return layer.getAudio().getIdentifier();
		}
		return null;
	}
}
