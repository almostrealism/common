/*
 * Copyright 2024 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.audio.filter;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.lifecycle.Lifecycle;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.collect.PackedCollection;

import java.util.function.Supplier;

public interface AudioProcessor extends Lifecycle {
	Supplier<Runnable> process(Producer<PackedCollection> destination,
							   Producer<PackedCollection> source);

	static AudioProcessor fromWave(WaveData data, int channel) {
		PackedCollection position = new PackedCollection(1);

		return new AudioProcessor() {
			@Override
			public Supplier<Runnable> process(Producer<PackedCollection> destination, Producer<PackedCollection> source) {
				return () -> {
					Evaluable<PackedCollection> dest = destination.get();

					return () -> {
						PackedCollection out = dest.evaluate();
						int len = out.getMemLength();
						int pos = (int) position.toDouble(0);
						if (pos + len > data.getFrameCount()) {
							len = data.getFrameCount() - pos;
						}

						out.setMem(data.getChannelData(channel).range(new TraversalPolicy(len), pos).toArray());
						position.set(0, position.toDouble(0) + len);
					};
				};
			}

			@Override
			public void reset() {
				AudioProcessor.super.reset();
				position.set(0, 0.0);
			}
		};
	}
}
