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

package org.almostrealism.collect.computations.test;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Pins the behaviour of {@code subtractIgnoreZero} now that it is built from the shared
 * {@code add(a, minus(b))} path rather than a hand-rolled per-item size check. The operation
 * subtracts element by element but preserves zero wherever the minuend is already zero or the
 * two operands are effectively equal, so no spurious non-zero value is produced by subtracting
 * equal operands. Because the operands now flow through the common size-reconciliation path, a
 * scalar subtrahend broadcasts across a vector minuend instead of being rejected.
 *
 * <p>The machine epsilon constant the operation uses for the "effectively equal" case evaluates
 * to {@code 0.0} in this runtime, so an equal pair yields exactly zero here; the assertions
 * therefore hold on both the equal and the zero-minuend branches.</p>
 *
 * @author Michael Murray
 */
public class SubtractIgnoreZeroTests extends TestSuiteBase {

	/** Equal-shape operands with no zero or equal entries subtract element by element. */
	@Test(timeout = 60000)
	public void subtractsEqualShapeElementwise() {
		try (PackedCollection a = pack(10.0, 20.0, 30.0);
			 PackedCollection b = pack(1.0, 2.0, 3.0);
			 PackedCollection out = subtractIgnoreZero(cp(a), cp(b)).evaluate()) {
			Assert.assertEquals(3, out.getShape().getTotalSize());
			Assert.assertEquals(9.0, out.toDouble(0), 0.001);
			Assert.assertEquals(18.0, out.toDouble(1), 0.001);
			Assert.assertEquals(27.0, out.toDouble(2), 0.001);
		}
	}

	/**
	 * A zero minuend and an equal pair are both preserved as zero, while the remaining entry is
	 * subtracted normally: for {@code a = [5, 0, 7]} and {@code b = [5, 4, 2]} the result is
	 * {@code [0, 0, 5]} — {@code 5 == 5} and {@code a == 0} each ignore the subtraction.
	 */
	@Test(timeout = 60000)
	public void preservesZeroAndEqualOperands() {
		try (PackedCollection a = pack(5.0, 0.0, 7.0);
			 PackedCollection b = pack(5.0, 4.0, 2.0);
			 PackedCollection out = subtractIgnoreZero(cp(a), cp(b)).evaluate()) {
			Assert.assertEquals(3, out.getShape().getTotalSize());
			Assert.assertEquals("5 - 5 is ignored", 0.0, out.toDouble(0), 0.001);
			Assert.assertEquals("zero minuend is preserved", 0.0, out.toDouble(1), 0.001);
			Assert.assertEquals("7 - 2 is subtracted", 5.0, out.toDouble(2), 0.001);
		}
	}

	/**
	 * A scalar subtrahend broadcasts across a vector minuend, which the previous hand-rolled
	 * size check handled as a special case and the shared path now reconciles directly: each
	 * vector entry has the scalar subtracted from it.
	 */
	@Test(timeout = 60000)
	public void scalarSubtrahendBroadcasts() {
		try (PackedCollection a = pack(10.0, 20.0, 30.0);
			 PackedCollection out = subtractIgnoreZero(cp(a), c(5.0)).evaluate()) {
			Assert.assertEquals(3, out.getShape().getTotalSize());
			Assert.assertEquals(5.0, out.toDouble(0), 0.001);
			Assert.assertEquals(15.0, out.toDouble(1), 0.001);
			Assert.assertEquals(25.0, out.toDouble(2), 0.001);
		}
	}
}
