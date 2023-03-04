package org.almostrealism.algebra.computations.test;

import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.algebra.computations.ScalarBankPadFast;
import org.almostrealism.algebra.computations.ScalarFromScalarBank;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

public class ScalarFromScalarBankTest implements TestFeatures {
	@Test
	public void fromScalarBank() {
		ScalarBank bank = new ScalarBank(4);
		bank.set(0, 1);
		bank.set(1, 2);
		bank.set(2, 4);
		bank.set(3, 7);

		assertEquals(4, bank.get(2));

		ScalarFromScalarBank from = new ScalarFromScalarBank(p(bank), v(2));
		assertEquals(4, from.get().evaluate());
	}

	@Test
	public void pad() {
		ScalarBank bank = new ScalarBank(4);
		bank.set(0, 1);
		bank.set(1, 2);
		bank.set(2, 4);
		bank.set(3, 7);

		assertEquals(4, bank.get(2));

		ScalarBankPadFast pad = new ScalarBankPadFast(7, 4, v(bank));
		ScalarBank padded = pad.get().evaluate();

		assertEquals(7, padded.getCount());
		assertEquals(1, padded.get(0));
		assertEquals(2, padded.get(1));
		assertEquals(4, padded.get(2));
		assertEquals(7, padded.get(3));
		assertEquals(0, padded.get(4));
		assertEquals(0, padded.get(5));
		assertEquals(0, padded.get(6));
	}
}
