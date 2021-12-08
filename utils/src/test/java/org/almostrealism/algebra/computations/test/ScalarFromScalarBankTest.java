package org.almostrealism.algebra.computations.test;

import org.almostrealism.algebra.ScalarBank;
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
}
