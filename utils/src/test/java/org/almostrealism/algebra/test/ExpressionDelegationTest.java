/*
 * Copyright 2021 Michael Murray
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

import org.almostrealism.algebra.Scalar;
import org.almostrealism.hardware.AcceleratedComputationEvaluable;
import org.almostrealism.hardware.AcceleratedComputationOperation;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

import java.util.function.Supplier;

public class ExpressionDelegationTest implements TestFeatures {
	@Test
	public void scalarFromTemporalScalarFromScalars() {
		Scalar a = new Scalar(1.0);
		Scalar b = new Scalar(2.0);
		AcceleratedComputationEvaluable<Scalar> ev = (AcceleratedComputationEvaluable<Scalar>) r((Supplier) temporal(p(a), p(b))).get();

		Scalar s = ev.evaluate();
		System.out.println(s);
		assertEquals(2.0, s);
	}

	@Test
	public void assignmentFromProduct() {
		Scalar a = new Scalar(1.0);
		Scalar b = new Scalar(2.0);
		Scalar r = new Scalar(0.0);

		OperationList l = new OperationList();
		l.add(a(1, p(r), v(a).multiply(p(b))));

		AcceleratedComputationOperation op = (AcceleratedComputationOperation) l.get();

		op.run();
		System.out.println(r);
		assertEquals(2.0, r);
	}
}
