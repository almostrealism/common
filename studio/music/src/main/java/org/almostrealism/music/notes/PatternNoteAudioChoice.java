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

package org.almostrealism.music.notes;

import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import org.almostrealism.audio.tone.KeyPosition;
import org.almostrealism.collect.PackedCollection;

import java.util.function.DoubleFunction;

/**
 * A {@link PatternNoteAudio} that delegates to a {@link PatternNoteAudio} selected
 * by a floating-point selection value via a {@link java.util.function.DoubleFunction}.
 *
 * <p>The {@code noteAudioSelection} value is passed to an audio selection function at
 * render time to pick the concrete {@link PatternNoteAudio} delegate. Equality is
 * compared with fixed-precision integer scaling to avoid floating-point issues.</p>
 *
 * @see PatternNoteAudio
 */
public class PatternNoteAudioChoice implements PatternNoteAudio {
	/** Scaling factor used when comparing {@code noteAudioSelection} values for equality. */
	public static final long selectionComparisonGranularity = (long) 1e10;

	/** The selection value passed to the audio selection function to pick the delegate. */
	private double noteAudioSelection;

	/** Creates a {@code PatternNoteAudioChoice} with a selection value of 0.0. */
	public PatternNoteAudioChoice() { this(0.0); }

	/**
	 * Creates a {@code PatternNoteAudioChoice} with the given selection value.
	 *
	 * @param noteAudioSelection the selection value
	 */
	public PatternNoteAudioChoice(double noteAudioSelection) {
		setNoteAudioSelection(noteAudioSelection);
	}

	/** Returns the selection value used to pick the delegate. */
	public double getNoteAudioSelection() { return noteAudioSelection; }

	/** Sets the selection value used to pick the delegate. */
	public void setNoteAudioSelection(double noteAudioSelection) {
		this.noteAudioSelection = noteAudioSelection;
	}

	/**
	 * Returns the delegate selected by the given audio selection function.
	 *
	 * @param audioSelection the function mapping the selection value to a {@link PatternNoteAudio}
	 * @return the selected delegate
	 */
	public PatternNoteAudio getDelegate(DoubleFunction<PatternNoteAudio> audioSelection) {
		return audioSelection.apply(getNoteAudioSelection());
	}

	@Override
	public int getSampleRate(KeyPosition<?> target, DoubleFunction<PatternNoteAudio> audioSelection) {
		return getDelegate(audioSelection).getSampleRate(target, audioSelection);
	}

	@Override
	public double getDuration(KeyPosition<?> target, DoubleFunction<PatternNoteAudio> audioSelection) {
		return getDelegate(audioSelection).getDuration(target, audioSelection);
	}

	@Override
	public Producer<PackedCollection> getAudio(KeyPosition<?> target, int channel, double noteDuration,
												  Factor<PackedCollection> automationLevel,
												  DoubleFunction<PatternNoteAudio> audioSelection,
												  PackedCollection offset, int frameCount) {
		return getDelegate(audioSelection).getAudio(target, channel,
						noteDuration, automationLevel, audioSelection, offset, frameCount);
	}

	@Override
	public Producer<PackedCollection> getAudio(KeyPosition<?> target, int channel,
												  DoubleFunction<PatternNoteAudio> audioSelection) {
		return getDelegate(audioSelection).getAudio(target, channel, audioSelection);
	}

	@Override
	public boolean equals(Object obj) {
		if (obj instanceof PatternNoteAudioChoice other) {

			long compA = (long) (other.getNoteAudioSelection() * selectionComparisonGranularity);
			long compB = (long) (getNoteAudioSelection() * selectionComparisonGranularity);
			return compA == compB;
		}

		return false;
	}

	@Override
	public int hashCode() {
		return Double.valueOf(getNoteAudioSelection()).hashCode();
	}
}
