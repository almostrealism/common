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
}
