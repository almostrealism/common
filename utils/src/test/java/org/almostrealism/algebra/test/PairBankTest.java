package org.almostrealism.algebra.test;

import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.PairBank;
import org.junit.Test;

public class PairBankTest {
	@Test
	public void test() {
		PairBank bank = new PairBank(2);
		bank.set(0, new Pair(1, 2));
		bank.set(1, new Pair(3, 4));
		assert bank.get(0).getX() == 1.0;
		assert bank.get(0).getY() == 2.0;
		assert bank.get(1).getX() == 3.0;
		assert bank.get(1).getY() == 4.0;
	}
}
