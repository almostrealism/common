/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.audio;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.time.TemporalFeatures;
import org.almostrealism.util.TestSuiteBase;

/**
 * The workload shared by the batched SSS chain tests: a fixed number of notes and
 * source layers at fixed lengths, driven by per-note scalars that vary linearly
 * across the notes.
 *
 * <p>The subclasses compare different routes through that workload — a batched
 * dispatch against a per-note reference, and a fused scalar-driven dispatch against
 * a pre-materialized one — so they must describe the same notes for the comparison
 * to mean anything.</p>
 */
public abstract class BatchedSssTestBase extends TestSuiteBase implements TemporalFeatures {

	/** Number of notes active in the window. */
	protected static final int N = 4;

	/** Source layers summed per note (production {@code PatternNoteFactory.LAYER_COUNT}). */
	protected static final int LAYERS = 3;

	/** Source samples per note before resampling. */
	protected static final int SOURCE_LENGTH = 2048;

	/** Target samples per note after resampling. */
	protected static final int TARGET_LENGTH = 1024;

	/** Output window width in frames (the a2 window; wider than a single note). */
	protected static final int WINDOW_WIDTH = 1536;

	/** FIR filter order matching production {@code EfxManager.filterOrder}. */
	protected static final int FILTER_ORDER = 40;

	/**
	 * The per-note parameter sequence {@code base + step * n}, which is the form every
	 * per-note scalar in these tests takes. A {@code step} of zero yields a constant.
	 *
	 * @param base the value for the first note
	 * @param step the amount added per subsequent note
	 * @return the per-note values, shaped {@code [N]}
	 */
	protected PackedCollection perNote(double base, double step) {
		return linear(base, base + step * (N - 1), N).evaluate().reshape(N);
	}

	/**
	 * The per-note destination offsets within the output window. One note starts
	 * mid-window so the placement is exercised rather than a plain sum at zero.
	 *
	 * @return the offsets, shaped {@code [N]}
	 */
	protected PackedCollection destinationOffsets() {
		return pack(0, 200, 512, 700);
	}

	/**
	 * The eight per-note layer envelope parameters for the given layer, in the order
	 * {@code buildLayerEnvelopeCurve} declares them.
	 *
	 * @param layer the layer index
	 * @return the parameter collections
	 */
	protected PackedCollection[] layerEnvelopeParameters(int layer) {
		return new PackedCollection[] {
				perNote(0.012, 0.002), perNote(0.3, 0.0), perNote(0.6, 0.0),
				perNote(1.0, 0.0), perNote(0.0, 0.0), perNote(0.85 + 0.02 * layer, 0.0),
				perNote(0.5, 0.03), perNote(0.0, 0.0) };
	}

	/**
	 * The five per-note ADSR parameters driving the filter cutoff envelope, in the
	 * order {@code buildVolumeEnvelopeCurve} declares them.
	 *
	 * @return the parameter collections
	 */
	protected PackedCollection[] filterAdsr() {
		return new PackedCollection[] {
				perNote(0.002, 0.0003), perNote(0.0015, 0.0002), perNote(0.5, 0.05),
				perNote(0.004, 0.0005), perNote(0.016, 0.002) };
	}

	/**
	 * The five per-note ADSR parameters driving the volume envelope, in the order
	 * {@code buildVolumeEnvelopeCurve} declares them.
	 *
	 * @return the parameter collections
	 */
	protected PackedCollection[] volumeAdsr() {
		return new PackedCollection[] {
				perNote(0.0015, 0.0003), perNote(0.0010, 0.0002), perNote(0.45, 0.05),
				perNote(0.003, 0.0005), perNote(0.018, 0.002) };
	}
}
