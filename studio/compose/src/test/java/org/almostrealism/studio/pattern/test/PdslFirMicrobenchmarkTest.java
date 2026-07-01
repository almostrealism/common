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

package org.almostrealism.studio.pattern.test;

import io.almostrealism.relation.Evaluable;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.time.computations.MultiOrderFilter;
import org.almostrealism.util.TestDepth;
import org.junit.Test;

/**
 * Isolates the run-time cost of a single {@link MultiOrderFilter} (FIR) at the shape the
 * mixdown forward uses (a {@code [channels, signal]} bank convolved with a {@code [channels,
 * taps]} coefficient bank), separate from the rest of the mixdown graph. The mixdown forward
 * applies several such FIRs (per-channel high-pass, wet, efx, master low-pass); if one
 * {@code [6, 8192] x 41} FIR costs several ms, the FIRs explain the frame-proportional forward
 * cost and the per-tap bounds-check conditional is the optimization target.
 */
public class PdslFirMicrobenchmarkTest extends AudioSceneTestBase {

	/** Warm-up evaluations (compile + steady-state) discarded before timing. */
	private static final int WARMUP = 12;

	/** Timed evaluations. */
	private static final int ITERS = 50;

	/**
	 * Times a {@code [6, 8192]} FIR with 41 taps and, for comparison, a single-channel
	 * {@code [1, 8192]} FIR, reporting milliseconds per evaluation.
	 */
	@Test(timeout = 600_000)
	@TestDepth(2)
	public void firCost() {
		benchmark(6, 8192, 41);
		benchmark(1, 8192, 41);
		benchmark(6, 4096, 41);
	}

	/**
	 * Builds and times a multi-channel FIR of the given shape.
	 *
	 * @param channels number of independent channels (rows)
	 * @param signal   samples per channel
	 * @param taps     filter coefficient count (order + 1)
	 */
	private void benchmark(int channels, int signal, int taps) {
		// Values are irrelevant: the FIR kernel runs the full per-tap convolution loop regardless
		// of data (the bounds-check branch is on position, not value), so zero-initialized inputs
		// time the kernel identically. coeffs come from a runtime PackedCollection (p(coeffs)), so
		// the compiler cannot constant-fold the multiplies.
		PackedCollection input = new PackedCollection(shape(channels, signal));
		PackedCollection coeffs = new PackedCollection(shape(channels, taps));

		MultiOrderFilter filter = MultiOrderFilter.create(p(input), p(coeffs));
		Evaluable<PackedCollection> ev = filter.get();

		for (int i = 0; i < WARMUP; i++) {
			ev.evaluate();
		}

		long start = System.nanoTime();
		for (int i = 0; i < ITERS; i++) {
			ev.evaluate();
		}
		double msPerEval = (System.nanoTime() - start) / 1e6 / ITERS;
		log("FIR channels=" + channels + " signal=" + signal + " taps=" + taps
				+ " msPerEval=" + String.format("%.3f", msPerEval));

		input.destroy();
		coeffs.destroy();
	}
}
