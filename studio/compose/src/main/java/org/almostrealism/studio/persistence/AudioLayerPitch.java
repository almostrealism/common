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
 * at — e.g. the exact note an AudioUnit chromatic capture produced. It is
 * modelled on the project's internal {@link KeyPosition} type rather than on
 * a MIDI note number: {@link KeyPosition} is strictly more general (its
 * {@code (system, position)} coordinate can express pitch systems a 12-TET
 * MIDI integer cannot), so persisting and reading the pitch never collapses
 * it through a MIDI value. This helper exists so the rest of the codebase has
 * one honest place to ask for the captured pitch rather than re-deriving it
 * from display text at every call site.</p>
 *
 * <h2>Resolution order</h2>
 * <ol>
 *   <li>The structured {@link Audio.KeyPositionData captured_pitch} field,
 *       when present. This is the authoritative source: it is written at
 *       capture time directly from the {@link KeyPosition} the coordinator
 *       resolved for the rendered note.</li>
 *   <li>Otherwise, a best-effort parse of the trailing note name in
 *       {@code layer_id} (e.g. {@code "DLSMusicDevice C#4"}). This is the
 *       brittle legacy path — it only covers layers saved before the
 *       structured field existed, and only when the display name ends in a
 *       parseable scientific-pitch token. {@link MidiNotes#parseNoteName}
 *       round-trips the exact spelling {@link MidiNotes#noteName} emits, so a
 *       layer named by the capture pipeline parses back correctly; the
 *       resulting MIDI note is mapped to a {@link KeyPosition} via
 *       {@link #midiToKeyPosition(int)}.</li>
 * </ol>
 *
 * <p>This helper does not assign or guess a pitch; an unknown pitch is
 * reported as {@code null} so callers can apply their own policy (e.g.
 * excluding pitchless members from nearest-pitch selection).</p>
 *
 * @see KeyPosition
 * @see WesternChromatic
 * @see MidiNotes#parseNoteName(String)
 */
public final class AudioLayerPitch {
	/**
	 * MIDI note number corresponding to {@link WesternChromatic} position
	 * 0 ({@code A0}). WesternChromatic numbers positions chromatically from
	 * A0, so {@code keyPosition = midiNote - MIDI_AT_POSITION_ZERO}. Used only
	 * by the MIDI bridge ({@link #midiToKeyPosition(int)}) for the legacy
	 * display-text fallback; the persisted captured pitch never stores a MIDI
	 * value.
	 */
	private static final int MIDI_AT_POSITION_ZERO = 21;

	/** Utility class — not instantiable. */
	private AudioLayerPitch() { }

	/**
	 * Returns the captured pitch of the layer as a {@link KeyPosition},
	 * preferring the structured {@link Audio.KeyPositionData captured_pitch}
	 * field and falling back to a parse of the {@code layer_id} display text.
	 *
	 * <p>This is the authoritative accessor: callers that need the captured
	 * pitch should use the {@link KeyPosition} it returns rather than any MIDI
	 * representation.</p>
	 *
	 * @param layer the layer to inspect; may be {@code null}.
	 * @return the captured key position, or {@code null} when the layer
	 *         carries no recoverable pitch (or one outside the supported
	 *         pitch-system range).
	 */
	public static KeyPosition<?> capturedKeyPosition(Audio.AudioLayer layer) {
		if (layer == null) return null;

		if (layer.hasCapturedPitch()) {
			KeyPosition<?> structured = fromKeyPositionData(layer.getCapturedPitch());
			if (structured != null) return structured;
			/* The structured field is present but unreconstructable (unknown
			   system or out-of-range position); fall through to the legacy
			   display-text parse rather than reporting no pitch at all. */
		}

		OptionalInt midi = MidiNotes.parseNoteName(trailingToken(layer.getLayerId()));
		return midi.isPresent() ? midiToKeyPosition(midi.getAsInt()) : null;
	}

	/**
	 * Builds the persisted {@link Audio.KeyPositionData} for a
	 * {@link KeyPosition} — the write half of the captured-pitch mapping.
	 *
	 * <p>Only {@link WesternChromatic} is supported today (the one pitch
	 * system in practical use); the stored {@code position} is the
	 * key's {@link KeyPosition#position()}, so reconstruction via
	 * {@link #fromKeyPositionData(Audio.KeyPositionData)} yields the identical
	 * key. New pitch systems become new {@link Audio.KeyPositionData.PitchSystem}
	 * values handled here.</p>
	 *
	 * @param position the key position to persist; must not be {@code null}.
	 * @return the structured pitch data for {@code position}.
	 * @throws IllegalArgumentException if {@code position} is {@code null} or
	 *                                  of an unsupported {@link KeyPosition}
	 *                                  type.
	 */
	public static Audio.KeyPositionData toKeyPositionData(KeyPosition<?> position) {
		if (position == null) {
			throw new IllegalArgumentException("position must not be null");
		}

		if (position instanceof WesternChromatic) {
			return Audio.KeyPositionData.newBuilder()
					.setSystem(Audio.KeyPositionData.PitchSystem.WESTERN_CHROMATIC)
					.setPosition(position.position())
					.build();
		}

		throw new IllegalArgumentException(
				"Unsupported KeyPosition type: " + position.getClass().getName());
	}

	/**
	 * Reconstructs a {@link KeyPosition} from persisted
	 * {@link Audio.KeyPositionData} — the read half of the captured-pitch
	 * mapping.
	 *
	 * @param data the structured pitch data; may be {@code null}.
	 * @return the reconstructed key position, or {@code null} when {@code data}
	 *         is {@code null}, names an unspecified/unknown pitch system, or
	 *         carries a position outside that system's range.
	 */
	public static KeyPosition<?> fromKeyPositionData(Audio.KeyPositionData data) {
		if (data == null) return null;

		switch (data.getSystem()) {
			case WESTERN_CHROMATIC:
				int position = data.getPosition();
				if (position < 0 || position >= WesternChromatic.scale().length()) return null;
				return WesternChromatic.scale().valueAt(position);
			default:
				return null;
		}
	}

	/**
	 * Maps a MIDI note number to its {@link WesternChromatic} key position.
	 *
	 * <p>This is the MIDI bridge used by the legacy {@code layer_id}
	 * display-text fallback (and by capture coordinators that drive an AU over
	 * MIDI and need the key the transport note denotes). It is not part of the
	 * persisted representation — the captured pitch is stored as a
	 * {@link KeyPosition}, never as a MIDI note.</p>
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
