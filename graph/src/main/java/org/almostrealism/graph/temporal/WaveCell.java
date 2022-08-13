/*
 * Copyright 2022 Michael Murray
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

package org.almostrealism.graph.temporal;

import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.Ops;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.HardwareFeatures;
import org.almostrealism.hardware.OperationList;

import java.util.function.Supplier;

public class WaveCell extends CollectionTemporalCellAdapter implements CodeFeatures, HardwareFeatures {
	private final WaveCellData data;
	private final PackedCollection<?> wave;

	private final Producer<Scalar> offset, duration, frameIndex, frameCount;
	private final boolean repeat;

	private double amplitude;
	private double waveLength;

	public WaveCell(PackedCollection<?> wav, int sampleRate) {
		this(wav, sampleRate, 1.0);
	}

	public WaveCell(PackedCollection<?> wav, int sampleRate, double amplitude) {
		this(wav, sampleRate, amplitude, null, null, Ops.ops().v(0.0), Ops.ops().v(wav.getCount()));
	}

	public WaveCell(PackedCollection<?> wav, int sampleRate, double amplitude,
					Producer<Scalar> offset, Producer<Scalar> repeat,
					Producer<Scalar> frameIndex, Producer<Scalar> frameCount) {
		this(new DefaultWaveCellData(), wav, sampleRate, amplitude, offset, repeat, frameIndex, frameCount);
	}

	public WaveCell(WaveCellData data, PackedCollection<?> wav, int sampleRate, double amplitude,
					Producer<Scalar> offset, Producer<Scalar> repeat,
					Producer<Scalar> frameIndex, Producer<Scalar> frameCount) {
		this.data = data;
		this.amplitude = amplitude;
		this.wave = wav;

		setFreq(1);

		if (offset != null) {
			this.offset = scalarsMultiply(offset, v(sampleRate));
		} else {
			this.offset = null;
		}

		if (repeat != null) {
			this.repeat = true;
			this.duration = scalarsMultiply(repeat, v(sampleRate));
		} else {
			this.repeat = false;
			this.duration = null;
		}

		this.frameIndex = frameIndex;
		this.frameCount = frameCount;
	}

	public void setFreq(double hertz) { waveLength = hertz; }

	public void setAmplitude(double amp) { amplitude = amp; }

	@Override
	public Supplier<Runnable> setup() {
		OperationList setup = new OperationList("WavCell Setup");
		if (offset == null) {
			setup.add(a(1, data::getWavePosition, v(0.0)));
		} else {
			setup.add(a(1, data::getWavePosition, scalarsMultiply(v(-1.0), offset)));
		}

		setup.add(a(1, data::getWaveLength, v(waveLength)));
		setup.add(a(1, data::getWaveIndex, frameIndex));
		setup.add(a(1, data::getWaveCount, frameCount));
		setup.add(a(1, data::getAmplitude, v(amplitude)));
		setup.add(super.setup());
		return setup;
	}

	@Override
	public Supplier<Runnable> push(Producer<PackedCollection<?>> protein) {
		Scalar value = new Scalar();
		OperationList push = new OperationList("WavCell Push");
		if (duration != null) push.add(a(1, data::getDuration, duration));
		push.add(new WaveCellPush(data, wave, value, repeat));
		push.add(super.push(p(value)));
		return push;
	}

	@Override
	public Supplier<Runnable> tick() {
		OperationList tick = new OperationList("WavCell Tick");
		tick.add(new WaveCellTick(data, wave, repeat));
		tick.add(super.tick());
		return tick;
	}
}

