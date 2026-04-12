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

package org.almostrealism.collect.computations.test;

import io.almostrealism.compute.Process;
import io.almostrealism.uml.Signature;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.Arrays;
import java.util.stream.IntStream;

public class AggregatedComputationTests extends TestSuiteBase {

	boolean enableOptimization = false;

	@Test(timeout = 120000)
	public void mediumSum() {
		int r = 1024;
		int c = 1024;

		PackedCollection a = new PackedCollection(r, c);

		PackedCollection out = cp(a).sum(1).evaluate();

		for (int i = 0; i < r; i++) {
			assertEquals(
					a.range(shape(c), i * c).doubleStream().sum(),
					out.toDouble(i));
		}
	}

	@Test(timeout = 120000)
	@TestDepth(1)
	public void largeSum() {
		int w = 257;
		int h = 8192;
		int d = 1024;

		PackedCollection a = new PackedCollection(shape(h, d))
				.randFill();
		PackedCollection b = new PackedCollection(shape(w, d))
				.randFill();

		CollectionProducer sum =
				multiply(cp(a).repeat(w).each(),
							cp(b).traverse(1).repeat(h).each())
						.traverse(2)
						.sum();
		log(Signature.of(sum));

		PackedCollection out = enableOptimization ?
				Process.optimized(sum).get().evaluate() : sum.evaluate();
		log("NaN Count = " + out.count(Double::isNaN));

		double[] data = out.toArray();

		int[] nanIndices = IntStream.range(0, data.length)
						.filter(i -> Double.isNaN(data[i]))
								.toArray();
		log(Arrays.toString(nanIndices));

		assertEquals(0, out.count(Double::isNaN));

		// Verify correctness of specific elements by matching the kernel's
		// index arithmetic: for global_id g, the kernel reads
		//   a[(g * d + k) % (h * d)]  and  b[(g * d + k) / (h * d) * d + k % d]
		// which means a_row = g % h, b_row = g / h.
		// The overflow boundary is at g >= h * (w-1) = 8192 * 256 = 2097152,
		// i.e. the last b_row (b_row = 256 = w-1).
		int[] checkGlobalIds = {
			0,                    // a_row=0, b_row=0
			h / 2,               // a_row=h/2, b_row=0
			h - 1,               // a_row=h-1, b_row=0
			h * (w / 2),         // a_row=0, b_row=w/2
			h * (w / 2) + h / 2, // a_row=h/2, b_row=w/2
			h * (w - 1),         // a_row=0, b_row=w-1  (overflow zone)
			h * (w - 1) + h / 2, // a_row=h/2, b_row=w-1  (overflow zone)
			h * w - 1,           // a_row=h-1, b_row=w-1  (overflow zone, last element)
		};

		for (int g : checkGlobalIds) {
			int aRow = g % h;
			int bRow = g / h;
			double expected = 0.0;
			for (int k = 0; k < d; k++) {
				expected += a.toDouble(aRow * d + k) * b.toDouble(bRow * d + k);
			}

			double actual = data[g];
			log("out[g=" + g + " a=" + aRow + " b=" + bRow + "] expected=" + expected + " actual=" + actual);
			// Use relative tolerance — FP accumulation order differs between
			// the Java reference loop and the generated kernel, but the overflow
			// bug produces errors of ~10% or more, so 0.1% catches it reliably.
			double tol = Math.max(1e-4, Math.abs(expected) * 1e-3);
			assertEquals("out[g=" + g + " a=" + aRow + " b=" + bRow + "]", expected, actual, tol);
		}
	}
}
