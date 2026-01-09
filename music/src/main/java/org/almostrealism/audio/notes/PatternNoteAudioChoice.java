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

package org.almostrealism.audio.notes;

import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import org.almostrealism.audio.tone.KeyPosition;
import org.almostrealism.collect.PackedCollection;

import java.util.function.DoubleFunction;

public class PatternNoteAudioChoice implements PatternNoteAudio {
	public static final long selectionComparisonGranularity = (long) 1e10;

	private double noteAudioSelection;

	public PatternNoteAudioChoice() { this(0.0); }

	public PatternNoteAudioChoice(double noteAudioSelection) {
		setNoteAudioSelection(noteAudioSelection);
	}

	public double getNoteAudioSelection() { return noteAudioSelection; }
	public void setNoteAudioSelection(double noteAudioSelection) {
		this.noteAudioSelection = noteAudioSelection;
	}

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
												  DoubleFunction<PatternNoteAudio> audioSelection) {
		return getDelegate(audioSelection).getAudio(target, channel,
						noteDuration, automationLevel, audioSelection);
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
