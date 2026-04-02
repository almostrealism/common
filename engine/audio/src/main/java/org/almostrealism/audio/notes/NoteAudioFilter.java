/*
 * Copyright 2023 Michael Murray
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

import io.almostrealism.relation.Producer;
import org.almostrealism.audio.SamplingFeatures;
import org.almostrealism.collect.PackedCollection;

/**
 * Interface for audio effects that can be applied to note-based audio signals.
 * Filters transform input audio based on note duration and automation level,
 * enabling dynamic processing that responds to musical timing and control parameters.
 * Implementations include effects like tremolo, reverse playback, and other
 * time-based transformations.
 *
 * @see ReversePlaybackAudioFilter
 * @see TremoloAudioFilter
 * @see SamplingFeatures
 */
public interface NoteAudioFilter extends SamplingFeatures {
	/**
	 * Applies this filter to the given audio input and returns the processed output.
	 *
	 * @param input           the input audio producer to filter
	 * @param noteDuration    producer yielding the note duration in seconds
	 * @param automationLevel producer yielding a normalized automation level (0.0–1.0)
	 * @return a producer yielding the filtered audio
	 */
	Producer<PackedCollection> apply(Producer<PackedCollection> input,
										Producer<PackedCollection> noteDuration,
										Producer<PackedCollection> automationLevel);
}
