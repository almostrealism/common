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

package org.almostrealism.graph.test;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.CollectionCachedStateCell;
import org.almostrealism.graph.Receptor;
import org.almostrealism.graph.SummationCell;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for the {@link org.almostrealism.graph.CachedStateCell} SummationCell
 * optimization that bypasses the intermediate outValue copy when the downstream
 * receptor is a {@link SummationCell}.
 *
 * <p>The optimization path in {@code CachedStateCell.tick()} detects when the
 * receptor is a SummationCell and directly pushes cachedValue to the receptor,
 * eliminating the copy to outValue. These tests verify both the standard
 * and optimized paths produce correct results.</p>
 *
 * @see org.almostrealism.graph.CachedStateCell
 * @see SummationCell
 */
public class CachedStateCellOptimizationTest extends TestSuiteBase {

	/**
	 * Verifies the standard CachedStateCell tick path:
	 * push a value, tick, verify the value propagates to a non-SummationCell receptor.
	 */
	@Test(timeout = 30000)
	public void standardPathWithNonSummationReceptor() {
		CollectionCachedStateCell source = new CollectionCachedStateCell();
		CollectionCachedStateCell downstream = new CollectionCachedStateCell();

		// Wire source -> downstream (non-SummationCell receptor)
		source.setReceptor(downstream);

		OperationList ops = new OperationList("standardPath");
		ops.add(source.setup());
		ops.add(downstream.setup());

		// Push value 5.0 into source's cache
		ops.add(source.push(c(5.0)));
		// Tick source: should copy cached->out, reset cached, push out->downstream
		ops.add(source.tick());
		ops.get().run();

		// Downstream should have received 5.0 in its cache
		Assert.assertEquals("Downstream should receive value via standard path",
				5.0, downstream.getCachedValue().toDouble(0), 1e-10);

		// Source's cache should be reset to 0
		Assert.assertEquals("Source cache should be reset after tick",
				0.0, source.getCachedValue().toDouble(0), 1e-10);
	}

	/**
	 * Verifies the optimized CachedStateCell tick path with a SummationCell receptor.
	 * The optimization bypasses the intermediate outValue copy and pushes cachedValue
	 * directly to the SummationCell for accumulation.
	 */
	@Test(timeout = 30000)
	public void optimizedPathWithSummationReceptor() {
		CollectionCachedStateCell source = new CollectionCachedStateCell();
		SummationCell accumulator = new SummationCell();

		// Wire source -> accumulator (SummationCell = optimized path)
		source.setReceptor(accumulator);

		OperationList ops = new OperationList("optimizedPath");
		ops.add(source.setup());
		ops.add(accumulator.setup());

		// Push value 7.0 into source's cache
		ops.add(source.push(c(7.0)));
		// Tick source: optimized path pushes cached directly to SummationCell
		ops.add(source.tick());
		ops.get().run();

		// Accumulator should have received 7.0
		Assert.assertEquals("Accumulator should receive value via optimized path",
				7.0, accumulator.getCachedValue().toDouble(0), 1e-10);

		// Source's cache should be reset to 0
		Assert.assertEquals("Source cache should be reset after tick",
				0.0, source.getCachedValue().toDouble(0), 1e-10);
	}

	/**
	 * Verifies that multiple CachedStateCell instances pushing to the same
	 * SummationCell correctly accumulate their values.
	 */
	@Test(timeout = 30000)
	public void multipleSourcesToSameSummationCell() {
		CollectionCachedStateCell sourceA = new CollectionCachedStateCell();
		CollectionCachedStateCell sourceB = new CollectionCachedStateCell();
		SummationCell accumulator = new SummationCell();

		// Both sources wire to the same SummationCell
		sourceA.setReceptor(accumulator);
		sourceB.setReceptor(accumulator);

		OperationList setup = new OperationList("setup");
		setup.add(sourceA.setup());
		setup.add(sourceB.setup());
		setup.add(accumulator.setup());
		setup.get().run();

		// Push different values into each source
		OperationList pushAndTick = new OperationList("pushAndTick");
		pushAndTick.add(sourceA.push(c(3.0)));
		pushAndTick.add(sourceB.push(c(4.0)));
		pushAndTick.add(sourceA.tick());
		pushAndTick.add(sourceB.tick());
		pushAndTick.get().run();

		// Accumulator should have 3.0 + 4.0 = 7.0
		Assert.assertEquals("Accumulator should sum both sources",
				7.0, accumulator.getCachedValue().toDouble(0), 1e-10);
	}

	/**
	 * Verifies that the optimized and standard paths produce equivalent results
	 * across multiple tick cycles.
	 */
	@Test(timeout = 30000)
	public void optimizedAndStandardPathsEquivalent() {
		// Standard path
		CollectionCachedStateCell standardSource = new CollectionCachedStateCell();
		CollectionCachedStateCell standardDownstream = new CollectionCachedStateCell();
		standardSource.setReceptor(standardDownstream);

		// Optimized path
		CollectionCachedStateCell optimizedSource = new CollectionCachedStateCell();
		SummationCell optimizedDownstream = new SummationCell();
		optimizedSource.setReceptor(optimizedDownstream);

		// Setup both
		OperationList setup = new OperationList("setup");
		setup.add(standardSource.setup());
		setup.add(standardDownstream.setup());
		setup.add(optimizedSource.setup());
		setup.add(optimizedDownstream.setup());
		setup.get().run();

		// Run multiple cycles with the same value
		double testValue = 2.5;
		for (int cycle = 0; cycle < 3; cycle++) {
			OperationList ops = new OperationList("cycle-" + cycle);
			ops.add(standardSource.push(c(testValue)));
			ops.add(optimizedSource.push(c(testValue)));
			ops.add(standardSource.tick());
			ops.add(optimizedSource.tick());
			ops.get().run();

			Assert.assertEquals(
					"Standard and optimized paths should produce same result in cycle " + cycle,
					standardDownstream.getCachedValue().toDouble(0),
					optimizedDownstream.getCachedValue().toDouble(0),
					1e-10);

			// Reset downstream for next cycle
			OperationList reset = new OperationList("reset");
			reset.add(standardDownstream.setup());
			reset.add(optimizedDownstream.setup());
			reset.get().run();
		}
	}
}
