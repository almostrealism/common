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

import io.almostrealism.code.Computation;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.AcceleratedOperation;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;
import org.almostrealism.algebra.computations.Switch;

import java.util.Arrays;

public class SwitchTest implements TestFeatures {
	public Switch choice(PackedCollection<?> output, PackedCollection<?> decision, PackedCollection<?> multiplier) {
		return choice(output, p(decision), p(multiplier));
	}

	public Switch choice(PackedCollection<?> output, Producer<PackedCollection<?>> decision, Producer<PackedCollection<?>> multiplier) {
		Computation<Void> firstChoice = a(1, p(output), multiply(multiplier, c(2.0)));
		Computation<Void> secondChoice = a(1, p(output), multiply(multiplier, c(4.0)));
		Computation<Void> thirdChoice = a(1, p(output), multiply(multiplier, c(8.0)));
		return new Switch((CollectionProducer) decision, Arrays.asList(firstChoice, secondChoice, thirdChoice));
	}

	@Test
	public void threeChoices() {
		PackedCollection<?> output = new PackedCollection<>(1);
		output.setMem(0, 0.0);
		PackedCollection<?> decision = new PackedCollection<>(1);
		decision.setMem(0, 0.4);

		Switch choice = choice(output, decision, pack(1.0));

		verboseLog(() -> {
			AcceleratedOperation op = (AcceleratedOperation) choice.get();
			op.run();
		});

		System.out.println("chosen = " + output.toDouble(0));
		assertEquals(4.0, output);
	}


	@Test
	public void choiceList() {
		PackedCollection<?> output1 = new PackedCollection<>(1);
		output1.setMem(0, 0.0);
		PackedCollection<?> decision1 = new PackedCollection<>(1);
		decision1.setMem(0, 0.4);
		PackedCollection<?> output2 = new PackedCollection<>(1);
		output2.setMem(0, 0.0);
		PackedCollection<?> decision2 = new PackedCollection<>(1);
		decision2.setMem(0, 0.8);

		OperationList list = new OperationList("Choice List");
		list.add(choice(output1, decision1, pack(1.0)));
		list.add(choice(output2, decision2, pack(1.0)));

		verboseLog(() -> {
			AcceleratedOperation op = (AcceleratedOperation) list.get();
			op.run();
		});

		System.out.println("first choice = " + output1.toDouble(0));
		System.out.println("second choice = " + output2.toDouble(0));

		assertEquals(4.0, output1);
		assertEquals(8.0, output2);
	}

	@Test
	public void nestedChoiceList() {
		Producer<PackedCollection<?>> multiplier = c(2.0);

		PackedCollection<?> output1a = new PackedCollection<>(1);
		output1a.setMem(0, 0.0);
		PackedCollection<?> output1b = new PackedCollection<>(1);
		output1b.setMem(0, 0.0);
		Producer<PackedCollection<?>> decisionA = c(0.4);
		PackedCollection<?> output2a = new PackedCollection<>(1);
		output2a.setMem(0, 0.0);
		PackedCollection<?> output2b = new PackedCollection<>(1);
		output2b.setMem(0, 0.0);
		Producer<PackedCollection<?>> decisionB = multiply(c(0.4), multiplier);

		OperationList embeddedList = new OperationList("Embedded Choice List");
		embeddedList.add(choice(output2a, decisionA, multiplier));
		embeddedList.add(choice(output2b, decisionB, c(1.0)));

		OperationList list = new OperationList("Choice List");
		list.add(choice(output1a, decisionA, c(1.0)));
		list.add(choice(output1b, decisionB, multiplier));
		list.add(embeddedList);

		AcceleratedOperation op = (AcceleratedOperation) list.get();
		op.run();

		System.out.println("first choice A = " + output1a.toDouble(0));
		System.out.println("first choice B = " + output1b.toDouble(0));
		System.out.println("second choice A = " + output2a.toDouble(0));
		System.out.println("second choice B = " + output2b.toDouble(0));

		assertEquals(4.0, output1a);
		assertEquals(16.0, output1b);
		assertEquals(8.0, output2a);
		assertEquals(8.0, output2b);
	}
}
