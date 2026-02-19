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

/** The PatternNoteAudio interface. */
public interface PatternNoteAudio {

	/** Performs the getBufferDetails operation. */
	default BufferDetails getBufferDetails(KeyPosition<?> target,
										   DoubleFunction<PatternNoteAudio> audioSelection) {
		return new BufferDetails(
				getSampleRate(target, audioSelection),
				getDuration(target, audioSelection));
	}

	/** Performs the getSampleRate operation. */
	default int getSampleRate(KeyPosition<?> target) {
		return getSampleRate(target, null);
	}

	/** Performs the getSampleRate operation. */
	int getSampleRate(KeyPosition<?> target,
					  DoubleFunction<PatternNoteAudio> audioSelection);

	/** Performs the getDuration operation. */
	double getDuration(KeyPosition<?> target, DoubleFunction<PatternNoteAudio> audioSelection);

	/** Performs the getAudio operation. */
	default Producer<PackedCollection> getAudio(KeyPosition<?> target, int channel) {
		return getAudio(target, channel, null);
	}

	/** Performs the filter operation. */
	default PatternNoteLayer filter(NoteAudioFilter filter) {
		return new PatternNoteLayer(this, filter);
	}

	/** Performs the getAudio operation. */
	Producer<PackedCollection> getAudio(KeyPosition<?> target, int channel,
										   DoubleFunction<PatternNoteAudio> audioSelection);

	/** Performs the getAudio operation. */
	Producer<PackedCollection> getAudio(KeyPosition<?> target, int channel,
										   double noteDuration,
										   Factor<PackedCollection> automationLevel,
										   DoubleFunction<PatternNoteAudio> audioSelection);
}
