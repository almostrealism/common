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

public class AdjustableDelayCellTest extends SineWaveCellTest {
	public static int DELAY_FRAMES = 1000;

	@BeforeClass
	public static void init() {
		// AcceleratedTimeSeries.defaultCacheLevel = MemoryBankAdapter.CacheLevel.ALL;
	}

	@AfterClass
	public static void shutdown() {
		AcceleratedTimeSeries.defaultCacheLevel = MemoryBankAdapter.CacheLevel.NONE;
	}

	protected AdjustableDelayCell adjustableDelay() {
		return new AdjustableDelayCell(OutputLine.sampleRate, ((double) DELAY_FRAMES) / OutputLine.sampleRate);
	}

	public OperationList computation(AdjustableDelayCell delay) {
		PackedCollection multiplier = new PackedCollection(1);
		multiplier.setMem(0.1);
		CollectionProducer product = c(1.0).multiply(p(multiplier));

		OperationList ops = new OperationList("Delay Push and Tick");
		ops.add(delay.push(product));
		ops.add(delay.tick());
		return ops;
	}

	@Test
	public void computationSeparately() {
		AdjustableDelayCell delay = adjustableDelay();
		OperationList ops = computation(delay);

		Runnable push = ops.get(0).get();
		Runnable tick = ops.get(1).get();

		IntStream.range(0, 25).forEach(i -> {
			push.run();
			tick.run();
		});
		assertions(delay);
	}

	@Test
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

	@Test
	public void computationLoop() {
		AdjustableDelayCell delay = adjustableDelay();
		OperationList ops = computation(delay);

		Runnable op = lp(ops, 25).get();
		op.run();
		assertions(delay);
	}

	protected void assertions(AdjustableDelayCell delay) {
		CursorPair cursors = delay.getCursors();
		assertEquals(25.0, cursors.getCursor());
		assertEquals(DELAY_FRAMES + 25, cursors.getDelayCursor());

		AcceleratedTimeSeries buffer = delay.getBuffer();
		TemporalScalar t = buffer.valueAt(delay.getDelay().get().evaluate().toDouble() * OutputLine.sampleRate);
		System.out.println(t);
		assertEquals(0.1, t.getValue());
	}

	@Test
	public void withAdjustableDelayCell() {
		AdjustableDelayCell delay = adjustableDelay();
		delay.setReceptor(loggingReceptor());

		SineWaveCell cell = cell();
		cell.setReceptor(delay);

		WaveOutput output = new WaveOutput(new File("results/adjustable-delay-cell-test.wav"));

		cell.setReceptor(delay);
		delay.setReceptor(output.getWriter(0));

		Runnable push = cell.push(c(0.0)).get();
		Runnable tick = cell.tick().get();
		IntStream.range(0, SineWaveCellTest.DURATION_FRAMES).forEach(i -> {
			push.run();
			tick.run();
			if ((i + 1) % 1000 == 0) System.out.println("AdjustableDelayCellTest: " + (i + 1) + " iterations");
		});

		System.out.println("AdjustableDelayCellTest: Writing WAV...");
		output.write().get().run();
		System.out.println("AdjustableDelayCellTest: Done");
	}
}
