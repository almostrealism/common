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
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.TimeCell;
import org.almostrealism.hardware.mem.MemoryDataCopy;

/**
 * A low-pass filter with ADSR-controlled cutoff frequency.
 *
 * <p>FilterEnvelopeProcessor applies a low-pass filter whose cutoff frequency
 * varies over time according to an ADSR envelope. The cutoff sweeps from
 * 0 Hz to {@code filterPeak} Hz based on the envelope shape.</p>
 *
 * @see EnvelopeProcessor
 * @see MultiOrderFilterEnvelopeProcessor
 */
// TODO  This should implement AudioProcessor
public class FilterEnvelopeProcessor implements EnvelopeProcessor, CellFeatures, EnvelopeFeatures {
	/** Maximum cutoff frequency in Hz that the ADSR envelope can sweep the filter to. */
	public static double filterPeak = 20000;

	/** Time source driving the filter's sample-by-sample processing. */
	private final TimeCell clock;

	/** Input audio buffer; audio is copied here before processing. */
	private final PackedCollection input;

	/** Output audio buffer; processed audio is written here. */
	private final PackedCollection output;

	/** Single-element collection holding the total envelope duration in seconds. */
	private final PackedCollection duration;

	/** Single-element collection holding the envelope attack time in seconds. */
	private final PackedCollection attack;

	/** Single-element collection holding the envelope decay time in seconds. */
	private final PackedCollection decay;

	/** Single-element collection holding the envelope sustain level (0.0–1.0). */
	private final PackedCollection sustain;

	/** Single-element collection holding the envelope release time in seconds. */
	private final PackedCollection release;

	/** Compiled processing pipeline that applies the filter envelope to the input buffer. */
	private final Runnable process;

	/**
	 * Creates a FilterEnvelopeProcessor with the given sample rate and maximum buffer duration.
	 *
	 * @param sampleRate audio sample rate in Hz
	 * @param maxSeconds maximum supported audio duration in seconds; determines buffer size
	 */
	public FilterEnvelopeProcessor(int sampleRate, double maxSeconds) {
		input = new PackedCollection((int) (sampleRate * maxSeconds));
		output = new PackedCollection((int) (sampleRate * maxSeconds));
		duration = new PackedCollection(1);
		attack = new PackedCollection(1);
		decay = new PackedCollection(1);
		sustain = new PackedCollection(1);
		release = new PackedCollection(1);

		EnvelopeSection env = envelope(cp(duration), cp(attack), cp(decay), cp(sustain), cp(release));

		clock = new TimeCell();
		Producer<PackedCollection> freq = frames(clock.frame(), () -> env.get().getResultant(c(filterPeak)));

		WaveData audio = new WaveData(input.traverse(1), sampleRate);
		process = cells(1, i -> audio.toCell(clock.frame(), 0))
				.addRequirement(clock)
				.f(i -> lp(freq, c(0.1)))
				.export(output).get();
	}

	/**
	 * Sets the total envelope duration.
	 *
	 * @param duration envelope duration in seconds
	 */
	@Override
	public void setDuration(double duration) {
		this.duration.set(0, duration);
	}

	/**
	 * Sets the envelope attack time.
	 *
	 * @param attack attack time in seconds
	 */
	@Override
	public void setAttack(double attack) {
		this.attack.set(0, attack);
	}

	/**
	 * Sets the envelope decay time.
	 *
	 * @param decay decay time in seconds
	 */
	@Override
	public void setDecay(double decay) {
		this.decay.set(0, decay);
	}

	/**
	 * Sets the envelope sustain level.
	 *
	 * @param sustain sustain level from 0.0 (silent) to 1.0 (full cutoff)
	 */
	@Override
	public void setSustain(double sustain) {
		this.sustain.set(0, sustain);
	}

	/**
	 * Sets the envelope release time.
	 *
	 * @param release release time in seconds
	 */
	@Override
	public void setRelease(double release) {
		this.release.set(0, release);
	}

	@Override
	public void process(PackedCollection input, PackedCollection output) {
		// TODO  This can be done without the copy to input
		// TODO  by just using a Provider that can be made to
		// TODO  refer to the provided data collection directly.
		new MemoryDataCopy("FilterEnvelopeProcessor input", input, this.input).get().run();
		process.run();
		new MemoryDataCopy("FilterEnvelopeProcessor output", this.output.range(shape(output.getMemLength())), output).get().run();
	}
}
