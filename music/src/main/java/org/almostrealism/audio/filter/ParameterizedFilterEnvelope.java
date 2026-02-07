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

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
import org.almostrealism.audio.data.ChannelInfo;
import org.almostrealism.audio.data.ParameterFunction;
import org.almostrealism.audio.data.ParameterSet;
import org.almostrealism.audio.notes.NoteAudioFilter;
import org.almostrealism.collect.PackedCollection;

import java.util.List;

public class ParameterizedFilterEnvelope extends ParameterizedEnvelopeAdapter {
	public static double adjustmentBase = 0.8;
	public static double adjustmentAutomation = 0.5;

	private Mode mode;

	public ParameterizedFilterEnvelope() {
		super();
		mode = Mode.STANDARD_NOTE;
	}

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

		public double getAttack() {
			return mode.getMaxAttack(getVoicing()) * getAttackSelection().positive().apply(params);
		}

		public double getDecay() {
			return mode.getMaxDecay(getVoicing()) * getDecaySelection().positive().apply(params);
		}

		public double getSustain() {
			return mode.getMaxSustain(getVoicing()) * getSustainSelection().positive().apply(params);
		}

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

	public enum Mode {
		STANDARD_NOTE, NOTE_LAYER;

		public double getMaxAttack(ChannelInfo.Voicing voicing) {
			switch (this) {
				case NOTE_LAYER:
					return 0.5;
				case STANDARD_NOTE:
				default:
					return voicing == ChannelInfo.Voicing.WET ? 0.3 : 0.1;
			}
		}

		public double getMaxDecay(ChannelInfo.Voicing voicing) {
			switch (this) {
				case NOTE_LAYER:
					return 0.5;
				case STANDARD_NOTE:
				default:
					return 0.05;
			}
		}

		public double getMaxSustain(ChannelInfo.Voicing voicing) {
			switch (this) {
				case NOTE_LAYER:
					return 1.0;
				case STANDARD_NOTE:
				default:
					return voicing == ChannelInfo.Voicing.WET ? 0.3 : 0.2;
			}
		}

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

	public static ParameterizedFilterEnvelope random(Mode mode) {
		return new ParameterizedFilterEnvelope(mode,
				ParameterFunction.random(), ParameterFunction.random(),
				ParameterFunction.random(), ParameterFunction.random());
	}
}
