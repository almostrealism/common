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
import org.almostrealism.audio.filter.AudioProcessingUtils;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.almostrealism.relation.Producer;
import org.almostrealism.music.data.ChannelInfo;
import org.almostrealism.music.data.ParameterFunction;
import org.almostrealism.music.data.ParameterSet;
import org.almostrealism.audio.notes.NoteAudioFilter;
import org.almostrealism.collect.PackedCollection;

import java.util.List;

/**
 * A parameterized volume envelope that shapes the amplitude of note audio using an ADSR curve.
 *
 * <p>The envelope applies an ADSR amplitude shape to the note audio. The ADSR parameters
 * are selected from a {@link ParameterSet} using the inherited selection functions,
 * then scaled by the {@link Mode}-specific maximum values and adjusted by the automation level.</p>
 *
 * @see ParameterizedFilterEnvelope
 * @see ParameterizedEnvelopeAdapter
 */
public class ParameterizedVolumeEnvelope extends ParameterizedEnvelopeAdapter {
	/** Base automation adjustment factor. */
	public static double adjustmentBase = 0.8;

	/** Automation scaling factor for the sustain and release adjustment. */
	public static double adjustmentAutomation = 0.01;

	/** The operating mode that sets maximum envelope values. */
	private Mode mode;

	/** Creates a {@code ParameterizedVolumeEnvelope} in {@link Mode#STANDARD_NOTE} mode. */
	public ParameterizedVolumeEnvelope() {
		super();
		mode = Mode.STANDARD_NOTE;
	}

	/**
	 * Creates a {@code ParameterizedVolumeEnvelope} with the given mode and ADSR selection functions.
	 *
	 * @param mode             the operating mode
	 * @param attackSelection  function selecting the attack duration
	 * @param decaySelection   function selecting the decay duration
	 * @param sustainSelection function selecting the sustain level
	 * @param releaseSelection function selecting the release duration
	 */
	public ParameterizedVolumeEnvelope(Mode mode, ParameterFunction attackSelection, ParameterFunction decaySelection,
									   ParameterFunction sustainSelection, ParameterFunction releaseSelection) {
		super(attackSelection, decaySelection, sustainSelection, releaseSelection);
		this.mode = mode;
	}

	/** Returns the operating mode for this envelope. */
	public Mode getMode() {
		return mode;
	}

	/** Sets the operating mode for this envelope. */
	public void setMode(Mode mode) {
		this.mode = mode;
	}

	@Override
	public NoteAudioFilter createFilter(ParameterSet params, ChannelInfo.Voicing voicing) {
		return new Filter(params, voicing);
	}

	@JsonIgnore
	@Override
	public Class getLogClass() {
		return super.getLogClass();
	}

	/**
	 * A concrete {@link NoteAudioFilter} that applies a volume envelope to audio.
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

		/**
		 * Returns the computed attack duration, scaled by the total note duration.
		 *
		 * @param totalDuration the total duration of the note in seconds
		 * @return the attack duration in seconds
		 */
		public double getAttack(double totalDuration) {
			return mode.getMaxAttack(getVoicing(), totalDuration) * getAttackSelection().positive().apply(params);
		}

		/** Returns the computed decay duration in seconds. */
		public double getDecay() {
			return mode.getMaxDecay(getVoicing()) * getDecaySelection().positive().apply(params);
		}

		/** Returns the computed sustain level. */
		public double getSustain() {
			return mode.getMaxSustain(getVoicing()) * getSustainSelection().positive().apply(params);
		}

		/**
		 * Returns the computed release duration, scaled by the total note duration.
		 *
		 * @param totalDuration the total duration of the note in seconds
		 * @return the release duration in seconds
		 */
		public double getRelease(double totalDuration) {
			return mode.getMaxRelease(getVoicing(), totalDuration) * getReleaseSelection().positive().apply(params);
		}

		@Override
		public Producer<PackedCollection> apply(Producer<PackedCollection> audio,
												   Producer<PackedCollection> duration,
												   Producer<PackedCollection> automationLevel) {
			PackedCollection a = new PackedCollection(1);
			PackedCollection d = new PackedCollection(1);
			PackedCollection s = new PackedCollection(1);
			PackedCollection r = new PackedCollection(1);

			return () -> args -> {
				PackedCollection audioData = audio.get().evaluate();
				PackedCollection dr = duration.get().evaluate();
				PackedCollection al = automationLevel.get().evaluate();

				double dv = dr.toDouble(0);
				double adj = adjustmentBase + adjustmentAutomation * al.toDouble(0);

				double sustain = getSustain();
				if (sustain > getMode().getMaxSustain(getVoicing())) {
					throw new IllegalArgumentException();
				}

				sustain = sustain * adj;

				if (sustain < 0.25) {
					sustain = 0.25;
				} else if (sustain > 1.0) {
					sustain = 1.0;
				}

				double release = getRelease(dv);
				if (release > getMode().getMaxRelease(getVoicing(), dv)) {
					throw new IllegalArgumentException();
				}

				release = release * adj;
				if (release > 0.7 * dv) {
					release = 0.7 * dv;
				}

//				log("Processing volume envelope with duration (" + dr.toDouble(0) +
//						", attack: " + getAttack(dr.toDouble()) + ", decay: " + getDecay() +
//						", sustain: " + sustain +
//						", release: " + sustain +
//						" | a:adj = " + al.toDouble(0) + ":" + adj + ")");
				a.set(0, getAttack(dr.toDouble()));
				d.set(0, getDecay());
				s.set(0, sustain);
				r.set(0, release);

				PackedCollection out = AudioProcessingUtils.getVolumeEnv()
						.evaluate(audioData.traverse(1), dr, a, d, s, r);

				if (out.getShape().getTotalSize() == 1) {
					warn("Envelope produced a value with shape " +
							out.getShape().toStringDetail());
				}

				return out;
			};
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) return true;
			if (obj == null || getClass() != obj.getClass()) return false;
			Filter filter = (Filter) obj;

			if (filter.getAttack(10.0) != getAttack(10.0)) return false;
			if (filter.getDecay() != getDecay()) return false;
			if (filter.getSustain() != getSustain()) return false;
			return filter.getRelease(10.0) == getRelease(10.0);
		}

		@Override
		public int hashCode() {
			return List.of(getAttack(10.0), getDecay(), getSustain(), getRelease(10.0)).hashCode();
		}
	}

	/**
	 * Defines the operating mode that sets the maximum ADSR envelope values.
	 */
	public enum Mode {
		/** Mode for standard (single) notes with shorter, duration-proportional envelope times. */
		STANDARD_NOTE,
		/** Mode for note layers with longer fixed envelope times. */
		NOTE_LAYER;

		/**
		 * Returns the maximum attack duration for this mode and voicing.
		 *
		 * @param voicing the signal path voicing
		 * @param totalDuration the total note duration in seconds
		 * @return the maximum attack in seconds
		 */
		public double getMaxAttack(ChannelInfo.Voicing voicing, double totalDuration) {
			return switch (this) {
				case NOTE_LAYER -> 2.0;
				default -> (voicing == ChannelInfo.Voicing.WET ? 1.0 : 0.2) * totalDuration;
			};
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
					return 3.0;
				case STANDARD_NOTE:
				default:
					return 2.0;
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
					return 2.0;
				case STANDARD_NOTE:
				default:
					return voicing == ChannelInfo.Voicing.WET ? 1.0 : 0.8;
			}
		}

		/**
		 * Returns the maximum release duration for this mode and voicing.
		 *
		 * @param voicing the signal path voicing
		 * @param totalDuration the total note duration in seconds
		 * @return the maximum release in seconds
		 */
		public double getMaxRelease(ChannelInfo.Voicing voicing, double totalDuration) {
			switch (this) {
				case NOTE_LAYER:
					return 2.0;
				case STANDARD_NOTE:
				default:
					return (voicing == ChannelInfo.Voicing.WET ? 0.7 : 0.2) * totalDuration;
			}
		}
	}

	/**
	 * Creates a randomly initialized {@code ParameterizedVolumeEnvelope} for the given mode.
	 *
	 * @param mode the operating mode
	 * @return a new randomly initialized instance
	 */
	public static ParameterizedVolumeEnvelope random(Mode mode) {
		return new ParameterizedVolumeEnvelope(mode,
				ParameterFunction.random(), ParameterFunction.random(),
				ParameterFunction.random(), ParameterFunction.random());
	}
}
