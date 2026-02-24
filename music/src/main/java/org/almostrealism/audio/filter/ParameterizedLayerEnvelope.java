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

package org.almostrealism.audio.filter;

import io.almostrealism.relation.Producer;
import org.almostrealism.audio.data.ChannelInfo;
import org.almostrealism.audio.data.ParameterSet;
import org.almostrealism.audio.notes.NoteAudioFilter;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DynamicCollectionProducer;

import java.util.List;

public class ParameterizedLayerEnvelope implements ParameterizedEnvelope {

	private final ParameterizedEnvelopeLayers parent;
	private final int layer;

	public ParameterizedLayerEnvelope(ParameterizedEnvelopeLayers parent, int layer) {
		this.parent = parent;
		this.layer = layer;
	}

	@Override
	public NoteAudioFilter createFilter(ParameterSet params, ChannelInfo.Voicing voicing) {
		return new Filter(params, voicing);
	}

	public class Filter implements NoteAudioFilter, EnvelopeFeatures {
		private final ParameterSet params;
		private final ChannelInfo.Voicing voicing;

		public Filter(ParameterSet params, ChannelInfo.Voicing voicing) {
			this.params = params;
			this.voicing = voicing;
		}

		public double getAttack() {
			return parent.getAttack(layer, params);
		}

		public double getSustain() {
			return parent.getSustain(layer, params);
		}

		public double getRelease() {
			return parent.getRelease(layer, params);
		}

		public double getVolume0() {
			return parent.getVolume(layer, 0, params);
		}

		public double getVolume1() {
			return parent.getVolume(layer, 1, params);
		}

		public double getVolume2() {
			return parent.getVolume(layer, 2, params);
		}

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
