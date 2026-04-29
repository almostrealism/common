/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.ml;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.midi.HeadGroupConfig;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Minimal reproduction test for the "Hardware Cannot compile greaterThan/subset" regression.
 *
 * <p>The regression was introduced when {@link RotationFeatures#computeRopeFreqs} was changed
 * to return a lazy {@link CollectionProducer} instead of an evaluated {@link PackedCollection}.
 * This broke {@link RotationFeatures#ropeRotation} and {@link RotationFeatures#mraRopeRotation}
 * because both methods close over the {@code freqCis} argument in a {@code CellularLayer} lambda,
 * and the framework's fixed-count kernel compilation cannot resolve a kernel element count when
 * a lazy producer sits in the computation graph at a position where the compiler expects a
 * materialised data buffer.</p>
 *
 * <p>Each test here is a standalone reproducer. They are expected to PASS once the fix is in
 * place; before the fix they fail with a {@code HardwareException} containing
 * "Cannot compile greaterThan" or "Cannot compile subset".</p>
 *
 * @see RotationFeatures#computeRopeFreqs
 * @see RotationFeatures#ropeRotation
 * @see RotationFeatures#mraRopeRotation
 */
public class RopeCompilationRegressionTest extends TestSuiteBase implements AttentionFeatures {

	/**
	 * Verify that {@link RotationFeatures#ropeRotation} compiles successfully when
	 * {@code freqCis} is a lazy {@link CollectionProducer} returned by
	 * {@link RotationFeatures#computeRopeFreqs} without calling {@code .evaluate()}.
	 *
	 * <p>Before the fix this throws:
	 * {@code HardwareException: Hardware Cannot compile greaterThan}</p>
	 */
	@Test(timeout = 60000)
	public void ropeRotationWithLazyFreqCis() {
		int heads = 2;
		int headDim = 8;
		int freqDim = headDim / 2;
		int seqLen = 4;

		// computeRopeFreqs returns a lazy CollectionProducer — NOT evaluated
		CollectionProducer freqCis =
				RotationFeatures.computeRopeFreqs(10000.0, headDim, seqLen);

		PackedCollection position = new PackedCollection(1);
		position.setMem(0, 0.0);

		PackedCollection input = new PackedCollection(shape(heads, freqDim, 2)).randFill();

		Model model = new Model(shape(heads, freqDim, 2));
		model.sequential().add(
				ropeRotation(shape(heads, freqDim, 2), freqCis, p(position))
		);

		// This must compile without "Cannot compile greaterThan"
		CompiledModel compiled = model.compile(false);
		PackedCollection output = compiled.forward(input);
		assertNotNull("ropeRotation forward pass should produce output", output);
		assertEquals("Output element count", heads * freqDim * 2,
				output.getShape().getTotalSize());
	}

	/**
	 * Verify that {@link RotationFeatures#mraRopeRotation} compiles successfully when
	 * each {@link HeadGroupConfig#freqCis} is a lazy {@link CollectionProducer}.
	 *
	 * <p>This mirrors the exact call path taken by {@code MoonbeamMidi.buildTransformer()}
	 * via {@code HeadGroupConfig.fromParams()}, which stores the unevaluated result of
	 * {@code computeRopeFreqs()} in each {@code HeadGroupConfig}.</p>
	 *
	 * <p>Before the fix this throws:
	 * {@code HardwareException: Hardware Cannot compile greaterThan}</p>
	 */
	@Test(timeout = 60000)
	public void mraRopeRotationWithLazyFreqCis() {
		int totalHeads = 2;
		int headDim = 8;
		int freqDim = headDim / 2;
		int seqLen = 4;

		// Two groups, one head each — mirrors MoonbeamConfig.testConfig() layout
		PackedCollection attrPositions = new PackedCollection(2);
		attrPositions.setMem(0, 0.0);
		attrPositions.setMem(1, 0.0);

		Producer<PackedCollection>[] positions = new Producer[2];
		positions[0] = cp(attrPositions).subset(shape(1), 0);
		positions[1] = cp(attrPositions).subset(shape(1), 1);

		double[] thetas = { 10000.0, 1000.0 };
		int[] headsPerGroup = { 1, 1 };

		// fromParams calls computeRopeFreqs and stores the lazy CollectionProducer
		HeadGroupConfig[] headGroups = HeadGroupConfig.fromParams(
				thetas, headDim, seqLen, headsPerGroup, positions);

		PackedCollection input =
				new PackedCollection(shape(totalHeads, freqDim, 2)).randFill();

		Model model = new Model(shape(totalHeads, freqDim, 2));
		model.sequential().add(
				mraRopeRotation(totalHeads, headDim, headsPerGroup, headGroups)
		);

		// This must compile without "Cannot compile greaterThan"
		CompiledModel compiled = model.compile(false);
		PackedCollection output = compiled.forward(input);
		assertNotNull("mraRopeRotation forward pass should produce output", output);
		assertEquals("Output element count", totalHeads * freqDim * 2,
				output.getShape().getTotalSize());
	}

	/**
	 * Narrow reproducer of the RoPE compile explosion that breaks
	 * {@code MoonbeamMidiTest}, {@code MidiTrainingTest}, and {@code SkyTntMidiTest}
	 * (all three funnel through {@link RotationFeatures#ropeRotation}/{@code mraRopeRotation}
	 * with a lazy {@link CollectionProducer} {@code freqCis}).
	 *
	 * <p><strong>What this test proves.</strong> {@code computeRopeFreqs} returns a lazy
	 * graph (integers &rarr; exp &rarr; matmul(positions, invFreq) &rarr; cos/sin
	 * &rarr; concat). When that lazy graph is passed as the gather-target
	 * {@code weights} argument to {@code ropeRotation}/{@code mraRopeRotation}, every
	 * index into {@code weights} inlines the entire freqCis graph. With the real
	 * Moonbeam shape, this produces a kernel expression so large that simplification
	 * either runs out of memory, bails out with "Large expression not improved by
	 * simplification" (surfaced as "Cannot compile greaterThan/subset"), or &mdash;
	 * if simplification completes &mdash; makes the native C compiler ({@code cc1}
	 * on Linux, {@code clang -cc1} on macOS) OOM on a single-line C expression
	 * tens of megabytes long. Observed concretely: at {@code seqLen=16} with
	 * {@code AR_HARDWARE_DRIVER=native}, the generated C file contains a single
	 * 44&nbsp;MB expression on one line and {@code clang -cc1} climbs past 2&nbsp;GB
	 * RSS before failing. On the CI Linux runner this surfaces as
	 * {@code "Killed" / "gcc: fatal error: Killed signal terminated program cc1" /
	 * Hardware Native compiler failure (1)}.</p>
	 *
	 * <p><strong>Why the existing regression tests above miss it.</strong> They use
	 * {@code seqLen=4} and at most 2 groups &mdash; under that scale the inlined
	 * expression stays small enough to compile. Any fix that passes them while
	 * leaving the exponential-inlining structure intact is a false fix; this is
	 * why this regression has been "declared fixed" more than once while
	 * {@code MoonbeamMidiTest.testTransformerForwardPass} still dies.</p>
	 *
	 * <p><strong>Dimensions.</strong> {@code headDim=8} and six head groups exactly
	 * match {@code MoonbeamConfig.testConfig()}. {@code seqLen=16} is a deliberate
	 * reduction from the real {@code maxSeqLen=128}: at 128 the Java-side
	 * simplifier itself OOM-kills the forked JVM without surfacing a stack
	 * trace, which makes the failure undiagnosable. {@code seqLen=16} is still
	 * 4&times; the passing toy tests and reliably triggers the native-compile
	 * OOM or the {@code @Test(timeout=60000)} guard.</p>
	 *
	 * <p><strong>How this test should eventually pass.</strong> Fix the producer:
	 * either materialise {@code freqCis} before handing it to the gather, or
	 * restructure {@code ropeRotation}/{@code mraRopeRotation} so the gather from
	 * {@code freqCis} is issued against a compact expression (not inlined at
	 * every index site).</p>
	 */
	@Test(timeout = 60000)
	public void mraRopeRotationAtMoonbeamScale() {
		MoonbeamScaleFixture fixture = moonbeamScaleFixture();
		PackedCollection input =
				new PackedCollection(shape(fixture.totalHeads, fixture.freqDim, 2)).randFill();

		Model model = new Model(shape(fixture.totalHeads, fixture.freqDim, 2));
		model.sequential().add(
				mraRopeRotation(fixture.totalHeads, fixture.headDim,
						fixture.headsPerGroup, fixture.headGroups)
		);

		CompiledModel compiled = model.compile(false);
		PackedCollection output = compiled.forward(input);
		assertNotNull("mraRopeRotation forward pass should produce output", output);
		assertEquals("Output element count", fixture.totalHeads * fixture.freqDim * 2,
				output.getShape().getTotalSize());
	}

	/**
	 * Reproduces the RoPE compile explosion as it appears in the actual production
	 * transformer: a Q-projection (dense layer with a square weight matrix of
	 * size {@code hiddenSize = totalHeads * headDim}) followed by
	 * {@link RotationFeatures#mraRopeRotation}. This is the composition that ships
	 * in {@code MoonbeamMidi.buildTransformer()}; the standalone
	 * {@link #mraRopeRotationAtMoonbeamScale} test is merely the floor-case
	 * reproducer. The actual transformer wraps RoPE after a matmul whose output
	 * feeds the rotation layer.
	 *
	 * <p>This matters for threshold calibration of any {@code ExpansionWidthTargetOptimization}
	 * or similar isolation strategy: the ew accumulation along the path from the
	 * root of the forward pass down to the pad inside {@code freqCis} passes
	 * through additional non-trivial producers here (the matmul and its downstream
	 * reshape) that are absent from the standalone test. A threshold that catches
	 * only the standalone shape may under- or over-fire in this composed shape.</p>
	 */
	@Test(timeout = 60000)
	public void mraRopeRotationAfterQProjectionAtMoonbeamScale() {
		MoonbeamScaleFixture fixture = moonbeamScaleFixture();
		int hiddenSize = fixture.totalHeads * fixture.headDim;

		// Q-projection weights: (hiddenSize, hiddenSize). Single-position input; the
		// dense output has the same leading shape and is reshaped into
		// (totalHeads, freqDim, 2) for RoPE.
		PackedCollection wq = new PackedCollection(shape(hiddenSize, hiddenSize)).randFill();
		PackedCollection input = new PackedCollection(shape(1, hiddenSize)).randFill();

		Model model = new Model(shape(1, hiddenSize));
		SequentialBlock seq = model.sequential();
		seq.add(dense(shape(1, hiddenSize), wq, null, false));
		seq.add(reshape(shape(1, hiddenSize),
				shape(fixture.totalHeads, fixture.freqDim, 2)));
		seq.add(mraRopeRotation(fixture.totalHeads, fixture.headDim,
				fixture.headsPerGroup, fixture.headGroups));

		CompiledModel compiled = model.compile(false);
		PackedCollection output = compiled.forward(input);
		assertNotNull("matmul + mraRopeRotation forward pass should produce output", output);
		assertEquals("Output element count", fixture.totalHeads * fixture.freqDim * 2,
				output.getShape().getTotalSize());
	}

	/**
	 * Verify numerical correctness of {@link RotationFeatures#ropeRotation} with
	 * a lazy {@code freqCis}: the output must match the result obtained when
	 * {@code freqCis} is evaluated to a {@link PackedCollection} before use.
	 *
	 * <p>This test distinguishes between "it compiles but produces garbage" and
	 * "it compiles and is correct".</p>
	 */
	@Test(timeout = 60000)
	public void ropeRotationLazyMatchesEvaluated() {
		int heads = 2;
		int headDim = 8;
		int freqDim = headDim / 2;
		int seqLen = 4;
		double theta = 10000.0;

		PackedCollection position = new PackedCollection(1);
		position.setMem(0, 1.0);

		PackedCollection input = new PackedCollection(shape(heads, freqDim, 2)).randFill();

		// --- Model A: lazy freqCis (the new behaviour after computeRopeFreqs change) ---
		CollectionProducer lazyFreqCis = RotationFeatures.computeRopeFreqs(theta, headDim, seqLen);
		Model modelA = new Model(shape(heads, freqDim, 2));
		modelA.sequential().add(ropeRotation(shape(heads, freqDim, 2), lazyFreqCis, p(position)));
		CompiledModel compiledA = modelA.compile(false);
		PackedCollection outputA = compiledA.forward(input);

		// --- Model B: evaluated freqCis (the old behaviour before the change) ---
		PackedCollection evaluatedFreqCis =
				RotationFeatures.computeRopeFreqs(theta, headDim, seqLen).evaluate();
		CollectionProducer evalProducer = cp(evaluatedFreqCis);
		Model modelB = new Model(shape(heads, freqDim, 2));
		modelB.sequential().add(ropeRotation(shape(heads, freqDim, 2), evalProducer, p(position)));
		CompiledModel compiledB = modelB.compile(false);
		PackedCollection outputB = compiledB.forward(input);

		double diff = compare(outputA, outputB);
		log("ropeRotation lazy vs evaluated diff: " + diff);
		assertTrue("Lazy and evaluated freqCis must produce identical results", diff < 1e-5);
	}

	/**
	 * Bundle of shape parameters and {@link HeadGroupConfig}s matching
	 * {@code MoonbeamConfig.testConfig()} &mdash; used by both the standalone
	 * and Q-projection-composed reproducers so the two tests exercise the exact
	 * same RoPE shape.
	 */
	private static final class MoonbeamScaleFixture {
		final int headDim = 8;
		final int freqDim = headDim / 2;
		final int[] headsPerGroup = { 1, 1, 1, 1, 1, 1 };
		final int totalHeads;
		final HeadGroupConfig[] headGroups;

		MoonbeamScaleFixture(HeadGroupConfig[] headGroups, int totalHeads) {
			this.totalHeads = totalHeads;
			this.headGroups = headGroups;
		}
	}

	/**
	 * Build a {@link MoonbeamScaleFixture} with freshly-allocated attribute
	 * positions and {@link HeadGroupConfig}s. Allocated per-test so the
	 * underlying {@link PackedCollection}s are isolated between tests.
	 */
	private MoonbeamScaleFixture moonbeamScaleFixture() {
		int headDim = 8;
		int maxSeqLen = 16;
		int[] headsPerGroup = { 1, 1, 1, 1, 1, 1 };
		double[] thetas = { 199999, 1031, 19, 20, 199999, 131 };
		int totalHeads = 0;
		for (int h : headsPerGroup) totalHeads += h;

		PackedCollection attrPositions = new PackedCollection(thetas.length);
		Producer<PackedCollection>[] positions = new Producer[thetas.length];
		for (int g = 0; g < thetas.length; g++) {
			attrPositions.setMem(g, 0.0);
			positions[g] = cp(attrPositions).subset(shape(1), g);
		}

		HeadGroupConfig[] headGroups = HeadGroupConfig.fromParams(
				thetas, headDim, maxSeqLen, headsPerGroup, positions);

		return new MoonbeamScaleFixture(headGroups, totalHeads);
	}
}
