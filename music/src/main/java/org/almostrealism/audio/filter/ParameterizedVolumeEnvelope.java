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

package org.almostrealism.audio.filter;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.almostrealism.relation.Producer;
import org.almostrealism.audio.data.ChannelInfo;
import org.almostrealism.audio.data.ParameterFunction;
import org.almostrealism.audio.data.ParameterSet;
import org.almostrealism.audio.notes.NoteAudioFilter;
import org.almostrealism.collect.PackedCollection;

import java.util.List;

public class ParameterizedVolumeEnvelope extends ParameterizedEnvelopeAdapter {
	public static double adjustmentBase = 0.8;
	public static double adjustmentAutomation = 0.01;

	private Mode mode;

	public ParameterizedVolumeEnvelope() {
		super();
		mode = Mode.STANDARD_NOTE;
	}

	public ParameterizedVolumeEnvelope(Mode mode, ParameterFunction attackSelection, ParameterFunction decaySelection,
									   ParameterFunction sustainSelection, ParameterFunction releaseSelection) {
		super(attackSelection, decaySelection, sustainSelection, releaseSelection);
		this.mode = mode;
	}

	public Mode getMode() {
		return mode;
	}

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

	public class Filter implements NoteAudioFilter {
		private final ParameterSet params;
		private final ChannelInfo.Voicing voicing;

		public Filter(ParameterSet params, ChannelInfo.Voicing voicing) {
			this.params = params;
			this.voicing = voicing;
		}

		public ChannelInfo.Voicing getVoicing() {
			return voicing;
		}

		public double getAttack(double totalDuration) {
			return mode.getMaxAttack(getVoicing(), totalDuration) * getAttackSelection().positive().apply(params);
		}

		public double getDecay() {
			return mode.getMaxDecay(getVoicing()) * getDecaySelection().positive().apply(params);
		}

		public double getSustain() {
			return mode.getMaxSustain(getVoicing()) * getSustainSelection().positive().apply(params);
		}

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

	public enum Mode {
		STANDARD_NOTE, NOTE_LAYER;

		public double getMaxAttack(ChannelInfo.Voicing voicing, double totalDuration) {
			return switch (this) {
				case NOTE_LAYER -> 2.0;
				default -> (voicing == ChannelInfo.Voicing.WET ? 1.0 : 0.2) * totalDuration;
			};
		}

		public double getMaxDecay(ChannelInfo.Voicing voicing) {
			switch (this) {
				case NOTE_LAYER:
					return 3.0;
				case STANDARD_NOTE:
				default:
					return 2.0;
			}
		}

		public double getMaxSustain(ChannelInfo.Voicing voicing) {
			switch (this) {
				case NOTE_LAYER:
					return 2.0;
				case STANDARD_NOTE:
				default:
					return voicing == ChannelInfo.Voicing.WET ? 1.0 : 0.8;
			}
		}

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

	public static ParameterizedVolumeEnvelope random(Mode mode) {
		return new ParameterizedVolumeEnvelope(mode,
				ParameterFunction.random(), ParameterFunction.random(),
				ParameterFunction.random(), ParameterFunction.random());
	}
}
