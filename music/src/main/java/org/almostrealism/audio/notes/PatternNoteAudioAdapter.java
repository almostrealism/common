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
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.SamplingFeatures;
import org.almostrealism.audio.tone.KeyPosition;
import org.almostrealism.collect.PackedCollection;

import java.util.function.DoubleFunction;

/** The PatternNoteAudioAdapter class. */
public abstract class PatternNoteAudioAdapter implements
		PatternNoteAudio, CellFeatures, SamplingFeatures {

	@Override
	public int getSampleRate(KeyPosition<?> target, DoubleFunction<PatternNoteAudio> audioSelection) {
		if (getDelegate() != null) return getDelegate().getSampleRate(target, audioSelection);
		return getProvider(target, audioSelection).getSampleRate(target, audioSelection);
	}

	@Override
	public double getDuration(KeyPosition<?> target, DoubleFunction<PatternNoteAudio> audioSelection) {
		if (getDelegate() != null) return getDelegate().getDuration(target, audioSelection);

		PatternNoteAudio provider = getProvider(target, audioSelection);
		if (provider == null) {
			warn("No provider for " + target);
			return 0.0;
		}

		return getProvider(target, audioSelection).getDuration(target, audioSelection);
	}

	@Override
	public Producer<PackedCollection> getAudio(KeyPosition<?> target, int channel, double noteDuration,
												  Factor<PackedCollection> automationLevel,
												  DoubleFunction<PatternNoteAudio> audioSelection) {
		return computeAudio(target, channel, noteDuration, automationLevel, audioSelection);
	}

	@Override
	public Producer<PackedCollection> getAudio(KeyPosition<?> target, int channel,
												  DoubleFunction<PatternNoteAudio> audioSelection) {
		if (getDelegate() != null) {
			warn("Loading audio from delegate without note duration, filter will be skipped");
			return getDelegate().getAudio(target, channel, audioSelection);
		}

		return getProvider(target, audioSelection).getAudio(target, channel, audioSelection);
	}

	/** Performs the computeAudio operation. */
	protected Producer<PackedCollection> computeAudio(KeyPosition<?> target, int channel,
														 double noteDuration,
														 Factor<PackedCollection> automationLevel,
														 DoubleFunction<PatternNoteAudio> audioSelection) {
		if (getDelegate() == null) {
			PatternNoteAudio p = getProvider(target, audioSelection);
			if (p == null) {
				throw new UnsupportedOperationException();
			}

			return p.getAudio(target, channel, audioSelection);
		} else if (noteDuration > 0) {
			return sampling(getSampleRate(target, audioSelection), getDuration(target, audioSelection),
					() -> getFilter().apply(getDelegate()
									.getAudio(target, channel, noteDuration,
											automationLevel, audioSelection),
												c(noteDuration), automationLevel.getResultant(c(0.0))));
		} else {
			throw new UnsupportedOperationException();
		}
	}

	/** Performs the getDelegate operation. */
	protected abstract PatternNoteAudio getDelegate();

	/** Performs the getFilter operation. */
	protected abstract NoteAudioFilter getFilter();

	/** Performs the getProvider operation. */
	protected abstract PatternNoteAudio getProvider(KeyPosition<?> target,
													DoubleFunction<PatternNoteAudio> audioSelection);
}
