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

package org.almostrealism.algebra.computations.test;

import io.almostrealism.code.Computation;
import io.almostrealism.code.ProducerComputation;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarProducer;
import org.almostrealism.algebra.ScalarProducerBase;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.DynamicAcceleratedOperation;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;
import org.almostrealism.algebra.computations.Switch;

import java.util.Arrays;

public class SwitchTest implements TestFeatures {
	public Switch choice(Scalar output, Scalar decision, Scalar multiplier) {
		return choice(output, v(decision), v(multiplier));
	}

	public Switch choice(Scalar output, ScalarProducerBase decision, ScalarProducerBase multiplier) {
		Computation<Void> firstChoice = a(1, p(output), scalarsMultiply(multiplier, v(2.0)));
		Computation<Void> secondChoice = a(1, p(output), scalarsMultiply(multiplier, v(4.0)));
		Computation<Void> thirdChoice = a(1, p(output), scalarsMultiply(multiplier, v(8.0)));
		return new Switch((ProducerComputation) decision, Arrays.asList(firstChoice, secondChoice, thirdChoice));
	}

	@Test
	public void threeChoices() {
		Scalar output = new Scalar(0.0);
		Scalar decision = new Scalar(0.4);

		Switch choice = choice(output, decision, new Scalar(1.0));

		DynamicAcceleratedOperation op = (DynamicAcceleratedOperation) choice.get();
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

		OperationList list = new OperationList("Choice List");
		list.add(choice(output1, decision1, new Scalar(1.0)));
		list.add(choice(output2, decision2, new Scalar(1.0)));

		DynamicAcceleratedOperation op = (DynamicAcceleratedOperation) list.get();
		op.run();

		System.out.println("first choice = " + output1.getValue());
		System.out.println("second choice = " + output2.getValue());

		assertEquals(new Scalar(4.0), output1);
		assertEquals(new Scalar(8.0), output2);
	}

	@Test
	public void nestedChoiceList() {
		ScalarProducerBase multiplier = v(2.0);

		Scalar output1a = new Scalar(0.0);
		Scalar output1b = new Scalar(0.0);
		ScalarProducerBase decisionA = v(0.4);
		Scalar output2a = new Scalar(0.0);
		Scalar output2b = new Scalar(0.0);
		ScalarProducerBase decisionB = scalarsMultiply(v(0.4), multiplier);

		OperationList embeddedList = new OperationList("Embedded Choice List");
		embeddedList.add(choice(output2a, decisionA, multiplier));
		embeddedList.add(choice(output2b, decisionB, v(1.0)));

		OperationList list = new OperationList("Choice List");
		list.add(choice(output1a, decisionA, v(1.0)));
		list.add(choice(output1b, decisionB, multiplier));
		list.add(embeddedList);

		DynamicAcceleratedOperation op = (DynamicAcceleratedOperation) list.get();
		op.run();

		System.out.println("first choice A = " + output1a.getValue());
		System.out.println("first choice B = " + output1b.getValue());
		System.out.println("second choice A = " + output2a.getValue());
		System.out.println("second choice B = " + output2b.getValue());

		assertEquals(new Scalar(4.0), output1a);
		assertEquals(new Scalar(16.0), output1b);
		assertEquals(new Scalar(8.0), output2a);
		assertEquals(new Scalar(8.0), output2b);
	}
}
