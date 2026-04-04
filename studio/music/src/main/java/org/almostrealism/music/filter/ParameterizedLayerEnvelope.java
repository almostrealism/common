/*
 * Copyright 2024 Michael Murray
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
import org.almostrealism.audio.filter.EnvelopeFeatures;
import org.almostrealism.audio.filter.AudioProcessingUtils;

import io.almostrealism.relation.Producer;
import org.almostrealism.music.data.ChannelInfo;
import org.almostrealism.music.data.ParameterSet;
import org.almostrealism.audio.notes.NoteAudioFilter;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DynamicCollectionProducer;

import java.util.List;

/**
 * A {@link ParameterizedEnvelope} for a single layer within a {@link ParameterizedEnvelopeLayers}.
 *
 * <p>Delegates all parameter lookups to the parent {@link ParameterizedEnvelopeLayers}
 * for the specific layer index, enabling per-layer ADSR values within a shared parameter set.</p>
 *
 * @see ParameterizedEnvelopeLayers
 */
public class ParameterizedLayerEnvelope implements ParameterizedEnvelope {

	/** The parent layers configuration that provides the parameter functions. */
	private final ParameterizedEnvelopeLayers parent;

	/** The zero-based layer index this envelope controls. */
	private final int layer;

	/**
	 * Creates a {@code ParameterizedLayerEnvelope} for the given layer in the given parent.
	 *
	 * @param parent the layers configuration
	 * @param layer  the zero-based layer index
	 */
	public ParameterizedLayerEnvelope(ParameterizedEnvelopeLayers parent, int layer) {
		this.parent = parent;
		this.layer = layer;
	}

	@Override
	public NoteAudioFilter createFilter(ParameterSet params, ChannelInfo.Voicing voicing) {
		return new Filter(params, voicing);
	}

	/**
	 * A concrete {@link NoteAudioFilter} that applies a per-layer multi-stage envelope.
	 */
	public class Filter implements NoteAudioFilter, EnvelopeFeatures {
		/** The parameter set controlling envelope values. */
		private final ParameterSet params;

		/** The signal path voicing. */
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

		/** Returns the computed attack duration from the parent layers. */
		public double getAttack() {
			return parent.getAttack(layer, params);
		}

		/** Returns the computed sustain duration from the parent layers. */
		public double getSustain() {
			return parent.getSustain(layer, params);
		}

		/** Returns the computed release duration from the parent layers. */
		public double getRelease() {
			return parent.getRelease(layer, params);
		}

		/** Returns the volume for audio source 0. */
		public double getVolume0() {
			return parent.getVolume(layer, 0, params);
		}

		/** Returns the volume for audio source 1. */
		public double getVolume1() {
			return parent.getVolume(layer, 1, params);
		}

		/** Returns the volume for audio source 2. */
		public double getVolume2() {
			return parent.getVolume(layer, 2, params);
		}

		/** Returns the volume for audio source 3. */
		public double getVolume3() {
			return parent.getVolume(layer, 3, params);
		}

		@Override
		public Producer<PackedCollection> apply(Producer<PackedCollection> audio,
												   Producer<PackedCollection> duration,
												   Producer<PackedCollection> automationLevel) {
			PackedCollection d0 = new PackedCollection(1);
			d0.set(0, getAttack());

			PackedCollection d1 = new PackedCollection(1);
			d1.set(0, getSustain());

			PackedCollection d2 = new PackedCollection(1);
			d2.set(0, getRelease());

			PackedCollection v0 = new PackedCollection(1);
			v0.set(0, getVolume0());

			PackedCollection v1 = new PackedCollection(1);
			v1.set(0, getVolume1());

			PackedCollection v2 = new PackedCollection(1);
			v2.set(0, getVolume2());

			PackedCollection v3 = new PackedCollection(1);
			v3.set(0, getVolume3());

			return new DynamicCollectionProducer(shape(audio), args -> {
				PackedCollection audioData = audio.get().evaluate();
				PackedCollection dr = duration.get().evaluate();

				PackedCollection out = AudioProcessingUtils.getLayerEnv()
						.evaluate(audioData.traverse(1), dr, d0, d1, d2, v0, v1, v2, v3);

				if (out.getShape().getTotalSize() == 1) {
					warn("Envelope produced a value with shape " +
							out.getShape().toStringDetail());
				}

				return out;
			}, false);
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) return true;
			if (obj == null || getClass() != obj.getClass()) return false;
			ParameterizedLayerEnvelope.Filter filter = (ParameterizedLayerEnvelope.Filter) obj;

			if (filter.getAttack() != getAttack()) return false;
			if (filter.getSustain() != getSustain()) return false;
			if (filter.getRelease() != getRelease()) return false;
			if (filter.getVolume0() != getVolume0()) return false;
			if (filter.getVolume1() != getVolume1()) return false;
			if (filter.getVolume2() != getVolume2()) return false;
			return filter.getVolume3() == getVolume3();
		}

		@Override
		public int hashCode() {
			return List.of(getAttack(), getSustain(), getRelease()).hashCode();
		}
	}
}
