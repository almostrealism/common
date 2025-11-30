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

import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.audio.filter.AudioProcessingUtils;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DynamicCollectionProducer;

public class ReversePlaybackAudioFilter implements NoteAudioFilter, CodeFeatures {
	@Override
	public Producer<PackedCollection> apply(Producer<PackedCollection> input,
											   Producer<PackedCollection> noteDuration,
											   Producer<PackedCollection> automationLevel) {
		return new DynamicCollectionProducer(shape(input), args -> {
			PackedCollection audioData = input.get().evaluate();

			PackedCollection out = AudioProcessingUtils.getReverse()
					.evaluate(audioData.traverse(1), pack(audioData.getShape().getTotalSize()));

			if (out.getShape().getTotalSize() == 1) {
				warn("Reverse filter produced a value with shape " +
						out.getShape().toStringDetail());
			}

			return out;
		}, false);
	}
}
