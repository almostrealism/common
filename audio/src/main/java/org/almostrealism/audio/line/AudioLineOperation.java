/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.audio.line;

import io.almostrealism.relation.Producer;
import org.almostrealism.audio.sources.AudioBuffer;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.time.TemporalRunner;

public interface AudioLineOperation {
	default BufferedOutputScheduler buffer(BufferedAudio line) {
		return BufferedOutputScheduler.create(
				line instanceof InputLine ? (InputLine) line : null,
				line instanceof OutputLine ? (OutputLine) line : null,
				this);
	}

	default TemporalRunner process(AudioBuffer buffer) {
		return process(
				CollectionFeatures.getInstance().p(buffer.getInputBuffer()),
				CollectionFeatures.getInstance().p(buffer.getOutputBuffer()),
				buffer.getDetails().getFrames());
	}

	TemporalRunner process(Producer<PackedCollection> input,
						   Producer<PackedCollection> output,
						   int frames);
}
