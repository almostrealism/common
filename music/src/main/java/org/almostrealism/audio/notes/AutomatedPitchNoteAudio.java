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
import org.almostrealism.Ops;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.sources.BufferDetails;
import org.almostrealism.audio.sources.StatelessSource;
import org.almostrealism.audio.tone.KeyPosition;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.heredity.IdentityFactor;

/**
 * {@link AutomatedPitchNoteAudio} extends {@link StatelessSourceNoteAudioAdapter}
 * to provide a {@link PatternNoteAudio} implementation with frequency tuned via
 * automation using a {@link Factor}.
 *
 * @author  Michael Murray
 */
public class AutomatedPitchNoteAudio extends StatelessSourceNoteAudioAdapter {

	private final Factor<PackedCollection> frequency;

	public AutomatedPitchNoteAudio(StatelessSource source, double duration) {
		this(source, new BufferDetails(OutputLine.sampleRate, duration));
	}

	public AutomatedPitchNoteAudio(StatelessSource source,
								   BufferDetails buffer) {
		this(source, buffer, new IdentityFactor<>(), 30, 17500);
	}

	public AutomatedPitchNoteAudio(StatelessSource source,
								   BufferDetails buffer,
								   Factor<PackedCollection> params,
								   int minFrequency, int maxFrequency) {
		this(source, buffer, params,
				level -> Ops.op(o ->
						o.c(minFrequency).add(
								o.multiply(level, o.c(maxFrequency - minFrequency)))));
	}

	public AutomatedPitchNoteAudio(StatelessSource source,
								   BufferDetails buffer,
								   Factor<PackedCollection> params,
								   Factor<PackedCollection> frequency) {
		super(source, buffer, params);
		this.frequency = frequency;
	}

	@Override
	public Factor<PackedCollection> getFrequency(KeyPosition<?> target,
													Factor<PackedCollection> automationLevel) {
		return time -> frequency.getResultant(automationLevel.getResultant(time));
	}
}
