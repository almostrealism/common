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

package org.almostrealism.ml;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.audio.DiffusionNoiseScheduler;
import org.almostrealism.optimize.ValueTarget;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;
import java.util.Random;

/**
 * Tests that {@link DiffusionTrainingDataset} produces training samples whose
 * arguments have the shapes a diffusion model declares for its inputs.
 */
public class DiffusionTrainingDatasetTest extends TestSuiteBase {

	/** Number of diffusion steps used by the scheduler in these tests. */
	private static final int STEPS = 10;

	/**
	 * The timestep argument must be a per-batch scalar of shape {@code (batchSize, 1)},
	 * matching the timestep input of a diffusion transformer, for a single-sample batch.
	 */
	@Test(timeout = 60000)
	public void timestepMatchesSingleBatchModelInput() {
		assertTimestepShape(1);
	}

	/**
	 * For a batch of several samples the timestep is repeated in every row, since
	 * the whole batch is noised at the same timestep.
	 */
	@Test(timeout = 60000)
	public void timestepRepeatedAcrossBatch() {
		assertTimestepShape(3);
	}

	/**
	 * Extra arguments follow the timestep, unchanged, in the order they were set.
	 */
	@Test(timeout = 60000)
	public void extraArgumentsFollowTimestep() {
		DiffusionTrainingDataset dataset = dataset(1);
		PackedCollection globalCond = new PackedCollection(1, 4);
		dataset.setExtraArguments(globalCond);

		PackedCollection[] args = dataset.iterator().next().getArguments();
		Assert.assertEquals(2, args.length);
		Assert.assertSame(globalCond, args[1]);
	}

	/**
	 * Checks the timestep argument of the first sample of a dataset whose samples
	 * have the given batch size.
	 */
	private void assertTimestepShape(int batchSize) {
		ValueTarget<PackedCollection> target = dataset(batchSize).iterator().next();
		PackedCollection timestep = target.getArguments()[0];

		Assert.assertEquals(2, timestep.getShape().getDimensions());
		Assert.assertEquals(batchSize, timestep.getShape().length(0));
		Assert.assertEquals(1, timestep.getShape().length(1));

		double[] values = timestep.toArray();
		double t = values[0] * STEPS;
		Assert.assertTrue("Timestep " + values[0] + " is outside [0, 1)", values[0] >= 0.0 && values[0] < 1.0);
		Assert.assertEquals("Timestep is not a whole step fraction", Math.rint(t), t, 1e-5);
		for (double v : values) {
			Assert.assertEquals(values[0], v, 0.0);
		}
	}

	/** Creates a dataset with a single random sample of shape {@code (batchSize, 2, 3)}. */
	private DiffusionTrainingDataset dataset(int batchSize) {
		PackedCollection sample = new PackedCollection(batchSize, 2, 3);
		sample.randnFill(new Random(3));
		return new DiffusionTrainingDataset(List.of(sample),
				new DiffusionNoiseScheduler(STEPS, new Random(1)), 1);
	}
}
