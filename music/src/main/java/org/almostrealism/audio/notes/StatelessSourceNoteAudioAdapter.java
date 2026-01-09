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
import org.almostrealism.CodeFeatures;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.sources.BufferDetails;
import org.almostrealism.audio.sources.StatelessSource;
import org.almostrealism.audio.tone.KeyPosition;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.heredity.IdentityFactor;

import java.util.function.DoubleFunction;


/**
 * {@link StatelessSourceNoteAudioAdapter} provides an adapter for generating note audio
 * using a {@link StatelessSource}. It provides implementations for sample rate, duration,
 * and audio generation while allowing subclasses to define how frequency is determined.
 *
 * @author  Michael Murray
 */
public abstract class StatelessSourceNoteAudioAdapter implements PatternNoteAudio, CodeFeatures {
	private final StatelessSource source;

	private final BufferDetails buffer;
	private final Factor<PackedCollection> params;

	/**
	 * Constructs a {@link StatelessSourceNoteAudioAdapter} using the
	 * specified {@link StatelessSource} and duration.
	 *
	 * @param source   The {@link StatelessSource} for audio generation.
	 * @param duration The duration of the output audio in seconds.
	 */
	public StatelessSourceNoteAudioAdapter(StatelessSource source, double duration) {
		this(source, new BufferDetails(OutputLine.sampleRate, duration));
	}

	/**
	 * Constructs a {@link StatelessSourceNoteAudioAdapter} using the
	 * specified {@link StatelessSource} and {@link BufferDetails}.
	 *
	 * @param source The {@link StatelessSource} for audio generation.
	 * @param buffer The {@link BufferDetails} specifying sample rate
	 *               and duration information.
	 */
	public StatelessSourceNoteAudioAdapter(StatelessSource source,
										   BufferDetails buffer) {
		this(source, buffer, new IdentityFactor<>());
	}

	/**
	 * Constructs a {@link StatelessSourceNoteAudioAdapter} using the
	 * specified {@link StatelessSource}, {@link BufferDetails} and
	 * {@link Factor} for determining parameter values based on the
	 * automation level.
	 *
	 * @param source The {@link StatelessSource} for audio generation.
	 * @param buffer The {@link BufferDetails} specifying sample rate
	 *               and duration information.
	 * @param params The {@link Factor} used for customization of the
	 *               audio generation via automation.
	 */
	public StatelessSourceNoteAudioAdapter(StatelessSource source,
										   BufferDetails buffer,
										   Factor<PackedCollection> params) {
		this.source = source;
		this.buffer = buffer;
		this.params = params;
	}

	/**
	 * Obtains the frequency of the audio associated with the given {@link KeyPosition}.
	 *
	 * @param target          The {@link KeyPosition} representing the audio's target.
	 * @param automationLevel  A {@link Factor} which provides the automation level given
	 *                         the position in the note in seconds (between 0.0 and the
	 *                         duration of the note).
	 * @return A {@link Producer} that generates the frequency in hertz.
	 */
	public abstract Factor<PackedCollection> getFrequency(KeyPosition<?> target,
															 Factor<PackedCollection> automationLevel);

	@Override
	public int getSampleRate(KeyPosition<?> target,
							 DoubleFunction<PatternNoteAudio> audioSelection) {
		return buffer.getSampleRate();
	}

	@Override
	public double getDuration(KeyPosition<?> target,
							  DoubleFunction<PatternNoteAudio> audioSelection) {
		return buffer.getDuration();
	}

	@Override
	public Producer<PackedCollection> getAudio(KeyPosition<?> target,
												  int channel, DoubleFunction<PatternNoteAudio> audioSelection) {
		return source.generate(buffer,
				params.getResultant(c(1.0)),
				getFrequency(target, time -> c(0.0)));
	}

	@Override
	public Producer<PackedCollection> getAudio(KeyPosition<?> target, int channel, double noteDuration,
												  Factor<PackedCollection> automationLevel,
												  DoubleFunction<PatternNoteAudio> audioSelection) {
		return source.generate(buffer,
				params.getResultant(automationLevel.getResultant(c(0.0))),
				getFrequency(target, automationLevel));
	}
}
