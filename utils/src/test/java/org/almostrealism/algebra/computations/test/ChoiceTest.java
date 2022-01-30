package org.almostrealism.algebra.computations.test;

import io.almostrealism.relation.Evaluable;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.algebra.computations.ScalarChoice;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

public class ChoiceTest implements TestFeatures {
	@Test
	public void oneOrTwo() {
		try {
			ScalarBank bank = new ScalarBank(2);
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
