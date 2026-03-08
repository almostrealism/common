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
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.sources.BufferDetails;
import org.almostrealism.audio.sources.StatelessSource;
import org.almostrealism.audio.tone.KeyPosition;
import org.almostrealism.audio.tone.KeyboardTuned;
import org.almostrealism.audio.tone.KeyboardTuning;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.heredity.IdentityFactor;

/**
 * {@link StatelessSourceNoteAudioTuned} extends {@link StatelessSourceNoteAudioAdapter}
 * to provide a {@link PatternNoteAudio} implementation with frequency tuned via a
 * {@link KeyboardTuning}.
 *
 * @author  Michael Murray
 */
public class StatelessSourceNoteAudioTuned extends StatelessSourceNoteAudioAdapter
											implements KeyboardTuned {
	private KeyboardTuning tuning;

	public StatelessSourceNoteAudioTuned(StatelessSource source, double duration) {
		this(source, new BufferDetails(OutputLine.sampleRate, duration));
	}

	public StatelessSourceNoteAudioTuned(StatelessSource source,
										 BufferDetails buffer) {
		this(source, buffer, new IdentityFactor<>());
	}

	public StatelessSourceNoteAudioTuned(StatelessSource source,
										 BufferDetails buffer,
										 Factor<PackedCollection> params) {
		super(source, buffer, params);
	}

	@Override
	public void setTuning(KeyboardTuning tuning) { this.tuning = tuning; }

	/**
	 * Returns the frequency of the note at the given {@link KeyPosition} in
	 * hertz as determined by the {@link KeyboardTuning}.
	 *
	 * @see #setTuning(KeyboardTuning) 
	 */
	@Override
	public Factor<PackedCollection> getFrequency(KeyPosition<?> target,
													  Factor<PackedCollection> automationLevel) {
		return time -> c(tuning.getTone(target).asHertz());
	}
}
