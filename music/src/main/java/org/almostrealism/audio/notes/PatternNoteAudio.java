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

	Producer<PackedCollection> getAudio(KeyPosition<?> target, int channel,
										   double noteDuration,
										   Factor<PackedCollection> automationLevel,
										   DoubleFunction<PatternNoteAudio> audioSelection);

	/**
	 * Returns audio for a specific frame range within the note.
	 *
	 * <p>This enables partial note evaluation for real-time rendering. Instead of
	 * producing the entire note's audio, only the frames in
	 * {@code [startFrame, startFrame + frameCount)} are evaluated. Filters and
	 * automation see the correct note-relative frame positions.</p>
	 *
	 * <p>The default implementation falls back to full note evaluation.</p>
	 *
	 * @param target key position for pitch
	 * @param channel stereo channel index
	 * @param noteDuration effective note duration in seconds
	 * @param automationLevel automation factor
	 * @param audioSelection audio selection function
	 * @param startFrame first frame to evaluate (note-relative)
	 * @param frameCount number of frames to evaluate
	 * @return a Producer that generates only the requested frame range
	 */
	default Producer<PackedCollection> getAudio(KeyPosition<?> target, int channel,
												   double noteDuration,
												   Factor<PackedCollection> automationLevel,
												   DoubleFunction<PatternNoteAudio> audioSelection,
												   int startFrame, int frameCount) {
		return getAudio(target, channel, noteDuration, automationLevel, audioSelection);
	}
}
