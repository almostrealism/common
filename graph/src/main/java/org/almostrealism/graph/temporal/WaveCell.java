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

package org.almostrealism.graph.temporal;

import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Provider;
import org.almostrealism.CodeFeatures;
import org.almostrealism.Ops;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.TimeCell;
import org.almostrealism.hardware.HardwareFeatures;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.hardware.computations.Assignment;
import io.almostrealism.relation.Factor;

import java.util.Objects;
import java.util.function.Supplier;

public class WaveCell extends CollectionTemporalCellAdapter {
	private final WaveCellData data;
	private final Producer<PackedCollection<?>> wave;

	private final TimeCell clock;
	private final Producer<Scalar> frameIndex, frameCount;
	private final Producer<Scalar> frame;

	private double amplitude;
	private double waveLength;

	public WaveCell(PackedCollection<?> wav, int sampleRate) {
		this(wav, sampleRate, 1.0);
	}

	public WaveCell(PackedCollection<?> wav, int sampleRate, double amplitude) {
		this(wav, sampleRate, amplitude, null, null);
	}

	public WaveCell(PackedCollection<?> wav, int sampleRate, double amplitude,
					Producer<Scalar> offset, Producer<Scalar> repeat) {
		this(wav, sampleRate, amplitude, offset, repeat, Ops.o().scalar(0.0), Ops.o().scalar(wav.getCountLong()));
	}

	public WaveCell(PackedCollection<?> wav, int sampleRate, double amplitude,
					Producer<Scalar> offset, Producer<Scalar> repeat,
					Producer<Scalar> frameIndex, Producer<Scalar> frameCount) {
		this(new DefaultWaveCellData(), wav, sampleRate, amplitude, offset, repeat, frameIndex, frameCount);
	}

	public WaveCell(WaveCellData data, PackedCollection<?> wav, int sampleRate, double amplitude,
					Producer<Scalar> offset, Producer<Scalar> repeat,
					Producer<Scalar> frameIndex, Producer<Scalar> frameCount) {
		this(data, () -> new Provider<>(wav), sampleRate, amplitude,
				offset, repeat, frameIndex, frameCount);
	}

	public WaveCell(WaveCellData data, Producer<PackedCollection<?>> wav, int sampleRate, double amplitude,
					Producer<Scalar> offset, Producer<Scalar> repeat,
					Producer<Scalar> frameIndex, Producer<Scalar> frameCount) {
		this.data = data;
		this.amplitude = amplitude;
		this.wave = validate(wav);

		this.waveLength = 1;

		Producer<Scalar> initial;

		if (offset != null) {
			initial = scalarsMultiply(offset, scalar(-sampleRate));
		} else {
			initial = null;
		}

		Producer<Scalar> duration;

		if (repeat != null) {
			duration = scalarsMultiply(repeat, scalar(sampleRate));
		} else {
			duration = null;
		}

		this.clock = new TimeCell(initial, duration);
		this.frame = clock.frameScalar();

		this.frameIndex = frameIndex;
		this.frameCount = frameCount;
	}

	public WaveCell(PackedCollection<?> wav, int sampleRate, Producer<Scalar> frame) {
		this(wav, sampleRate, 1.0, frame);
	}

	public WaveCell(PackedCollection<?> wav, int sampleRate, double amplitude, Producer<Scalar> frame) {
		this(new DefaultWaveCellData(), wav, sampleRate, amplitude, frame);
	}

	public WaveCell(WaveCellData data, PackedCollection<?> wav, int sampleRate, double amplitude, Producer<Scalar> frame) {
		this(data, wav, sampleRate, amplitude, frame, Ops.o().scalar(0.0), Ops.o().scalar(wav.getCountLong()));
	}

	public WaveCell(WaveCellData data, PackedCollection<?> wav, int sampleRate, double amplitude,
					Producer<Scalar> frame, Producer<Scalar> frameIndex, Producer<Scalar> frameCount) {
		this(data, () -> new Provider<>(wav), sampleRate, amplitude, frame, frameIndex, frameCount);
	}

	public WaveCell(WaveCellData data, Producer<PackedCollection<?>> wav, int sampleRate, double amplitude,
					Producer<Scalar> frame, Producer<Scalar> frameIndex, Producer<Scalar> frameCount) {
		this.data = data;
		this.amplitude = amplitude;
		this.wave = validate(wav);
		this.waveLength = 1;

		this.clock = null;
		this.frame = Objects.requireNonNull(frame);

		this.frameIndex = frameIndex;
		this.frameCount = frameCount;
	}

	public void setAmplitude(double amp) { amplitude = amp; }

	public WaveCellData getData() { return data; }

	public TimeCell getClock() { return clock; }

	public Producer<Scalar> getFrame() { return frame; }

	@Override
	public Supplier<Runnable> setup() {
		OperationList setup = new OperationList("WaveCell Setup");
		if (clock != null) setup.add(clock.setup());
		setup.add(a(1, data.getWaveLength(), scalar(waveLength)));
		setup.add(a(1, data.getWaveIndex(), frameIndex));
		setup.add(a(1, data.getWaveCount(), frameCount));
		setup.add(a(1, data.getAmplitude(), scalar(amplitude)));
		setup.add(super.setup());
		return setup;
	}

	@Override
	public Supplier<Runnable> push(Producer<PackedCollection<?>> protein) {
		OperationList push = new OperationList("WavCell Push");
		push.add(new WaveCellPush(data, wave, frame, data.value()));
		push.add(super.push(p(data.value())));
		return push;
	}

	@Override
	public Supplier<Runnable> tick() {
		OperationList tick = new OperationList("WavCell Tick");
		if (clock != null) tick.add(clock.tick());
		tick.add(super.tick());
		return tick;
	}

	public Factor<PackedCollection<?>> toFactor() {
		return toFactor(() -> new PackedCollection<>(shape(1)), p -> protein -> new Assignment<>(1, p, protein));
	}

	private static Producer<PackedCollection<?>> validate(Producer<PackedCollection<?>> wav) {
		if (!(wav instanceof Shape)) return wav;

		TraversalPolicy shape = ((Shape) wav).getShape();

		if (shape.getCountLong() == 0) {
			throw new IllegalArgumentException("Wave must have at least one sample");
		} else if (shape.getCountLong() == 1) {
			System.out.println("WARN: Wave has only one sample");
		}

		return wav;
	}
}

