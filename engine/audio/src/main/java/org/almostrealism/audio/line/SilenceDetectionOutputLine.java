/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.audio.line;

import io.almostrealism.relation.Producer;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationList;

import java.util.function.Supplier;

/**
 * An {@link OutputLine} wrapper that monitors audio levels to detect silence,
 * useful for automatic recording stop, voice activity detection, or energy-based
 * processing decisions. The line tracks the maximum sample value and compares it
 * against a configurable threshold.
 * <p>
 * The silence detection is performed using hardware-accelerated max operations
 * over the entire sample buffer, with the result stored in a {@link PackedCollection}
 * for querying via {@link #isSilence()}.
 * </p>
 *
 * @see OutputLine
 */
public class SilenceDetectionOutputLine implements OutputLine, CellFeatures {
	/** The delegate output line that receives audio samples after analysis. */
	private final OutputLine out;

	/** Amplitude threshold below which audio is considered silence. */
	private final double threshold;

	/** Single-element collection holding the maximum absolute sample value observed. */
	private final PackedCollection max;

	/**
	 * Creates a SilenceDetectionOutputLine wrapping the given output line with a default threshold of 0.05.
	 *
	 * @param out the delegate output line
	 */
	public SilenceDetectionOutputLine(OutputLine out) {
		this(out, 0.05);
	}

	/**
	 * Creates a SilenceDetectionOutputLine wrapping the given output line with the specified threshold.
	 *
	 * @param out       the delegate output line
	 * @param threshold amplitude below which audio is considered silence (0.0–1.0)
	 */
	public SilenceDetectionOutputLine(OutputLine out, double threshold) {
		this.out = out;
		this.threshold = threshold;
		this.max = new PackedCollection(1);
	}

	@Override
	public void write(PackedCollection sample) {
		throw new UnsupportedOperationException();
	}

	/**
	 * Returns true if the maximum observed sample amplitude is below the configured threshold,
	 * indicating that the most recently written buffer was silent.
	 *
	 * @return true if the audio is below the silence threshold
	 */
	public boolean isSilence() {
		return max.toDouble() < threshold;
	}

	@Override
	public Supplier<Runnable> write(Producer<PackedCollection> frames) {
		OperationList write = new OperationList("SilenceDetectionOutputLine");
		write.add(a(cp(max), max(traverse(0, frames))));
		write.add(out.write(frames));
		return write;
	}
}
