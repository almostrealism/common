/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.algebra.computations.test;

import io.almostrealism.relation.Evaluable;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.computations.ScalarChoice;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

public class ChoiceTest implements TestFeatures {
	@Test
	public void oneOrTwo() {
		try {
			PackedCollection<Scalar> bank = Scalar.scalarBank(2);
			bank.set(0, 1.0);
			bank.set(1, 2.0);
			ScalarChoice choice = new ScalarChoice(2, v(0.7), v(bank));
			Evaluable<Scalar> ev = choice.get();
			assertEquals(2.0, ev.evaluate());
		} catch (HardwareException e) {
			System.out.println(e.getProgram());
			e.printStackTrace();
		}
	}
}
