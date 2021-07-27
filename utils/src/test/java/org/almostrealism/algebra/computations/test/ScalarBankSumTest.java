package org.almostrealism.algebra.computations.test;

import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.algebra.computations.ScalarBankSum;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

public class ScalarBankSumTest implements TestFeatures {
	@Test
	public void sum() {
		ScalarBank bank = new ScalarBank(4);
		bank.set(0, 1);
		bank.set(1, 2);
		bank.set(2, 3);
		bank.set(3, 4);

		ScalarBankSum s = new ScalarBankSum(bank.getCount(), p(bank));
		assertEquals(10, s.get().evaluate());
	}
}
