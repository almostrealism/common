/*
 * Copyright 2021 Michael Murray
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

import org.almostrealism.algebra.Scalar;
import org.almostrealism.graph.ScalarCachedStateCell;
import org.almostrealism.hardware.DynamicAcceleratedOperation;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.graph.CellPair;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

public class CellPairTest implements TestFeatures {

	@Test
	public void scalarCachedStatePair() {
		ScalarCachedStateCell cellA = new ScalarCachedStateCell();
		ScalarCachedStateCell cellB = new ScalarCachedStateCell();

		CellPair<Scalar> pair = new CellPair<>(cellA, cellB, v -> scalar(3.0), v -> scalarsMultiply(v, scalar(2.0)));
		pair.init();

		// A = 6
		DynamicAcceleratedOperation op = (DynamicAcceleratedOperation) cellA.push(v(6.0)).get();
		System.out.println("cellA.push:");
		System.out.println(op.getFunctionDefinition());
		op.run();

		// B = 9
		op = (DynamicAcceleratedOperation) cellB.push(v(9.0)).get();
		System.out.println("cellB.push:");
		System.out.println(op.getFunctionDefinition());
		op.run();

		assertEquals(6.0, cellA.getCachedValue());
		assertEquals(9.0, cellB.getCachedValue());

		// A(6) -> Pair(*2) -> B = 12
		op = (DynamicAcceleratedOperation) cellA.tick().get();
		System.out.println("cellA.tick:");
		System.out.println(op.getFunctionDefinition());
		op.run();

		assertEquals(12.0, cellB.getCachedValue());
		assertEquals(0.0, cellA.getCachedValue());

		// B(6) -> Pair(3.0) -> A = 3.0
		op = (DynamicAcceleratedOperation) cellB.tick().get();
		System.out.println("cellB.tick:");
		System.out.println(op.getFunctionDefinition());
		op.run();

		assertEquals(3.0, cellA.getCachedValue());
		assertEquals(0.0, cellB.getCachedValue());
	}

	@Test
	public void scalarCachedStatePairOperationList() {
		ScalarCachedStateCell cellA = new ScalarCachedStateCell();
		ScalarCachedStateCell cellB = new ScalarCachedStateCell();

		CellPair<Scalar> pair = new CellPair<>(cellA, cellB, v -> scalar(3.0), v -> scalarsMultiply(v, scalar(2.0)));
		pair.init();

		OperationList ops = new OperationList("Cell Pushes and Ticks");
		ops.add(cellA.push(v(6.0))); // A = 6
		ops.add(cellB.push(v(9.0))); // B = 9
		ops.add(cellA.tick());             // A(6) -> Pair(*2) -> B = 12
		// ops.add(cellB.tick());             // B(6) -> Pair(3.0) -> A = 3.0

		DynamicAcceleratedOperation dao = (DynamicAcceleratedOperation) ops.get();
		System.out.println(dao.getFunctionDefinition());
		dao.run();

		assertEquals(12.0, cellB.getCachedValue());
		assertEquals(0.0, cellA.getCachedValue());

		cellB.tick().get().run();
		assertEquals(3.0, cellA.getCachedValue());
		assertEquals(0.0, cellB.getCachedValue());
	}
}
