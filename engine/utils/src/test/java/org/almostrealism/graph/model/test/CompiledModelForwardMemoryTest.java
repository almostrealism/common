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

package org.almostrealism.graph.model.test;

import io.almostrealism.code.Memory;
import io.almostrealism.code.MemoryProvider;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.mem.HardwareMemoryProvider;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Verifies that repeated inference through a {@link CompiledModel} does not keep the memory of
 * earlier passes alive: once the inputs of finished passes are released and a collection has run,
 * the native memory in use must not grow with the number of passes.
 */
public class CompiledModelForwardMemoryTest extends TestSuiteBase {
	/** Size of the model input, large enough that a retained per-pass buffer is measurable. */
	private static final int INPUT_SIZE = 16384;

	/** Size of the hidden layer. */
	private static final int HIDDEN_SIZE = 64;

	/**
	 * Runs many forward passes, each with a new input that is destroyed after the pass, and
	 * checks that neither the number of live native blocks nor the live native bytes grow with
	 * the passes: fewer than one block per four passes, and fewer bytes than a quarter of the
	 * passes' inputs at single precision, so that retaining each pass's input, or any per-pass
	 * buffer of comparable size, fails the test.
	 */
	@Test(timeout = 600000)
	public void forwardPassesDoNotAccumulateMemory() {
		Model model = new Model(shape(INPUT_SIZE));
		CellularLayer first = dense(INPUT_SIZE, HIDDEN_SIZE).apply(shape(INPUT_SIZE));
		CellularLayer second = dense(HIDDEN_SIZE, 1).apply(shape(HIDDEN_SIZE));
		first.getWeights().forEach(w -> w.fill(0.001));
		second.getWeights().forEach(w -> w.fill(0.01));
		model.add(first);
		model.add(second);

		CompiledModel compiled = model.compile(false);

		try {
			runPasses(compiled, 5);
			int before = liveBlocks();
			long beforeBytes = liveBytes();

			int passes = 40;
			runPasses(compiled, passes);
			int after = liveBlocks();
			long afterBytes = liveBytes();

			log("liveBlocks before=" + before + " after=" + after +
					" liveBytes before=" + beforeBytes + " after=" + afterBytes);
			String growth = "Native memory grew by " + (after - before) + " blocks (" +
					(afterBytes - beforeBytes) + " bytes) over " + passes + " passes";
			Assert.assertTrue(growth, after - before < passes / 4);
			Assert.assertTrue(growth, afterBytes - beforeBytes < (long) (passes / 4) * INPUT_SIZE * Float.BYTES);
		} finally {
			compiled.destroy();
		}
	}

	/**
	 * Runs the given number of forward passes, each with a new input it destroys afterwards.
	 *
	 * @param compiled the compiled model
	 * @param passes   the number of passes
	 */
	private void runPasses(CompiledModel compiled, int passes) {
		for (int i = 0; i < passes; i++) {
			PackedCollection input = new PackedCollection(shape(INPUT_SIZE)).fill(0.5);
			PackedCollection output = compiled.forward(input);
			Assert.assertFalse(Double.isNaN(output.toDouble(0)));
			input.destroy();
		}
	}

	/** Returns the number of live native blocks across all providers, after collecting garbage. */
	private int liveBlocks() {
		collect();
		int count = 0;
		for (HardwareMemoryProvider<?> provider : providers()) count += provider.getAllocatedCount();
		return count;
	}

	/** Returns the number of live native bytes across all providers, after collecting garbage. */
	private long liveBytes() {
		collect();
		long bytes = 0;
		for (HardwareMemoryProvider<?> provider : providers()) {
			bytes += provider.getAllocated().stream().mapToLong(r -> r.getSize()).sum();
		}
		return bytes;
	}

	/** Requests several collections, giving released memory time to be returned. */
	private void collect() {
		for (int i = 0; i < 4; i++) {
			System.gc();
			try {
				Thread.sleep(200);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	/** Returns the hardware memory providers of the local hardware. */
	private List<HardwareMemoryProvider<?>> providers() {
		List<HardwareMemoryProvider<?>> result = new ArrayList<>();
		for (MemoryProvider<? extends Memory> provider :
				Hardware.getLocalHardware().getDataContext().getMemoryProviders()) {
			if (provider instanceof HardwareMemoryProvider) result.add((HardwareMemoryProvider<?>) provider);
		}
		return result;
	}
}
