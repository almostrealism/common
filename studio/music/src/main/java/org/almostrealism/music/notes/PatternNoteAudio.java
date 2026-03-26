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
import org.almostrealism.audio.notes.NoteAudioFilter;

import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import org.almostrealism.audio.sources.BufferDetails;
import org.almostrealism.audio.tone.KeyPosition;
import org.almostrealism.collect.PackedCollection;

import java.util.function.DoubleFunction;

public interface PatternNoteAudio {

	default BufferDetails getBufferDetails(KeyPosition<?> target,
										   DoubleFunction<PatternNoteAudio> audioSelection) {
		return new BufferDetails(
				getSampleRate(target, audioSelection),
				getDuration(target, audioSelection));
	}

	default int getSampleRate(KeyPosition<?> target) {
		return getSampleRate(target, null);
	}

	int getSampleRate(KeyPosition<?> target,
					  DoubleFunction<PatternNoteAudio> audioSelection);

	double getDuration(KeyPosition<?> target, DoubleFunction<PatternNoteAudio> audioSelection);

	default Producer<PackedCollection> getAudio(KeyPosition<?> target, int channel) {
		return getAudio(target, channel, null);
	}

	default PatternNoteLayer filter(NoteAudioFilter filter) {
		return new PatternNoteLayer(this, filter);
	}

	Producer<PackedCollection> getAudio(KeyPosition<?> target, int channel,
										   DoubleFunction<PatternNoteAudio> audioSelection);

	/**
	 * Returns audio for the note, optionally for a specific frame range.
	 *
	 * <p>When {@code offset} is non-null, only {@code frameCount} frames are evaluated
	 * starting at the position stored in the {@code offset} {@link PackedCollection},
	 * enabling partial note evaluation for real-time rendering. When {@code offset}
	 * is null, the full note is evaluated.</p>
	 *
	 * @param target key position for pitch
	 * @param channel stereo channel index
	 * @param noteDuration effective note duration in seconds
	 * @param automationLevel automation factor
	 * @param audioSelection audio selection function
	 * @param offset PackedCollection containing the start frame (note-relative), or null for full evaluation
	 * @param frameCount number of frames to evaluate (ignored when offset is null)
	 * @return a Producer that generates the requested audio
	 */
	Producer<PackedCollection> getAudio(KeyPosition<?> target, int channel,
										   double noteDuration,
										   Factor<PackedCollection> automationLevel,
										   DoubleFunction<PatternNoteAudio> audioSelection,
										   PackedCollection offset, int frameCount);
}
