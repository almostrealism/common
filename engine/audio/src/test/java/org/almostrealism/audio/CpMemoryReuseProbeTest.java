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

import io.almostrealism.relation.Evaluable;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.time.TemporalFeatures;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Probe: determines whether {@code cp(buffer)} reads the buffer's memory at
 * evaluation time (so a single compiled {@link Evaluable} can be reused after
 * {@code setMem}-ing new data into the same buffer) or bakes the buffer's values
 * at compile time (so new data requires a recompile). This decides whether the
 * batched dispatch's per-tick recompile can be eliminated simply by caching the
 * {@link Evaluable} and reusing input buffers, or whether the kernel inputs must
 * be rebuilt as {@code cv}-argument placeholders.
 */
public class CpMemoryReuseProbeTest extends TestSuiteBase implements CollectionFeatures, TemporalFeatures {

	/**
	 * Verifies that a compiled {@link Evaluable} produced from {@code cp(buffer)}
	 * re-reads the buffer's native memory on each evaluation, so that mutating the
	 * buffer via {@code setMem} is reflected in subsequent evaluations without
	 * recompiling the kernel.
	 */
	@Test(timeout = 60000)
	@TestDepth(1)
	public void cpRereadsBufferMemoryOnReevaluate() {
		PackedCollection buf = new PackedCollection(4);
		buf.setMem(1.0, 2.0, 3.0, 4.0);

		CollectionProducer producer = cp(buf).multiply(c(2.0));
		Evaluable<PackedCollection> evaluable = producer.get();

		PackedCollection first = evaluable.evaluate();
		log("first: " + first.toDouble(0) + "," + first.toDouble(1) + ","
				+ first.toDouble(2) + "," + first.toDouble(3));

		// Mutate the same buffer's memory and re-run the SAME compiled evaluable.
		buf.setMem(10.0, 20.0, 30.0, 40.0);
		PackedCollection second = evaluable.evaluate();
		log("second: " + second.toDouble(0) + "," + second.toDouble(1) + ","
				+ second.toDouble(2) + "," + second.toDouble(3));

		boolean rereadsMemory = Math.abs(second.toDouble(0) - 20.0) < 1e-6
				&& Math.abs(second.toDouble(3) - 80.0) < 1e-6;
		log("cp rereads buffer memory on re-evaluate: " + rereadsMemory);

		// Sanity: the first evaluation reflects the original data either way.
		Assert.assertEquals(2.0, first.toDouble(0), 1e-6);
		Assert.assertEquals(8.0, first.toDouble(3), 1e-6);
		Assert.assertTrue(
				"cp(buffer) did not reread mutated memory on re-evaluate (second[0]="
						+ second.toDouble(0) + ", expected 20.0) — kernel inputs must become "
						+ "cv-argument placeholders for compile-once-run-many",
				rereadsMemory);
	}
}
