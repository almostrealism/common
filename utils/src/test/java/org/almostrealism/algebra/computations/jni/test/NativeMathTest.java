/*
 * Copyright 2022 Michael Murray
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

package org.almostrealism.algebra.computations.jni.test;

import io.almostrealism.relation.Evaluable;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.computations.ScalarExpressionComputation;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

import java.io.IOException;

public class NativeMathTest implements TestFeatures {
	@Test
	public void add() throws IOException, InterruptedException {
		ScalarExpressionComputation sum = scalarAdd(v(1.0), v(2.0));
		Evaluable ev = sum.get();
		System.out.println(ev.evaluate());
		assertEquals(3.0, (Scalar) ev.evaluate());
	}
}
