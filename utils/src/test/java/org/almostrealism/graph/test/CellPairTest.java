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

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.CellPair;
import org.almostrealism.graph.CollectionCachedStateCell;
import org.almostrealism.hardware.AcceleratedOperation;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.util.TestFeatures;

public class CellPairTest implements TestFeatures {

	// TODO @Test(timeout = 30000)
	public void scalarCachedStatePair() {
		CollectionCachedStateCell cellA = new CollectionCachedStateCell();
		CollectionCachedStateCell cellB = new CollectionCachedStateCell();

		CellPair<PackedCollection> pair = new CellPair(cellA, cellB, v -> c(3.0), v -> multiply(v, c(2.0)));
		pair.init();

		// A = 6
		AcceleratedOperation op = (AcceleratedOperation) cellA.push(c(6.0)).get();
		op.run();

		// B = 9
		op = (AcceleratedOperation) cellB.push(c(9.0)).get();
		op.run();

		assertEquals(6.0, cellA.getCachedValue().toDouble(0));
		assertEquals(9.0, cellB.getCachedValue().toDouble(0));

		// A(6) -> Pair(*2) -> B = 12
		op = (AcceleratedOperation) cellA.tick().get();
		op.run();

		assertEquals(12.0, cellB.getCachedValue().toDouble(0));
		assertEquals(0.0, cellA.getCachedValue().toDouble(0));

		// B(6) -> Pair(3.0) -> A = 3.0
		op = (AcceleratedOperation) cellB.tick().get();
		op.run();

		assertEquals(3.0, cellA.getCachedValue().toDouble(0));
		assertEquals(0.0, cellB.getCachedValue().toDouble(0));
	}

	// TODO @Test(timeout = 30000)
	public void scalarCachedStatePairOperationList() {
		CollectionCachedStateCell cellA = new CollectionCachedStateCell();
		CollectionCachedStateCell cellB = new CollectionCachedStateCell();

		CellPair<PackedCollection> pair = new CellPair(cellA, cellB, v -> c(3.0), v -> multiply(v, c(2.0)));
		pair.init();

		OperationList ops = new OperationList("Cell Pushes and Ticks");
		ops.add(cellA.push(c(6.0))); // A = 6
		ops.add(cellB.push(c(9.0))); // B = 9
		ops.add(cellA.tick());             // A(6) -> Pair(*2) -> B = 12
		// ops.add(cellB.tick());             // B(6) -> Pair(3.0) -> A = 3.0

		AcceleratedOperation dao = (AcceleratedOperation) ops.get();
		dao.run();

		assertEquals(12.0, cellB.getCachedValue().toDouble(0));
		assertEquals(0.0, cellA.getCachedValue().toDouble(0));

		cellB.tick().get().run();
		assertEquals(3.0, cellA.getCachedValue().toDouble(0));
		assertEquals(0.0, cellB.getCachedValue().toDouble(0));
	}
}
