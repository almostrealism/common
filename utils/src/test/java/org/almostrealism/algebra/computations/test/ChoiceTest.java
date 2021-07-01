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

package org.almostrealism.algebra.computations.test;

import io.almostrealism.code.Computation;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.hardware.DynamicAcceleratedOperation;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;
import org.almostrealism.algebra.computations.Choice;

import java.util.Arrays;

public class ChoiceTest implements TestFeatures {
	public Choice choice(Scalar output, Scalar decision) {
		Computation<Void> firstChoice = a(2, p(output), v(2.0));
		Computation<Void> secondChoice = a(2, p(output), v(4.0));
		Computation<Void> thirdChoice = a(2, p(output), v(8.0));
		return new Choice(v(decision), Arrays.asList(firstChoice, secondChoice, thirdChoice));
	}

	@Test
	public void threeChoices() {
		Scalar output = new Scalar(0.0);
		Scalar decision = new Scalar(0.4);

		Choice choice = choice(output, decision);

		DynamicAcceleratedOperation op = (DynamicAcceleratedOperation) choice.get();
		System.out.println(op.getFunctionDefinition());
		op.run();

		System.out.println("chosen = " + output.getValue());
		assertEquals(new Scalar(4.0), output);
	}


	@Test
	public void choiceList() {
		Scalar output1 = new Scalar(0.0);
		Scalar decision1 = new Scalar(0.4);
		Scalar output2 = new Scalar(0.0);
		Scalar decision2 = new Scalar(0.8);

		OperationList list = new OperationList();
		list.add(choice(output1, decision1));
		list.add(choice(output2, decision2));

		DynamicAcceleratedOperation op = (DynamicAcceleratedOperation) list.get();
		System.out.println(op.getFunctionDefinition());
		op.run();

		System.out.println("first choice = " + output1.getValue());
		System.out.println("second choice = " + output2.getValue());

		assertEquals(new Scalar(4.0), output1);
		assertEquals(new Scalar(8.0), output2);
	}
}
