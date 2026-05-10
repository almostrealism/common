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
import org.almostrealism.audio.notes.NoteAudioSourceAggregator;
import org.almostrealism.audio.notes.NoteAudioFilter;

import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.sources.BufferDetails;
import org.almostrealism.audio.tone.KeyPosition;
import org.almostrealism.audio.tone.KeyboardTuned;
import org.almostrealism.audio.tone.KeyboardTuning;
import org.almostrealism.collect.PackedCollection;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleFunction;
import java.util.stream.Collectors;

/**
 * A composite note that can contain multiple audio layers.
 *
 * <p>{@code PatternNote} is the primary implementation of {@link PatternNoteAudio} used
 * by {@link PatternElement}s. It supports two modes of operation:</p>
 * <ol>
 *   <li><strong>Delegate Mode</strong>: Wraps a single {@link PatternNoteAudio} with an optional filter</li>
 *   <li><strong>Layer Mode</strong>: Combines multiple {@link PatternNoteAudio} layers</li>
 * </ol>
 *
 * <h2>Layer Composition</h2>
 *
 * <p>In layer mode, multiple audio sources are combined using the static {@code layerAggregator}.
 * This enables rich sounds by layering different samples (e.g., attack + sustain, or
 * multiple instrument layers).</p>
 *
 * <h2>Audio Generation</h2>
 *
 * <p>The {@link #getAudio} method produces audio by:</p>
 * <ol>
 *   <li>If in delegate mode, delegating to the wrapped audio with optional filtering</li>
 *   <li>If in layer mode, combining all layers via {@link #combineLayers}</li>
 * </ol>
 *
 * <h2>Pitch Support</h2>
 *
 * <p>For melodic content, {@link #setTuning} propagates keyboard tuning to all layers
 * or the delegate, enabling pitch shifting based on the target key position.</p>
 *
 * <h2>Duration</h2>
 *
 * <p>In layer mode, the duration is the maximum of all layer durations, ensuring
 * the full sound is captured.</p>
 *
 * @see PatternNoteAudio
 * @see PatternNoteAudioAdapter
 * @see PatternElement
 * @see NoteAudioSourceAggregator
 *
 * @author Michael Murray
 */
public class PatternNote extends PatternNoteAudioAdapter {
	/** Shared aggregator used to combine multiple audio layers into a single note. */
	private static final NoteAudioSourceAggregator layerAggregator;

	static {
		layerAggregator = new NoteAudioSourceAggregator();
	}

	/** The delegate note audio used in delegate mode. */
	private PatternNoteAudio delegate;

	/** The filter applied to the delegate in delegate mode. */
	private NoteAudioFilter filter;

	/** The list of layers combined in layer mode. */
	private List<PatternNoteAudio> layers;

	/** The aggregation choice value passed to the layer aggregator. */
	private double aggregationChoice;

	/** Creates an uninitialized {@code PatternNote}. */
	public PatternNote() { }

	/**
	 * Creates a {@code PatternNote} in layer mode with the given layers.
	 *
	 * @param layers the list of layers to combine
	 */
	public PatternNote(List<PatternNoteAudio> layers) {
		this.layers = layers;
	}

	/**
	 * Creates a {@code PatternNote} in layer mode using the given audio selection values.
	 *
	 * @param noteAudioSelections the selection values for each layer
	 */
	public PatternNote(double... noteAudioSelections) {
		this(new ArrayList<>());

		for (double noteAudioSelection : noteAudioSelections) {
			addLayer(noteAudioSelection);
		}
	}

	/**
	 * Creates a {@code PatternNote} in delegate mode wrapping the given delegate with a filter.
	 *
	 * @param delegate the underlying note audio
	 * @param filter   the audio filter to apply
	 */
	public PatternNote(PatternNoteAudio delegate, NoteAudioFilter filter) {
		this.delegate = delegate;
		this.filter = filter;
	}

	/**
	 * Adds a layer using the given audio selection value.
	 *
	 * @param noteAudioSelection the selection value for the new layer
	 */
	public void addLayer(double noteAudioSelection) {
		layers.add(new PatternNoteAudioChoice(noteAudioSelection));
	}

	/** Returns the list of layers in layer mode. */
	public List<PatternNoteAudio> getLayers() {
		return layers;
	}

	/** Returns the aggregation choice value. */
	public double getAggregationChoice() {
		return aggregationChoice;
	}

	/** Sets the aggregation choice value. */
	public void setAggregationChoice(double aggregationChoice) {
		this.aggregationChoice = aggregationChoice;
	}

	/**
	 * Returns the list of audio providers for the given target and selection function.
	 *
	 * @param target         the key position target
	 * @param audioSelection function mapping a double to a {@link PatternNoteAudio}
	 * @return the list of providers
	 */
	public List<PatternNoteAudio> getProviders(KeyPosition<?> target, DoubleFunction<PatternNoteAudio> audioSelection) {
		if (delegate instanceof PatternNote)
			return ((PatternNote) delegate).getProviders(target, audioSelection);

		return layers.stream()
				.filter(l -> l instanceof PatternNoteLayer)
				.map(l -> ((PatternNoteLayer) l).getProvider(target, audioSelection))
				.collect(Collectors.toList());
	}

	/**
	 * Propagates the keyboard tuning to all layers or the delegate.
	 *
	 * @param tuning the keyboard tuning to apply
	 */
	public void setTuning(KeyboardTuning tuning) {
		if (delegate == null) {
			layers.forEach(l -> {
				if (l instanceof KeyboardTuned tuned) tuned.setTuning(tuning);
			});
		} else if (delegate instanceof KeyboardTuned) {
			((KeyboardTuned) delegate).setTuning(tuning);
		}
	}

	@Override
	protected PatternNoteAudio getDelegate() {
		return delegate;
	}

	@Override
	protected NoteAudioFilter getFilter() {
		return filter;
	}

	@Override
	public double getDuration(KeyPosition<?> target, DoubleFunction<PatternNoteAudio> audioSelection) {
		if (delegate != null) return delegate.getDuration(target, audioSelection);
		return layers.stream().mapToDouble(l -> l.getDuration(target, audioSelection)).max().orElse(0.0);
	}

	@Override
	public int getSampleRate(KeyPosition<?> target, DoubleFunction<PatternNoteAudio> audioSelection) {
		return OutputLine.sampleRate;
	}

	@Override
	public Producer<PackedCollection> getAudio(KeyPosition<?> target, int channel,
												  DoubleFunction<PatternNoteAudio> audioSelection) {
		if (getDelegate() != null) return super.getAudio(target, channel, audioSelection);
		return combineLayers(target, channel, -1, null, audioSelection, null, -1);
	}

	@Override
	protected Producer<PackedCollection> computeAudio(KeyPosition<?> target, int channel,
														 double noteDuration,
														 Factor<PackedCollection> automationLevel,
														 DoubleFunction<PatternNoteAudio> audioSelection,
														 PackedCollection offset, int frameCount) {
		if (getDelegate() != null) {
			return super.computeAudio(
					target, channel,
					noteDuration,
					automationLevel, audioSelection,
					offset, frameCount);
		}

		return combineLayers(target, channel, noteDuration, automationLevel, audioSelection,
				offset, frameCount);
	}

	/**
	 * Combines layers to produce audio, optionally for a specific frame range.
	 *
	 * <p>When {@code offset} is non-null, a {@link BufferDetails} sized to {@code frameCount}
	 * is used so the output buffer is limited to the requested range and each layer
	 * receives the frame range parameters. When {@code offset} is null, the full note
	 * buffer is used.</p>
	 */
	protected Producer<PackedCollection> combineLayers(KeyPosition<?> target, int channel,
														  double noteDuration,
														  Factor<PackedCollection> automationLevel,
														  DoubleFunction<PatternNoteAudio> audioSelection,
														  PackedCollection offset, int frameCount) {
		if (noteDuration < 0) {
			throw new UnsupportedOperationException();
		}

		BufferDetails buffer = (offset != null)
				? new BufferDetails(getSampleRate(target, audioSelection), frameCount)
				: getBufferDetails(target, audioSelection);

		return layerAggregator.getAggregator(c(aggregationChoice)).aggregate(buffer,
				null, null,
				layers.stream()
						.map(l -> l.getAudio(target, channel, noteDuration,
								automationLevel, audioSelection,
								offset, frameCount))
						.toArray(Producer[]::new));
	}

	@Override
	protected PatternNoteAudio getProvider(KeyPosition<?> target,
										   DoubleFunction<PatternNoteAudio> audioSelection) {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean equals(Object obj) {
		if (!(obj instanceof PatternNote n)) return false;

		boolean eq;

		if (filter != null) {
			eq = filter.equals(n.filter) && delegate.equals(n.delegate);
		} else {
			eq = layers.equals(n.layers);
		}

		return eq;
	}

	@Override
	public int hashCode() {
		if (filter != null) {
			return filter.hashCode();
		} else {
			return layers.hashCode();
		}
	}
}
