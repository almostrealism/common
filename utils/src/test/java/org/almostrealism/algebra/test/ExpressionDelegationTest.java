/*
 * Copyright 2025 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.algebra.test;

import io.almostrealism.relation.Evaluable;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.AcceleratedComputationOperation;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.time.TemporalScalar;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

public class ExpressionDelegationTest implements TestFeatures {
	@Test(timeout = 10000)
	public void scalarFromTemporalScalar() {
		TemporalScalar t = new TemporalScalar(4, 8);
		Evaluable<PackedCollection> ev = r(p(t)).get();
		assertEquals(8.0, ev.evaluate());
	}

	@Test(timeout = 10000)
	public void scalarFromTemporalScalarFromScalars() {
		verboseLog(() -> {
			PackedCollection a = pack(1.0);
			PackedCollection b = pack(2.0);
			Evaluable<PackedCollection> ev = r(temporal(p(a), p(b))).get();

			PackedCollection s = ev.evaluate();
			System.out.println(s);
			assertEquals(2.0, s);
		});
	}

	@Test(timeout = 10000)
	public void assignmentFromProduct() {
		PackedCollection a = pack(1.0);
		PackedCollection b = pack(2.0);
		PackedCollection r = pack(0.0);

		OperationList l = new OperationList("Assignment from product");
		l.add(a(1, p(r), cp(a).multiply(p(b))));

		AcceleratedComputationOperation op = (AcceleratedComputationOperation) l.get();

		op.run();
		System.out.println(r.toDouble(0));
		assertEquals(2.0, r);
	}
}
