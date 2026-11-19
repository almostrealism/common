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
 * Pins the contract of binary element-wise operations when the two operands have different
 * sizes: trailing-axis broadcasting. Comparing the two shapes axis by axis from the trailing
 * end, each pair of lengths must be equal or one of them must be 1 (a length-1 axis broadcasts,
 * and a missing leading axis counts as length 1); a scalar broadcasts against anything. A pair
 * that is not compatible by this rule is rejected when the computation is created, rather than
 * being tiled or read modulo its own length.
 *
 * <p>Each accepted case asserts both the result shape and the per-element values, using operands
 * whose elements differ so that a wrong broadcast axis would change the result. Each rejected
 * case asserts that building the computation throws.</p>
 *
 * @see ClampBroadcastTests
 * @see io.almostrealism.collect.TraversalPolicy#isBroadcastCompatible
 * @see io.almostrealism.collect.TraversalAxisAlignment
 */
public class OperandSizeBroadcastCharacterizationTests extends TestSuiteBase {

	/** A (4) vector. */
	private PackedCollection v() {
		return pack(0.5, 1.5, 2.0, 3.0);
	}

	/** A (3) vector: not broadcast-compatible with a (4) vector. */
	private PackedCollection row() {
		return pack(10.0, 20.0, 30.0);
	}

	/** A (2) vector: a divisor of 4, but still not broadcast-compatible with a (4) vector. */
	private PackedCollection col() {
		return pack(100.0, 200.0);
	}

	/** A (2, 3) matrix. */
	private PackedCollection m23() {
		return pack(1.0, 2.0, 3.0, 4.0, 5.0, 6.0).reshape(shape(2, 3));
	}

	/**
	 * Asserts that building the given computation throws, because its operands are not
	 * trailing-axis broadcast compatible.
	 *
	 * @param build the computation builder expected to be rejected
	 */
	private void assertRejected(Runnable build) {
		try {
			build.run();
			Assert.fail("Expected a non-broadcastable shape pair to be rejected at creation");
		} catch (IllegalArgumentException expected) {
			// a non-broadcastable pair is rejected when the computation is created
		}
	}

	/**
	 * A (3) row vector broadcasts across the trailing axis of a (2, 3) matrix: the same row is
	 * added to every matrix row. The result keeps the matrix shape.
	 */
	@Test(timeout = 60000)
	public void rowBroadcastsAcrossTrailingAxis() {
		PackedCollection out = cp(m23()).add(cp(row())).evaluate();

		Assert.assertEquals(6, out.getShape().getTotalSize());
		Assert.assertEquals(2, out.getShape().length(0));
		Assert.assertEquals(3, out.getShape().length(1));
		double[] expected = {11, 22, 33, 14, 25, 36};
		for (int i = 0; i < expected.length; i++) {
			assertEquals(expected[i], out.toDouble(i));
		}
	}

	/**
	 * A (2, 1) column broadcasts across the trailing axis of a (2, 3) matrix: a per-row value is
	 * added to every element of its row. This is the explicit per-row form a caller writes with
	 * {@code reshape(2, 1)}.
	 */
	@Test(timeout = 60000)
	public void columnBroadcastsPerRow() {
		PackedCollection column = pack(10.0, 20.0).reshape(shape(2, 1));
		PackedCollection out = cp(m23()).add(cp(column)).evaluate();

		Assert.assertEquals(6, out.getShape().getTotalSize());
		Assert.assertEquals(2, out.getShape().length(0));
		Assert.assertEquals(3, out.getShape().length(1));
		double[] expected = {11, 12, 13, 24, 25, 26};
		for (int i = 0; i < expected.length; i++) {
			assertEquals(expected[i], out.toDouble(i));
		}
	}

	/**
	 * A (2) vector against a square (2, 2) matrix resolves to the trailing axis — the whole
	 * vector added to every row — rather than the coincidentally equal leading axis.
	 */
	@Test(timeout = 60000)
	public void squareMatrixResolvesTrailingAxis() {
		PackedCollection square = pack(1.0, 2.0, 3.0, 4.0).reshape(shape(2, 2));
		PackedCollection vec = pack(10.0, 20.0);
		PackedCollection out = cp(square).add(cp(vec)).evaluate();

		Assert.assertEquals(4, out.getShape().getTotalSize());
		Assert.assertEquals(2, out.getShape().length(0));
		Assert.assertEquals(2, out.getShape().length(1));
		double[] expected = {11, 22, 13, 24};
		for (int i = 0; i < expected.length; i++) {
			assertEquals(expected[i], out.toDouble(i));
		}
	}

	/**
	 * A trailing size-1 reduction broadcast — a (b, k, N) tensor divided or offset by a
	 * (b, k, 1) per-row value — is the shape family normalization and softmax rely on, and it is
	 * accepted unchanged.
	 */
	@Test(timeout = 60000)
	public void trailingSizeOneReductionBroadcasts() {
		PackedCollection a = pack(1.0, 2.0, 3.0, 4.0, 5.0, 6.0).reshape(shape(1, 2, 3));
		PackedCollection b = pack(10.0, 20.0).reshape(shape(1, 2, 1));
		PackedCollection out = cp(a).add(cp(b)).evaluate();

		Assert.assertEquals(6, out.getShape().getTotalSize());
		Assert.assertEquals(3, out.getShape().getDimensions());
		double[] expected = {11, 12, 13, 24, 25, 26};
		for (int i = 0; i < expected.length; i++) {
			assertEquals(expected[i], out.toDouble(i));
		}
	}

	/**
	 * Operands of equal total size are combined positionally when each exposes the common size at
	 * a traversal axis, so a (2, 3) matrix combines with a (6) vector element by element.
	 */
	@Test(timeout = 60000)
	public void equalTotalSizeCombinesPositionally() {
		PackedCollection flat = pack(10.0, 20.0, 30.0, 40.0, 50.0, 60.0);
		PackedCollection out = cp(m23()).add(cp(flat)).evaluate();

		Assert.assertEquals(6, out.getShape().getTotalSize());
		double[] expected = {11, 22, 33, 44, 55, 66};
		for (int i = 0; i < expected.length; i++) {
			assertEquals(expected[i], out.toDouble(i));
		}
	}

	/**
	 * Adding a (3) vector to a (4) vector is rejected: a conventional library rejects
	 * {@code (4) + (3)} as non-broadcastable, and so does this one, rather than reading the (3)
	 * operand modulo 3.
	 */
	@Test(timeout = 60000)
	public void addVectorRowRejectsIndivisible() {
		assertRejected(() -> cp(v()).add(cp(row())));
	}

	/**
	 * {@code max} of a (4) and a (3) operand is rejected in exactly the same way {@code add} is,
	 * confirming the rejection lives in the shared size-reconciliation path rather than in any one
	 * operation.
	 */
	@Test(timeout = 60000)
	public void maxVectorRowRejectsIndivisible() {
		assertRejected(() -> max(cp(v()), cp(row())));
	}

	/**
	 * Multiplying a (4) by a (2) operand is rejected even though 2 divides 4: Rule B does not tile
	 * an operand along an axis that is not length 1.
	 */
	@Test(timeout = 60000)
	public void multiplyVectorColRejectsDivisibleTile() {
		assertRejected(() -> cp(v()).multiply(cp(col())));
	}

	/**
	 * A bare (2) vector against a (2, 3) matrix is rejected: its trailing length 2 does not match
	 * the matrix's trailing length 3 and is not 1. The caller writes {@code reshape(2, 1)} to say
	 * the value is per-row.
	 */
	@Test(timeout = 60000)
	public void bareVectorAgainstMatrixRejected() {
		assertRejected(() -> cp(m23()).add(cp(col())));
	}

	/**
	 * A scalar broadcasts across every element of a vector, in either operand position, and across
	 * the rows of a traversed matrix. This is the behaviour every rule keeps.
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
	 * Emits a profiled run of the accepted per-row broadcast {@code (2, 3) + (2, 1)} so the
	 * generated kernel source and its argument bindings can be read with {@code ar-profile-analyzer}.
	 *
	 * @throws IOException if the profile directory cannot be created or the profile cannot be saved
	 */
	@Test(timeout = 60000)
	public void profileRowBroadcast() throws IOException {
		OperationProfileNode profile =
				initKernelMetrics(new OperationProfileNode("operandSizeRowBroadcastAdd"));

		PackedCollection out;
		try {
			PackedCollection column = pack(10.0, 20.0).reshape(shape(2, 1));
			CollectionProducer sum = cp(m23()).add(cp(column));
			out = Process.optimized(sum).get().evaluate();
		} finally {
			Hardware.getLocalHardware().assignProfile(null);
			Files.createDirectories(Path.of("results"));
			profile.save("results/operandSizeRowBroadcastAdd.xml");
		}

		Assert.assertEquals(6, out.getShape().getTotalSize());
		assertEquals(26.0, out.toDouble(5));
	}
}
