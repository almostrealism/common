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

package org.almostrealism.studio.ml.test;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.audio.CellList;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.dsl.PdslLoader;
import org.almostrealism.ml.dsl.PdslNode;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.time.Temporal;
import org.almostrealism.util.FirFilterTestFeatures;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Validation tests for Approach D ({@code pipeline} keyword + {@code PdslTemporalBlock}).
 *
 * <p>These three tests verify the assumptions that Approach D relies on before any
 * production code is written. If a test fails it means a design assumption is wrong
 * and the plan must be revised before proceeding.</p>
 *
 * <h2>Tests</h2>
 * <ol>
 *   <li><strong>Per-buffer granularity</strong> — a {@link CellList} requirement's
 *       {@link Temporal#tick()} method is assembled exactly once per buffer-level tick
 *       regardless of {@link CellList#setTickLoopCount(int)}.</li>
 *   <li><strong>State persistence across Temporal.tick</strong> — stateful PDSL block
 *       state (delay buffer / write-head) survives across successive
 *       {@link Temporal#tick()} invocations when the block is wrapped as a Temporal.</li>
 *   <li><strong>Block→Temporal adapter forward flow</strong> — a minimal Temporal
 *       wrapping {@link CompiledModel#forward} channels input through to output,
 *       validating the pattern {@code PdslTemporalBlock.tick()} will use.</li>
 * </ol>
 *
 * @see org.almostrealism.ml.dsl.PdslLoader
 * @see org.almostrealism.audio.CellList
 * @see org.almostrealism.time.Temporal
 */
public class PdslApproachDValidationTest extends TestSuiteBase implements FirFilterTestFeatures {

	/** Buffer size — the N in {@code setTickLoopCount(N)}. */
	private static final int BUFFER_SIZE = 64;

	/** FIR filter order for blocks that require it. */
	private static final int FILTER_ORDER = 20;

	// ---- Test 1: Per-buffer granularity -----------------------------------------------

	/**
	 * Verifies that a {@link CellList} requirement's {@link Temporal#tick()} method
	 * is assembled exactly once per outer buffer tick even when
	 * {@link CellList#setTickLoopCount(int)} is set to N (the buffer size).
	 *
	 * <p>{@link CellList#tick()} assembles the per-tick operation graph once and wraps
	 * it in a compiled {@link org.almostrealism.hardware.computations.Loop}. Each
	 * requirement's {@code tick()} method must be called exactly once during that
	 * assembly — not N times. If it were called N times a {@code PdslTemporalBlock}
	 * would assemble N separate forward-pass graphs, which is not the intended
	 * behaviour.</p>
	 *
	 * <p>Note: the compiled Loop executes the assembled operations N times natively;
	 * this test specifically validates the Java-side assembly count, not the
	 * native execution count.</p>
	 */
	@Test(timeout = 30000)
	public void testRequirementTicksOncePerBufferNotPerSample() {
		AtomicInteger tickCallCount = new AtomicInteger(0);

		Temporal requirement = () -> {
			tickCallCount.incrementAndGet();
			return () -> {};
		};

		CellList list = new CellList();
		list.setTickLoopCount(BUFFER_SIZE);
		list.addRequirement(requirement);

		// Invoking CellList.tick() triggers assembly: each requirement's tick() must be
		// called exactly once, regardless of the tickLoopCount loop multiplier.
		list.tick();

		Assert.assertEquals(
				"Requirement tick() must be assembled once per buffer-level tick, "
						+ "not once per sample (tickLoopCount=" + BUFFER_SIZE + ")",
				1, tickCallCount.get());
	}

	// ---- Test 2: State persistence across Temporal.tick --------------------------------

	/**
	 * Verifies that stateful PDSL block state persists across successive
	 * {@link Temporal#tick()} invocations when the compiled block is wrapped as
	 * a {@link Temporal}.
	 *
	 * <p>Uses the {@code efx_delay} layer (from {@code efx_channel.pdsl}) with a
	 * two-sample integer delay. After the first tick (all-ones input), the circular
	 * buffer holds the written samples. On the second tick (all-twos input), the
	 * first two output samples must read back the value written by the first tick
	 * (1.0), not the default zero — proving that delay state carried across the
	 * tick boundary.</p>
	 *
	 * <p>This validates that {@code PdslTemporalBlock.tick()} can call
	 * {@link CompiledModel#forward} on successive invocations and rely on
	 * state persisting between them.</p>
	 */
	@Test(timeout = 60000)
	@TestDepth(2)
	public void testStatePersistsAcrossTemporalTick() {
		int delaySamples = 2;
		PackedCollection buffer = new PackedCollection(BUFFER_SIZE);
		buffer.setMem(new double[BUFFER_SIZE]);
		PackedCollection head = new PackedCollection(1);
		head.setMem(new double[]{0.0});

		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parseResource("/pdsl/audio/efx_channel.pdsl");

		Map<String, Object> args = new HashMap<>();
		args.put("signal_size", BUFFER_SIZE);
		args.put("delay_samples", delaySamples);
		args.put("buffer", buffer);
		args.put("head", head);

		TraversalPolicy inputShape = new TraversalPolicy(1, BUFFER_SIZE);
		Block block = loader.buildLayer(program, "efx_delay", inputShape, args);

		Model model = new Model(inputShape);
		model.add(block);
		CompiledModel compiled = model.compile();

		PackedCollection signal1 = createSignal(BUFFER_SIZE, i -> 1.0);
		PackedCollection signal2 = createSignal(BUFFER_SIZE, i -> 2.0);
		PackedCollection[] lastOutput = {null};

		// First tick: wrap forward() as Temporal, run it
		Temporal firstTick = () -> () -> lastOutput[0] = compiled.forward(signal1);
		firstTick.tick().get().run();

		Assert.assertNotNull("First tick output must not be null", lastOutput[0]);
		Assert.assertEquals("First tick output[0] must be 0 (empty delay buffer)",
				0.0, lastOutput[0].toDouble(0), 1e-6);
		Assert.assertEquals("First tick output[1] must be 0 (empty delay buffer)",
				0.0, lastOutput[0].toDouble(1), 1e-6);

		// Second tick: state must carry over — the delay buffer now holds 1.0 values
		Temporal secondTick = () -> () -> lastOutput[0] = compiled.forward(signal2);
		secondTick.tick().get().run();

		Assert.assertNotNull("Second tick output must not be null", lastOutput[0]);
		Assert.assertEquals("Second tick output[0] must be 1.0 (from first-tick delay buffer)",
				1.0, lastOutput[0].toDouble(0), 1e-6);
		Assert.assertEquals("Second tick output[1] must be 1.0 (from first-tick delay buffer)",
				1.0, lastOutput[0].toDouble(1), 1e-6);
	}

	// ---- Test 3: Block→Temporal adapter forward flow ----------------------------------

	/**
	 * Verifies that a minimal {@link Temporal} adapter around
	 * {@link CompiledModel#forward} correctly channels input through to output.
	 *
	 * <p>Uses {@code efx_dry_path} from {@code efx_channel.pdsl}, which applies
	 * {@code scale(dry_level)} to its input — a simple one-parameter operation with
	 * no state. With {@code dry_level=0.5} and an all-ones input, every output
	 * sample must equal 0.5.</p>
	 *
	 * <p>This is the exact execution path that {@code PdslTemporalBlock.tick()} will
	 * use. The test validates:
	 * <ul>
	 *   <li>The adapter compiles and executes without error.</li>
	 *   <li>Output is correctly populated — the forward pass ran.</li>
	 *   <li>No silent failure (all-zeros output) from the Temporal wrapper.</li>
	 * </ul>
	 */
	@Test(timeout = 60000)
	@TestDepth(2)
	public void testBlockToTemporalAdapterForwardFlow() {
		PackedCollection filterCoeffs = new PackedCollection(FILTER_ORDER + 1);
		filterCoeffs.setMem(new double[FILTER_ORDER + 1]);

		PdslLoader loader = new PdslLoader();
		PdslNode.Program program = loader.parseResource("/pdsl/audio/efx_channel.pdsl");

		Map<String, Object> args = new HashMap<>();
		args.put("filter_coeffs", filterCoeffs);
		args.put("dry_level", 0.5);

		TraversalPolicy inputShape = new TraversalPolicy(1, BUFFER_SIZE);
		Block block = loader.buildLayer(program, "efx_dry_path", inputShape, args);

		Model model = new Model(inputShape);
		model.add(block);
		CompiledModel compiled = model.compile();

		PackedCollection input = createSignal(BUFFER_SIZE, i -> 1.0);
		PackedCollection[] output = {null};

		// Minimal Temporal adapter — the core pattern for PdslTemporalBlock.tick()
		Temporal adapter = () -> () -> output[0] = compiled.forward(input);
		adapter.tick().get().run();

		Assert.assertNotNull("Adapter output must not be null", output[0]);
		for (int i = 0; i < BUFFER_SIZE; i++) {
			Assert.assertEquals(
					"Output[" + i + "] must equal input * dry_level (1.0 * 0.5)",
					0.5, output[0].toDouble(i), 1e-6);
		}
	}
}
