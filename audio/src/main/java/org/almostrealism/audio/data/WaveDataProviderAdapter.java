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

package org.almostrealism.audio.data;

import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.expression.Product;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.CodeFeatures;
import org.almostrealism.Ops;
import org.almostrealism.audio.WaveOutput;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.PassThroughProducer;
import org.almostrealism.hardware.ctx.ContextSpecific;
import org.almostrealism.hardware.ctx.DefaultContextSpecific;
import org.almostrealism.time.computations.Interpolate;

import java.util.HashMap;
import java.util.Map;

public abstract class WaveDataProviderAdapter implements WaveDataProvider,
								Comparable<WaveDataProvider>, CodeFeatures {
	private static final Map<String, ContextSpecific<WaveData>> loaded;
	private static final ContextSpecific<Evaluable<PackedCollection>> interpolate;

	static {
		loaded = new HashMap<>();
		interpolate = new DefaultContextSpecific<>(() ->
				new Interpolate(
						new PassThroughProducer<>(Ops.o().shape(1), 0),
						new PassThroughProducer<>(Ops.o().shape(-1), 1),
						new PassThroughProducer<>(Ops.o().shape(1), 2),
						v -> Product.of(v, ExpressionFeatures.getInstance().e(1.0 / OutputLine.sampleRate)),
						v -> Product.of(v, ExpressionFeatures.getInstance().e(OutputLine.sampleRate))).get());
	}

	protected void clearKey(String key) {
		loaded.remove(key);
	}

	protected abstract WaveData load();

	protected void unload() { clearKey(getKey()); }

	@Override
	public double getDuration(double playbackRate) {
		return getDuration() / playbackRate;
	}

	@Override
	public int getCount(double playbackRate) {
		return (int) (getCount() / playbackRate);
	}

	@Override
	public PackedCollection getChannelData(int channel, double playbackRate) {
		WaveData original = get();
		if (playbackRate == 1.0) return original.getChannelData(channel);

		PackedCollection rate = PackedCollection.factory().apply(1);
		rate.setMem(0, playbackRate);

		PackedCollection audio = original.getChannelData(channel);
		int len = (int) (audio.getMemLength() / playbackRate);
		PackedCollection dest = new PackedCollection(len);

		// TODO  This can use CollectionFeatures::integers instead of taking a timeline argument
		PackedCollection timeline = WaveOutput.timeline.getValue();

		interpolate.getValue().into(dest.traverse(1))
				.evaluate(audio.traverse(0),
						timeline.range(shape(dest.getMemLength())).traverseEach(),
						rate.traverse(0));
		return dest.traverse(1);
	}

	@Override
	public WaveData get() {
		if (loaded.get(getKey()) == null) {
			DefaultContextSpecific<WaveData> specific = new DefaultContextSpecific<>(this::load);
			specific.setValid(w -> w.getData() != null && !w.getData().isDestroyed());

			loaded.put(getKey(), specific);
			loaded.get(getKey()).init();
		}

		return loaded.get(getKey()).getValue();
	}
}
