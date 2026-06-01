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

package org.almostrealism.audio.filter.test;

import org.almostrealism.audio.WaveOutput;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.sources.SineWaveCell;
import org.almostrealism.audio.test.SineWaveCellTest;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.AdjustableDelayCell;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.hardware.mem.MemoryBankAdapter;
import org.almostrealism.time.AcceleratedTimeSeries;
import org.almostrealism.time.CursorPair;
import org.almostrealism.time.TemporalScalar;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * Tests for {@link org.almostrealism.graph.AdjustableDelayCell}.
 * Verifies delay cell computation, push/tick operations, and integration with audio cells.
 *
 * @see org.almostrealism.graph.AdjustableDelayCell
 */
public class AdjustableDelayCellTest extends SineWaveCellTest {

	/** Number of frames to delay in tests. */
	public static int DELAY_FRAMES = 1000;

	/**
	 * Initializes test environment before test class runs.
	 * Currently empty but allows for future test setup.
	 */
	@BeforeClass
	public static void init() {
		// AcceleratedTimeSeries.defaultCacheLevel = MemoryBankAdapter.CacheLevel.ALL;
	}

	/**
	 * Cleans up test environment after test class completes.
	 * Resets the cache level for AcceleratedTimeSeries.
	 */
	@AfterClass
	public static void shutdown() {
		AcceleratedTimeSeries.defaultCacheLevel = MemoryBankAdapter.CacheLevel.NONE;
	}

	/**
	 * Creates a new AdjustableDelayCell with delay duration calculated from
	 * {@link #DELAY_FRAMES} and the sample rate from {@link OutputLine}.
	 *
	 * @return a new AdjustableDelayCell configured with the default delay
	 */
	protected AdjustableDelayCell adjustableDelay() {
		return new AdjustableDelayCell(OutputLine.sampleRate, ((double) DELAY_FRAMES) / OutputLine.sampleRate);
	}

	/**
	 * Creates an {@link OperationList} representing the computation for a delay cell,
	 * consisting of a push operation with a scaled multiplier and a tick operation.
	 *
	 * @param delay the AdjustableDelayCell to create operations for
	 * @return an OperationList containing the push and tick operations
	 */
	public OperationList computation(AdjustableDelayCell delay) {
		PackedCollection multiplier = new PackedCollection(1);
		multiplier.setMem(0.1);
		CollectionProducer product = c(1.0).multiply(p(multiplier));

		OperationList ops = new OperationList("Delay Push and Tick");
		ops.add(delay.push(product));
		ops.add(delay.tick());
		return ops;
	}

	/**
	 * Tests push and tick operations executed separately, verifying that
	 * the delay cell correctly processes audio through its buffer.
	 */
	@Test(timeout = 60000)
	public void computationSeparately() {
		AdjustableDelayCell delay = adjustableDelay();
		Supplier<Runnable> setupOp = delay.setup();
		OperationList ops = computation(delay);

		Runnable setup = setupOp.get();
		Runnable op = ops.get();

		setup.run();
		IntStream.range(0, 25).forEach(i -> op.run());
		assertions(delay);
	}

	/**
	 * Tests push and tick operations executed together as a single operation,
	 * verifying that the delay cell processes audio correctly.
	 */
	@Test(timeout = 60000)
	public void computationTogether() {
		AdjustableDelayCell delay = adjustableDelay();
		Supplier<Runnable> setupOp = delay.setup();
		OperationList ops = computation(delay);

		Runnable setup = setupOp.get();
		Runnable op = ops.get();

		setup.run();
		IntStream.range(0, 25).forEach(i -> op.run());
		assertions(delay);
	}

	/**
	 * Tests push and tick operations executed in a loop using lp() operator,
	 * verifying the delay cell processes audio correctly with looped execution.
	 */
	@Test(timeout = 60000)
	public void computationLoop() {
		AdjustableDelayCell delay = adjustableDelay();
		delay.setup().get().run();
		OperationList ops = computation(delay);

		Runnable op = lp(ops, 25).get();
		op.run();
		assertions(delay);
	}

	/**
	 * Validates that the delay cell has produced expected cursor positions and buffer values
	 * after 25 iterations. Checks that the cursor advanced to frame 25, the delay cursor
	 * advanced to {@link #DELAY_FRAMES} + 25, and the buffer contains the expected scaled value.
	 *
	 * @param delay the AdjustableDelayCell to validate
	 */
	protected void assertions(AdjustableDelayCell delay) {
		CursorPair cursors = delay.getCursors();
		assertEquals(25.0, cursors.getCursor());
		assertEquals(DELAY_FRAMES + 25, cursors.getDelayCursor());

		AcceleratedTimeSeries buffer = delay.getBuffer();
		TemporalScalar t = buffer.valueAt(delay.getDelay().get().evaluate().toDouble() * OutputLine.sampleRate);
		log(String.valueOf(t));
		assertEquals(0.1, t.getValue());
	}

	/**
	 * Tests the AdjustableDelayCell integrated with a SineWaveCell,
	 * writing output to a WAV file for verification.
	 */
	@Test(timeout = 60000)
	public void withAdjustableDelayCell() {
		AdjustableDelayCell delay = adjustableDelay();
		delay.setReceptor(loggingReceptor());

		SineWaveCell cell = cell();
		cell.setReceptor(delay);

		WaveOutput output = new WaveOutput(new File("results/adjustable-delay-cell-test.wav"));

		cell.setReceptor(delay);
		delay.setReceptor(output.getWriter(0));

		OperationList ops = new OperationList("SineWaveCell Push and Tick");
		ops.add(cell.push(c(0.0)));
		ops.add(cell.tick());
		lp(ops, SineWaveCellTest.DURATION_FRAMES).get().run();

		log("AdjustableDelayCellTest: Writing WAV...");
		output.write().get().run();
		log("AdjustableDelayCellTest: Done");
	}
}
