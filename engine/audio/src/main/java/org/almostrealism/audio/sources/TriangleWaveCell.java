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

package org.almostrealism.audio.sources;

import org.almostrealism.audio.data.PolymorphicAudioData;
import org.almostrealism.collect.CollectionProducer;

/**
 * A temporal cell that generates triangle wave audio with configurable frequency,
 * amplitude, phase, and envelope. The triangle wave produces a mellow, flute-like
 * tone with odd harmonics that roll off quickly, making it softer than square
 * or sawtooth waves.
 * <p>
 * The triangle wave has no discontinuities, so it naturally produces less aliasing
 * than other geometric waveforms and does not require PolyBLEP anti-aliasing.
 * Parameter storage, setup, the runtime setters and position advancement are
 * inherited from {@link OscillatorCell}.
 *
 * @see OscillatorCell
 * @see SineWaveCellData
 */
public class TriangleWaveCell extends OscillatorCell {
	/**
	 * Creates a new TriangleWaveCell with default polymorphic data storage.
	 */
	public TriangleWaveCell() {
		this(new PolymorphicAudioData());
	}

	/**
	 * Creates a new TriangleWaveCell with the specified data storage.
	 *
	 * @param data the hardware-side data storage for wave parameters
	 */
	public TriangleWaveCell(SineWaveCellData data) {
		super(data);
	}

	/**
	 * Computes a linear ramp from -1 to +1 during the first half of the cycle,
	 * then from +1 to -1 during the second half.
	 *
	 * @param phase the raw phase in cycles
	 * @return the triangle waveform
	 */
	@Override
	protected CollectionProducer waveform(CollectionProducer phase) {
		CollectionProducer frac = cyclePosition(phase);

		// if (frac < 0.5) triangle = frac*4 - 1 else triangle = 3 - frac*4
		return lessThan(frac, c(0.5),
				subtract(multiply(frac, c(4.0)), c(1.0)),
				subtract(c(3.0), multiply(frac, c(4.0))));
	}
}
