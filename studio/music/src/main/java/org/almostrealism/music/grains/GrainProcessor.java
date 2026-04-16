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
import org.almostrealism.collect.PackedCollection;

/**
 * Pre-compiles a grain sampling kernel for reuse across rendering calls.
 *
 * <p>The kernel takes five positional inputs &mdash; a source audio buffer, a
 * {@link Grain} descriptor, and per-grain wavelength, phase, and amplitude
 * modulation values &mdash; and produces a fixed-length audio segment. Callers
 * bind inputs and invoke the kernel at a pipeline boundary using
 * {@link #getKernel()} and {@link #newOutputBuffer()}:</p>
 *
 * <pre>{@code
 * PackedCollection rendered = processor.getKernel()
 *         .into(processor.newOutputBuffer())
 *         .evaluate(source.traverse(0), grain, wavelength, phase, amp);
 * }</pre>
 *
 * <p>Keeping {@code .evaluate()} at the caller's rendering boundary preserves
 * the computation graph up to that point and satisfies the framework's
 * Producer-pattern policy for this class.</p>
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

	/** Returns the audio sample rate in Hz. */
	public int getSampleRate() { return sampleRate; }

	/**
	 * Returns the pre-compiled grain sampling kernel.
	 *
	 * <p>The returned kernel expects five positional arguments, in order: source
	 * audio buffer (traversed on axis 0), {@link Grain} descriptor, wavelength,
	 * phase, amplitude. Callers should pair this with {@link #newOutputBuffer()}
	 * and invoke {@code .into(buffer).evaluate(...)} at a rendering boundary.</p>
	 */
	public Evaluable<PackedCollection> getKernel() { return ev; }

	/** Allocates a fresh output buffer sized for one kernel invocation. */
	public PackedCollection newOutputBuffer() {
		return new PackedCollection(shape(frames), 1);
	}
}
