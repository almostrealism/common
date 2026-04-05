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

package org.almostrealism.studio.midi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.almostrealism.ml.midi.MidiCompoundToken;
import org.almostrealism.music.midi.MidiNoteEvent;

/**
 * Tokenizes MIDI note events into compound token sequences for the
 * Moonbeam transformer model.
 *
 * <p>Each MIDI note is represented as a {@link MidiCompoundToken} with 6
 * attributes. Onset values are stored as relative deltas from the previous
 * note's onset. The tokenizer handles SOS/EOS framing and can convert
 * back from compound tokens to note events.</p>
 *
 * <p>Attribute value clamping:</p>
 * <ul>
 *   <li>Onset delta and duration are clamped to [0, 4098]</li>
 *   <li>Octave is clamped to [0, 10] (MIDI pitches 0-127 have octaves 0-10)</li>
 *   <li>Pitch class is [0, 11]</li>
 *   <li>Instrument is clamped to [0, 128] (128 = drums)</li>
 *   <li>Velocity is clamped to [0, 127]</li>
 * </ul>
 *
 * @see MidiCompoundToken
 * @see MidiNoteEvent
 * @see MidiFileReader
 */
public class MidiTokenizer {

	/** Ticks per second for onset/duration quantization. */
	public static final int TIME_RESOLUTION = 100;

	/** Maximum onset delta or duration value (vocab size - 1 for onset/duration). */
	public static final int MAX_TIME_VALUE = 4098;

	/** Maximum octave value. */
	public static final int MAX_OCTAVE = 10;

	/** Maximum pitch class value. */
	public static final int MAX_PITCH_CLASS = 11;

	/** Maximum instrument value (128 = drums). */
	public static final int MAX_INSTRUMENT = 128;

	/** Maximum velocity value. */
	public static final int MAX_VELOCITY = 127;

	/**
	 * Convert a list of note events into a compound token sequence.
	 *
	 * <p>The returned sequence is framed with SOS at the start and EOS at the end.
	 * Events are sorted by onset time before tokenization. Onset values are
	 * encoded as deltas from the previous note's onset.</p>
	 *
	 * @param events the note events to tokenize
	 * @return compound token sequence including SOS and EOS
	 */
	public List<MidiCompoundToken> tokenize(List<MidiNoteEvent> events) {
		List<MidiNoteEvent> sorted = new ArrayList<>(events);
		Collections.sort(sorted);

		List<MidiCompoundToken> tokens = new ArrayList<>(sorted.size() + 2);
		tokens.add(MidiCompoundToken.sos());

		long previousOnset = 0;
		for (MidiNoteEvent event : sorted) {
			int onsetDelta = clamp((int) (event.getOnset() - previousOnset), 0, MAX_TIME_VALUE);
			int duration = clamp((int) event.getDuration(), 0, MAX_TIME_VALUE);
			int octave = clamp(event.getOctave(), 0, MAX_OCTAVE);
			int pitchClass = clamp(event.getPitchClass(), 0, MAX_PITCH_CLASS);
			int instrument = clamp(event.getInstrument(), 0, MAX_INSTRUMENT);
			int velocity = clamp(event.getVelocity(), 0, MAX_VELOCITY);

			tokens.add(new MidiCompoundToken(onsetDelta, duration, octave,
					pitchClass, instrument, velocity));
			previousOnset = event.getOnset();
		}

		tokens.add(MidiCompoundToken.eos());
		return tokens;
	}

	/**
	 * Convert a compound token sequence back to note events.
	 *
	 * <p>SOS, EOS, and PAD tokens are skipped. Relative onset deltas are
	 * accumulated to reconstruct absolute onset times.</p>
	 *
	 * @param tokens the compound token sequence
	 * @return list of reconstructed note events
	 */
	public List<MidiNoteEvent> detokenize(List<MidiCompoundToken> tokens) {
		List<MidiNoteEvent> events = new ArrayList<>();
		long currentOnset = 0;

		for (MidiCompoundToken token : tokens) {
			if (token.isSpecial()) continue;

			currentOnset += token.getOnset();
			int pitch = clamp(token.getOctave() * 12 + token.getPitchClass(), 0, 127);
			int velocity = clamp(token.getVelocity(), 0, 127);
			int instrument = clamp(token.getInstrument(), 0, 127);
			events.add(new MidiNoteEvent(
					pitch, currentOnset, token.getDuration(),
					velocity, instrument));
		}

		return events;
	}

	/**
	 * Convert a compound token sequence to model input format.
	 *
	 * <p>Returns a 2D array of shape (seqLen, 6) where each row contains
	 * the 6 attribute values for one token position.</p>
	 *
	 * @param tokens the compound token sequence
	 * @return 2D int array of shape (tokens.size(), 6)
	 */
	public int[][] toModelInput(List<MidiCompoundToken> tokens) {
		int[][] input = new int[tokens.size()][MidiCompoundToken.ATTRIBUTE_COUNT];
		for (int i = 0; i < tokens.size(); i++) {
			input[i] = tokens.get(i).toArray();
		}
		return input;
	}

	/**
	 * Clamps {@code value} to the inclusive range [{@code min}, {@code max}].
	 */
	static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}
