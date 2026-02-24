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
import io.almostrealism.relation.Validity;
import org.almostrealism.audio.tone.KeyPosition;
import org.almostrealism.audio.tone.KeyboardTuned;
import org.almostrealism.audio.tone.KeyboardTuning;
import org.almostrealism.collect.PackedCollection;

import java.util.function.DoubleFunction;

public class SimplePatternNote implements PatternNoteAudio, KeyboardTuned, Validity {
	private final NoteAudio audio;

	public SimplePatternNote(NoteAudio audio) {
		this.audio = audio;
	}

	public NoteAudio getNoteAudio() { return audio; }

	@Override
	public void setTuning(KeyboardTuning tuning) {
		if (audio != null) {
			audio.setTuning(tuning);
		}
	}

	@Override
	public int getSampleRate(KeyPosition<?> target, DoubleFunction<PatternNoteAudio> audioSelection) {
		return audio.getSampleRate();
	}

	@Override
	public double getDuration(KeyPosition<?> target, DoubleFunction<PatternNoteAudio> audioSelection) {
		return audio.getDuration(target);
	}

	@Override
	public Producer<PackedCollection> getAudio(KeyPosition<?> target, int channel,
												  DoubleFunction<PatternNoteAudio> audioSelection) {
		return audio.getAudio(target, channel);
	}

	@Override
	public Producer<PackedCollection> getAudio(KeyPosition<?> target, int channel,
												  double noteDuration,
												  Factor<PackedCollection> automationLevel,
												  DoubleFunction<PatternNoteAudio> audioSelection) {
		return audio.getAudio(target, channel);
	}

	@Override
	public boolean isValid() { return Validity.valid(audio); }
}
