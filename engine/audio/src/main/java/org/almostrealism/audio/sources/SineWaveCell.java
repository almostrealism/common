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
import org.almostrealism.geometry.GeometryFeatures;

/**
 * A temporal cell that generates sine wave audio with configurable frequency,
 * amplitude, phase, and envelope. Implements both push and tick operations
 * for real-time audio generation within the graph framework.
 * <p>
 * The output is computed as: {@code sin(2*PI * (wavePosition + phase)) * envelope * amplitude * depth}.
 * Parameter storage, setup, the runtime setters and position advancement are
 * inherited from {@link OscillatorCell}.
 *
 * @see OscillatorCell
 * @see SineWaveCellData
 */
// TODO  Reimplement as a function of org.almostrealism.graph.TimeCell
public class SineWaveCell extends OscillatorCell implements GeometryFeatures {
	/** Pre-computed constant 2*PI for use in angle calculations. */
	private static final double TWO_PI = 2 * Math.PI;

	/**
	 * Creates a new SineWaveCell with default polymorphic data storage.
	 */
	public SineWaveCell() {
		this(new PolymorphicAudioData());
	}

	/**
	 * Creates a new SineWaveCell with the specified data storage.
	 *
	 * @param data the hardware-side data storage for wave parameters
	 */
	public SineWaveCell(SineWaveCellData data) {
		super(data);
	}

	/**
	 * Computes {@code sin(2*PI * phase)} from the raw phase.
	 *
	 * @param phase the raw phase in cycles
	 * @return the sine waveform
	 */
	@Override
	protected CollectionProducer waveform(CollectionProducer phase) {
		return sin(multiply(c(TWO_PI), phase));
	}
}
