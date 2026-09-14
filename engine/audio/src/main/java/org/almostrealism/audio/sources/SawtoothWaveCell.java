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
 * A temporal cell that generates sawtooth wave audio with configurable frequency,
 * amplitude, phase, direction, and envelope. Supports both ascending (ramp up)
 * and descending (ramp down) waveforms. Includes PolyBLEP anti-aliasing to reduce
 * aliasing artifacts at the wave discontinuity.
 * <p>
 * The sawtooth wave produces a linear ramp that creates a bright, buzzy tone
 * rich in harmonics, commonly used in synthesizers for brass and string sounds.
 * Parameter storage, setup, the runtime setters and position advancement are
 * inherited from {@link OscillatorCell}.
 *
 * <h2>PolyBLEP Anti-Aliasing</h2>
 * <p>The sawtooth wave has a sharp discontinuity at the end of each cycle which can
 * cause aliasing. PolyBLEP (Polynomial Band-Limited Step) smooths this discontinuity
 * to reduce aliasing artifacts without significant computational overhead.</p>
 *
 * @see OscillatorCell
 * @see SineWaveCellData
 */
public class SawtoothWaveCell extends OscillatorCell {
	/** Wave direction: true for ascending ramp (-1 to +1), false for descending (+1 to -1). */
	private boolean ascending;

	/**
	 * Creates a new SawtoothWaveCell with default polymorphic data storage.
	 */
	public SawtoothWaveCell() {
		this(new PolymorphicAudioData());
	}

	/**
	 * Creates a new SawtoothWaveCell with the specified data storage.
	 *
	 * @param data the hardware-side data storage for wave parameters
	 */
	public SawtoothWaveCell(SineWaveCellData data) {
		super(data);
		this.ascending = true;
	}

	/**
	 * Sets the wave direction. This is a compile-time parameter that affects
	 * the generated GPU code - it cannot be changed dynamically.
	 *
	 * @param ascending true for ascending ramp (-1 to +1), false for descending (+1 to -1)
	 */
	public void setAscending(boolean ascending) {
		this.ascending = ascending;
	}

	/**
	 * Returns whether this is an ascending (ramp up) sawtooth.
	 *
	 * @return true if ascending, false if descending
	 */
	public boolean isAscending() {
		return ascending;
	}

	/**
	 * Computes a linear ramp with PolyBLEP anti-aliasing applied at the discontinuity.
	 *
	 * @param phase the raw phase in cycles
	 * @return the anti-aliased sawtooth waveform
	 */
	@Override
	protected CollectionProducer waveform(CollectionProducer phase) {
		CollectionProducer frac = cyclePosition(phase);

		// Raw sawtooth: linear ramp from -1 to +1 (ascending) or +1 to -1 (descending)
		CollectionProducer rawSaw;
		if (ascending) {
			rawSaw = subtract(multiply(frac, c(2.0)), c(1.0));  // frac*2 - 1
		} else {
			rawSaw = subtract(c(1.0), multiply(frac, c(2.0)));  // 1 - frac*2
		}

		// PolyBLEP anti-aliasing
		CollectionProducer blep = polyBlep(frac, getData().getWaveLength());

		if (ascending) {
			return subtract(rawSaw, blep);
		} else {
			return add(rawSaw, blep);
		}
	}
}
