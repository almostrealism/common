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

import io.almostrealism.compute.Process;
import io.almostrealism.profile.OperationProfileNode;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Pins the <em>current</em> behaviour of binary element-wise operations when the two
 * operands have sizes that are not broadcast-compatible in the conventional
 * numerical-library sense.
 *
 * <p><strong>These tests characterise behaviour that is under review; they do not assert a
 * desired contract.</strong> Today a binary element-wise operation built through
 * {@code CollectionFeatures.compute(...)} reconciles mismatched operand sizes by taking the
 * larger operand's shape and reading the smaller operand modulo its own length (see
 * {@code CollectionVariable.getValueAt}, which applies {@code imod(getTotalSize())} to a
 * non-scalar operand, and {@code TraversalAxisAlignment.align}, whose second pass either tiles
 * a divisible operand with {@code repeat(...)} or leaves an indivisible one short to be
 * wrapped). The owner's stated principle is to fail fast on a wrong shape at creation time, so
 * a later change is expected to make several of these operations throw instead. When that
 * happens these assertions should be <em>flipped</em> (to expect an exception), not deleted:
 * each one marks a concrete input the new rule has to make a decision about.</p>
 *
 * <p>Scalar (size&nbsp;1) operands broadcast correctly today and are expected to keep doing so
 * under any rule; {@link #scalarOperandBroadcastsCorrectly()} pins that as the baseline the
 * mismatched cases are contrasted against.</p>
 *
 * @see ClampBroadcastTests
 * @see io.almostrealism.collect.CollectionVariable
 * @see io.almostrealism.collect.TraversalAxisAlignment
 */
public class OperandSizeBroadcastCharacterizationTests extends TestSuiteBase {

	/** A (4) vector, the larger operand in the vector cases. */
	private PackedCollection v() {
		return pack(0.5, 1.5, 2.0, 3.0);
	}

	/** A (3) vector: not broadcast-compatible with a (4) vector. */
	private PackedCollection row() {
		return pack(10.0, 20.0, 30.0);
	}

	/** A (2) vector: a divisor of 4, so it is tiled rather than wrapped. */
	private PackedCollection col() {
		return pack(100.0, 200.0);
	}

	/** A (2, 3) matrix. */
	private PackedCollection m23() {
		return pack(1.0, 2.0, 3.0, 4.0, 5.0, 6.0).reshape(shape(2, 3));
	}

	/**
	 * CURRENT BEHAVIOUR (under review): adding a (3) vector to a (4) vector produces a (4)
	 * result in which the (3) operand is read modulo 3, so index 3 reuses element 0. A
	 * conventional library rejects {@code (4) + (3)} as non-broadcastable.
	 */
	@Test(timeout = 60000)
	public void addVectorRowWrapsModuloThree() {
		PackedCollection out = cp(v()).add(cp(row())).evaluate();

		Assert.assertEquals("result keeps the larger operand's total size",
				4, out.getShape().getTotalSize());
		assertEquals(0.5 + 10.0, out.toDouble(0));
		assertEquals(1.5 + 20.0, out.toDouble(1));
		assertEquals(2.0 + 30.0, out.toDouble(2));
		// row[3 % 3] == row[0] == 10.0 is reused here:
		assertEquals(3.0 + 10.0, out.toDouble(3));
	}

	/**
	 * CURRENT BEHAVIOUR (under review): {@code max} of a (4) and a (3) operand wraps the (3)
	 * operand modulo 3 in exactly the same way {@code add} does, confirming that the wrapping
	 * lives in the shared size-reconciliation path rather than in any one operation.
	 */
	@Test(timeout = 60000)
	public void maxVectorRowWrapsModuloThree() {
		PackedCollection out = max(cp(v()), cp(row())).evaluate();

		Assert.assertEquals(4, out.getShape().getTotalSize());
		assertEquals(Math.max(0.5, 10.0), out.toDouble(0));
		assertEquals(Math.max(1.5, 20.0), out.toDouble(1));
		assertEquals(Math.max(2.0, 30.0), out.toDouble(2));
		assertEquals(Math.max(3.0, 10.0), out.toDouble(3));
	}

	/**
	 * CURRENT BEHAVIOUR (under review): because 2 divides 4, the (2) operand is tiled to
	 * {@code [100, 200, 100, 200]} rather than left short, so the multiply reuses it with a
	 * period of 2. A conventional library rejects {@code (4) * (2)} as non-broadcastable.
	 */
	@Test(timeout = 60000)
	public void multiplyVectorColTilesDivisibleOperand() {
		PackedCollection out = cp(v()).multiply(cp(col())).evaluate();

		Assert.assertEquals(4, out.getShape().getTotalSize());
		assertEquals(0.5 * 100.0, out.toDouble(0));
		assertEquals(1.5 * 200.0, out.toDouble(1));
		assertEquals(2.0 * 100.0, out.toDouble(2));
		assertEquals(3.0 * 200.0, out.toDouble(3));
	}

	/**
	 * CURRENT BEHAVIOUR (under review): a (3) operand against a (2, 3) operand aligns with the
	 * trailing axis, so the same row is added to every row of the matrix. Which axis a vector
	 * aligns with is decided by which dimension its length happens to match.
	 */
	@Test(timeout = 60000)
	public void addMatrixRowAlignsTrailingAxis() {
		PackedCollection out = cp(m23()).add(cp(row())).evaluate();

		Assert.assertEquals(6, out.getShape().getTotalSize());
		double[] expected = {11, 22, 33, 14, 25, 36};
		for (int i = 0; i < expected.length; i++) {
			assertEquals(expected[i], out.toDouble(i));
		}
	}

	/**
	 * CURRENT BEHAVIOUR (under review): a (2) operand against the same (2, 3) operand aligns
	 * with the leading axis instead, so each matrix row is offset by a different element of the
	 * (2) operand. For a square {@code (n, n)} matrix both alignments would be possible and the
	 * caller cannot say which they would get — that ambiguity is the second defect under review.
	 */
	@Test(timeout = 60000)
	public void addMatrixColAlignsLeadingAxis() {
		PackedCollection out = cp(m23()).add(cp(col())).evaluate();

		Assert.assertEquals(6, out.getShape().getTotalSize());
		double[] expected = {101, 102, 103, 204, 205, 206};
		for (int i = 0; i < expected.length; i++) {
			assertEquals(expected[i], out.toDouble(i));
		}
	}

	/**
	 * BASELINE (expected to survive any rule): a scalar broadcasts across every element of a
	 * vector, in either operand position, and across the rows of a traversed matrix. This is the
	 * behaviour the mismatched-size cases above are contrasted against.
	 */
	@Test(timeout = 60000)
	public void scalarOperandBroadcastsCorrectly() {
		PackedCollection maxOut = max(cp(v()), c(1.7)).evaluate();
		Assert.assertEquals(4, maxOut.getShape().getTotalSize());
		for (int i = 0; i < 4; i++) {
			assertEquals(Math.max(v().toDouble(i), 1.7), maxOut.toDouble(i));
		}

		PackedCollection divOut = c(6.0).divide(cp(v())).evaluate();
		Assert.assertEquals(4, divOut.getShape().getTotalSize());
		for (int i = 0; i < 4; i++) {
			assertEquals(6.0 / v().toDouble(i), divOut.toDouble(i));
		}

		PackedCollection addOut = cp(m23()).traverse(1).add(c(1.0)).evaluate();
		Assert.assertEquals(6, addOut.getShape().getTotalSize());
		for (int i = 0; i < 6; i++) {
			assertEquals(m23().toDouble(i) + 1.0, addOut.toDouble(i));
		}
	}

	/**
	 * Emits a profiled run of the wrapping {@code (4) + (3)} case so the generated kernel source
	 * and its argument bindings can be read with {@code ar-profile-analyzer}. The profile is
	 * written under {@code results/} alongside the other profiles this module produces.
	 *
	 * @throws IOException if the profile directory cannot be created or the profile cannot be saved
	 */
	@Test(timeout = 60000)
	public void profileVectorRowWrap() throws IOException {
		OperationProfileNode profile =
				initKernelMetrics(new OperationProfileNode("operandSizeWrapVectorRowAdd"));

		PackedCollection out;
		try {
			CollectionProducer sum = cp(v()).add(cp(row()));
			out = Process.optimized(sum).get().evaluate();
		} finally {
			Hardware.getLocalHardware().assignProfile(null);
			Files.createDirectories(Path.of("results"));
			profile.save("results/operandSizeWrapVectorRowAdd.xml");
		}

		Assert.assertEquals(4, out.getShape().getTotalSize());
		assertEquals(3.0 + 10.0, out.toDouble(3));
	}
}
