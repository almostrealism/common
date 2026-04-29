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

package org.almostrealism.music.filter;
import org.almostrealism.audio.filter.EnvelopeProcessor;
import org.almostrealism.audio.filter.AudioProcessingUtils;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
import org.almostrealism.music.data.ChannelInfo;
import org.almostrealism.music.data.ParameterFunction;
import org.almostrealism.music.data.ParameterSet;
import org.almostrealism.audio.notes.NoteAudioFilter;
import org.almostrealism.collect.PackedCollection;

import java.util.List;

/**
 * A parameterized filter (frequency) envelope that shapes note audio using an ADSR curve.
 *
 * <p>The envelope modulates a low-pass or band-pass filter cutoff frequency over the
 * duration of a note. The ADSR parameters are selected from a {@link ParameterSet} using
 * the inherited selection functions, then scaled by the {@link Mode}-specific maximum values.</p>
 *
 * @see ParameterizedVolumeEnvelope
 * @see ParameterizedEnvelopeAdapter
 */
public class ParameterizedFilterEnvelope extends ParameterizedEnvelopeAdapter {
	/** Base automation adjustment factor. */
	public static double adjustmentBase = 0.8;

	/** Automation scaling factor for the sustain/release adjustment. */
	public static double adjustmentAutomation = 0.5;

	/** The operating mode that sets maximum envelope values. */
	private Mode mode;

	/** Creates a {@code ParameterizedFilterEnvelope} in {@link Mode#STANDARD_NOTE} mode. */
	public ParameterizedFilterEnvelope() {
		super();
		mode = Mode.STANDARD_NOTE;
	}

	/**
	 * Creates a {@code ParameterizedFilterEnvelope} with the given mode and ADSR selection functions.
	 *
	 * @param mode             the operating mode
	 * @param attackSelection  function selecting the attack duration
	 * @param decaySelection   function selecting the decay duration
	 * @param sustainSelection function selecting the sustain level
	 * @param releaseSelection function selecting the release duration
	 */
	public ParameterizedFilterEnvelope(Mode mode, ParameterFunction attackSelection, ParameterFunction decaySelection,
									   ParameterFunction sustainSelection, ParameterFunction releaseSelection) {
		super(attackSelection, decaySelection, sustainSelection, releaseSelection);
		this.mode = mode;
	}

	public Mode getMode() { return mode; }

	public void setMode(Mode mode) { this.mode = mode; }

	@Override
	public NoteAudioFilter createFilter(ParameterSet params, ChannelInfo.Voicing voicing) {
		return new Filter(params, voicing);
	}

	/**
	 * A concrete {@link NoteAudioFilter} implementation that applies a filter envelope to audio.
	 */
	public class Filter implements NoteAudioFilter {
		/** The parameter set controlling envelope values. */
		private final ParameterSet params;

		/** The signal path voicing used to select mode-specific maximum values. */
		private final ChannelInfo.Voicing voicing;

		/**
		 * Creates a Filter with the given parameters and voicing.
		 *
		 * @param params the parameter set
		 * @param voicing the signal path voicing
		 */
		public Filter(ParameterSet params, ChannelInfo.Voicing voicing) {
			this.params = params;
			this.voicing = voicing;
		}

		/** Returns the signal path voicing used by this filter. */
		public ChannelInfo.Voicing getVoicing() {
			return voicing;
		}

		/** Returns the computed attack duration in seconds. */
		public double getAttack() {
			return mode.getMaxAttack(getVoicing()) * getAttackSelection().positive().apply(params);
		}

		/** Returns the computed decay duration in seconds. */
		public double getDecay() {
			return mode.getMaxDecay(getVoicing()) * getDecaySelection().positive().apply(params);
		}

		/** Returns the computed sustain level. */
		public double getSustain() {
			return mode.getMaxSustain(getVoicing()) * getSustainSelection().positive().apply(params);
		}

		/** Returns the computed release duration in seconds. */
		public double getRelease() {
			return mode.getMaxRelease(getVoicing()) * getReleaseSelection().positive().apply(params);
		}

		@Override
		public Producer<PackedCollection> apply(Producer<PackedCollection> audio,
												   Producer<PackedCollection> duration,
												   Producer<PackedCollection> automationLevel) {
			return () -> args -> {
				PackedCollection audioData = audio.get().evaluate();

				TraversalPolicy shape = audioData.getShape();
				PackedCollection result = PackedCollection.factory()
						.apply(shape.getTotalSize()).reshape(shape);
				PackedCollection dr = duration.get().evaluate();
				PackedCollection al = automationLevel.get().evaluate();

				double adj = adjustmentBase + adjustmentAutomation * al.toDouble(0);
//				log("Processing filter envelope with duration (" + dr.toDouble(0) +
//						", attack: " + getAttack() + ", decay: " + getDecay() +
//						", sustain: " + getSustain() * adj +
//						", release: " + getRelease() * adj + ")");

				EnvelopeProcessor processor = AudioProcessingUtils.getFilterEnv();
				processor.setDuration(dr.toDouble(0));
				processor.setAttack(getAttack());
				processor.setDecay(getDecay());
				processor.setSustain(getSustain() * adj);
				processor.setRelease(getRelease() * adj);
				processor.process(audioData, result);
				return result;
			};
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) return true;
			if (obj == null || getClass() != obj.getClass()) return false;
			Filter filter = (Filter) obj;

			if (filter.getAttack() != getAttack()) return false;
			if (filter.getDecay() != getDecay()) return false;
			if (filter.getSustain() != getSustain()) return false;
			return filter.getRelease() == getRelease();
		}

		@Override
		public int hashCode() {
			return List.of(getAttack(), getDecay(), getSustain(), getRelease()).hashCode();
		}
	}

	/**
	 * Defines the operating mode that sets the maximum ADSR envelope values.
	 */
	public enum Mode {
		/** Mode for standard (single) notes with shorter envelope times. */
		STANDARD_NOTE,
		/** Mode for note layers with longer envelope times. */
		NOTE_LAYER;

		/**
		 * Returns the maximum attack duration for this mode and voicing.
		 *
		 * @param voicing the signal path voicing
		 * @return the maximum attack in seconds
		 */
		public double getMaxAttack(ChannelInfo.Voicing voicing) {
			switch (this) {
				case NOTE_LAYER:
					return 0.5;
				case STANDARD_NOTE:
				default:
					return voicing == ChannelInfo.Voicing.WET ? 0.3 : 0.1;
			}
		}

		/**
		 * Returns the maximum decay duration for this mode.
		 *
		 * @param voicing the signal path voicing (unused in current implementation)
		 * @return the maximum decay in seconds
		 */
		public double getMaxDecay(ChannelInfo.Voicing voicing) {
			switch (this) {
				case NOTE_LAYER:
					return 0.5;
				case STANDARD_NOTE:
				default:
					return 0.05;
			}
		}

		/**
		 * Returns the maximum sustain level for this mode and voicing.
		 *
		 * @param voicing the signal path voicing
		 * @return the maximum sustain level
		 */
		public double getMaxSustain(ChannelInfo.Voicing voicing) {
			switch (this) {
				case NOTE_LAYER:
					return 1.0;
				case STANDARD_NOTE:
				default:
					return voicing == ChannelInfo.Voicing.WET ? 0.3 : 0.2;
			}
		}

		/**
		 * Returns the maximum release duration for this mode and voicing.
		 *
		 * @param voicing the signal path voicing
		 * @return the maximum release in seconds
		 */
		public double getMaxRelease(ChannelInfo.Voicing voicing) {
			switch (this) {
				case NOTE_LAYER:
					return 6.0;
				case STANDARD_NOTE:
				default:
					return voicing == ChannelInfo.Voicing.WET ? 8.0 : 5.0;
			}
		}
	}

	/**
	 * Creates a randomly initialized {@code ParameterizedFilterEnvelope} for the given mode.
	 *
	 * @param mode the operating mode
	 * @return a new randomly initialized instance
	 */
	public static ParameterizedFilterEnvelope random(Mode mode) {
		return new ParameterizedFilterEnvelope(mode,
				ParameterFunction.random(), ParameterFunction.random(),
				ParameterFunction.random(), ParameterFunction.random());
	}
}
