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

import org.almostrealism.audio.api.Audio;
import org.almostrealism.audio.midi.MidiNotes;
import org.almostrealism.audio.tone.KeyPosition;
import org.almostrealism.audio.tone.WesternChromatic;

import java.util.OptionalInt;

/**
 * The single, authoritative accessor for "the captured pitch of an
 * {@link Audio.AudioLayer}".
 *
 * <p>A layer's captured pitch is the note its audio was actually rendered
 * at — e.g. the exact MIDI note an AudioUnit chromatic capture produced.
 * This helper exists so the rest of the codebase has one honest place to
 * ask for it rather than re-deriving the pitch from display text at every
 * call site.</p>
 *
 * <h2>Resolution order</h2>
 * <ol>
 *   <li>The structured {@code root_midi_note} field, when present. This is
 *       the authoritative source: it is written at capture time directly
 *       from the MIDI note the coordinator iterated.</li>
 *   <li>Otherwise, a best-effort parse of the trailing note name in
 *       {@code layer_id} (e.g. {@code "DLSMusicDevice C#4"}). This is the
 *       brittle legacy path — it only covers layers saved before the
 *       structured field existed, and only when the display name ends in a
 *       parseable scientific-pitch token. {@link MidiNotes#parseNoteName}
 *       round-trips the exact spelling {@link MidiNotes#noteName} emits, so
 *       a layer named by the capture pipeline parses back correctly.</li>
 * </ol>
 *
 * <p>This helper does not assign or guess a pitch; an unknown pitch is
 * reported as empty/null so callers can apply their own policy (e.g.
 * excluding pitchless members from nearest-pitch selection).</p>
 *
 * @see MidiNotes#parseNoteName(String)
 */
public final class AudioLayerPitch {
	/**
	 * MIDI note number corresponding to {@link WesternChromatic} position
	 * 0 ({@code A0}). WesternChromatic numbers positions chromatically from
	 * A0, so {@code keyPosition = midiNote - MIDI_AT_POSITION_ZERO}.
	 */
	private static final int MIDI_AT_POSITION_ZERO = 21;

	/** Utility class — not instantiable. */
	private AudioLayerPitch() { }

	/**
	 * Returns the captured pitch of the layer as a MIDI note number,
	 * preferring the structured {@code root_midi_note} field and falling
	 * back to a parse of the {@code layer_id} display text.
	 *
	 * @param layer the layer to inspect; may be {@code null}.
	 * @return the captured MIDI note, or {@link OptionalInt#empty()} when
	 *         the layer carries no recoverable pitch.
	 */
	public static OptionalInt capturedMidiNote(Audio.AudioLayer layer) {
		if (layer == null) return OptionalInt.empty();
		if (layer.hasRootMidiNote()) return OptionalInt.of(layer.getRootMidiNote());
		return MidiNotes.parseNoteName(trailingToken(layer.getLayerId()));
	}

	/**
	 * Returns the captured pitch of the layer as a {@link KeyPosition} for
	 * use by the pitch-delivery layer, or {@code null} when the pitch is
	 * unknown or falls outside the {@link WesternChromatic} range
	 * ({@code A0}–{@code C8}).
	 *
	 * @param layer the layer to inspect; may be {@code null}.
	 * @return the captured key position, or {@code null} when unavailable.
	 */
	public static KeyPosition<?> capturedKeyPosition(Audio.AudioLayer layer) {
		OptionalInt midi = capturedMidiNote(layer);
		return midi.isPresent() ? midiToKeyPosition(midi.getAsInt()) : null;
	}

	/**
	 * Maps a MIDI note number to its {@link WesternChromatic} key position.
	 *
	 * @param midiNote a MIDI note number.
	 * @return the corresponding key position, or {@code null} when the note
	 *         lies outside the WesternChromatic range ({@code A0}–{@code C8},
	 *         MIDI 21–108).
	 */
	public static KeyPosition<?> midiToKeyPosition(int midiNote) {
		int position = midiNote - MIDI_AT_POSITION_ZERO;
		if (position < 0 || position >= WesternChromatic.scale().length()) return null;
		return WesternChromatic.scale().valueAt(position);
	}

	/**
	 * Returns the final whitespace-delimited token of {@code layerId} — the
	 * note-name fragment of a capture display name such as
	 * {@code "DLSMusicDevice C#4"}. Returns {@code layerId} itself when it
	 * holds no whitespace, and {@code null} for a null input.
	 */
	private static String trailingToken(String layerId) {
		if (layerId == null) return null;
		String trimmed = layerId.trim();
		int lastSpace = trimmed.lastIndexOf(' ');
		return lastSpace < 0 ? trimmed : trimmed.substring(lastSpace + 1);
	}
}
