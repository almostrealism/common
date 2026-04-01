/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.music.grains;

import io.almostrealism.relation.Evaluable;
import org.almostrealism.audio.SamplingFeatures;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.collect.PackedCollection;

/**
 * Applies granular synthesis processing to a single audio grain.
 *
 * <p>Pre-compiles a sampling kernel for the specified duration and sample rate, then
 * evaluates it against a source audio buffer and grain descriptors to produce a
 * {@link WaveData} segment.</p>
 *
 * @deprecated Prefer the updated granular synthesis pipeline.
 * @see Grain
 * @see GrainSet
 */
@Deprecated
public class GrainProcessor implements SamplingFeatures {
	/** Total number of audio frames produced by this processor. */
	private final int frames;

	/** Audio sample rate in Hz. */
	private final int sampleRate;

	/** Pre-compiled sampling kernel evaluable. */
	private final Evaluable<PackedCollection> ev;

	/**
	 * Creates a {@code GrainProcessor} for the given duration and sample rate.
	 *
	 * @param duration   total output duration in seconds
	 * @param sampleRate audio sample rate in Hz
	 */
	public GrainProcessor(double duration, int sampleRate) {
		this.frames = (int) (duration * sampleRate);
		this.sampleRate = sampleRate;

		ev = sampling(sampleRate, duration, () -> grains(
				v(1, 0),
				v(shape(3), 1),
				v(shape(3), 2),
				v(shape(3), 3),
				v(1, 4))).get();
	}

	/** Returns the total number of audio frames this processor produces. */
	public int getFrames() { return frames; }

	/**
	 * Applies the grain sampling kernel to produce a {@link WaveData} segment.
	 *
	 * @param input      the source audio buffer
	 * @param grain      the grain descriptor (start, duration, rate)
	 * @param wavelength per-grain wavelength modulation values
	 * @param phase      per-grain phase modulation values
	 * @param amp        per-grain amplitude modulation values
	 * @return the processed grain as a {@link WaveData} instance
	 */
	public WaveData apply(PackedCollection input, Grain grain, PackedCollection wavelength, PackedCollection phase, PackedCollection amp) {
		PackedCollection result = ev.into(new PackedCollection(shape(frames), 1))
				.evaluate(input.traverse(0), grain, wavelength, phase, amp);
		return new WaveData(result, sampleRate);
	}
}
