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

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.almostrealism.relation.Factor;
import org.almostrealism.audio.tone.KeyPosition;
import org.almostrealism.audio.tone.KeyboardTuned;
import org.almostrealism.audio.tone.KeyboardTuning;
import org.almostrealism.collect.PackedCollection;

import java.util.Objects;
import java.util.function.DoubleFunction;

/**
 * A {@link PatternNoteAudio} layer that wraps a delegate with a {@link NoteAudioFilter}.
 *
 * <p>Used to apply an envelope or other filter to note audio as part of the layered
 * pattern rendering pipeline. The delegate provides the raw audio, and the filter
 * shapes it before output.</p>
 *
 * @see PatternNoteAudio
 * @see PatternNoteAudioAdapter
 */
public class PatternNoteLayer extends PatternNoteAudioAdapter implements KeyboardTuned {

	/** The underlying note audio source. */
	private final PatternNoteAudio delegate;

	/** The audio filter applied to the delegate's output. */
	private final NoteAudioFilter filter;

	/** Creates an uninitialized {@code PatternNoteLayer}. */
	public PatternNoteLayer() { this(null, null); }

	/**
	 * Creates a {@code PatternNoteLayer} wrapping the given delegate with the given filter.
	 *
	 * @param delegate the underlying note audio source
	 * @param filter   the audio filter to apply
	 */
	protected PatternNoteLayer(PatternNoteAudio delegate, NoteAudioFilter filter) {
		this.delegate = delegate;
		this.filter = filter;
	}

	@JsonIgnore
	@Override
	public void setTuning(KeyboardTuning tuning) {
		if (delegate instanceof KeyboardTuned d) {
			d.setTuning(tuning);
		}
	}

	@Override
	protected PatternNoteAudio getDelegate() { return delegate; }

	@Override
	protected NoteAudioFilter getFilter() { return filter; }

	@Override
	protected PatternNoteAudio getProvider(KeyPosition<?> target,
										   DoubleFunction<PatternNoteAudio> audioSelection) {
		throw new UnsupportedOperationException();
	}

	/**
	 * Returns null, as this layer does not provide raw audio directly.
	 *
	 * @return always null
	 */
	@JsonIgnore
	public PackedCollection getAudio() {
		if (delegate != null) {
			warn("Attempting to get audio from a delegated PatternNote");
		}

		return null;
	}

	@Override
	public boolean equals(Object obj) {
		if (obj instanceof PatternNoteLayer other) {

			return Objects.equals(delegate, other.delegate) &&
					Objects.equals(filter, other.filter);
		}

		return false;
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(delegate);
	}

	/**
	 * Creates a {@code PatternNoteLayer} wrapping the given delegate with the given filter.
	 *
	 * @param delegate the underlying note audio source
	 * @param filter   the audio filter to apply
	 * @return a new {@code PatternNoteLayer}
	 */
	public static PatternNoteLayer create(PatternNoteAudio delegate, NoteAudioFilter filter) {
		return new PatternNoteLayer(delegate, filter);
	}

	/**
	 * Creates a {@code PatternNoteLayer} wrapping the given delegate with a filter
	 * derived from the given factor.
	 *
	 * @param delegate the underlying note audio source
	 * @param factor   the factor to convert to an audio filter
	 * @return a new {@code PatternNoteLayer}
	 */
	public static PatternNoteLayer create(PatternNoteAudio delegate, Factor<PackedCollection> factor) {
		return new PatternNoteLayer(delegate, (audio, duration, automationLevel) -> factor.getResultant(audio));
	}
}
