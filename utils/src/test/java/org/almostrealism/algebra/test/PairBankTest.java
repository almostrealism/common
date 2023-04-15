package org.almostrealism.algebra.test;

import org.almostrealism.algebra.Pair;
import org.almostrealism.collect.PackedCollection;
import org.junit.Assert;
import org.junit.Test;

public class PairBankTest {
	@Test
	public void test() {
		PackedCollection<Pair<?>> bank = Pair.bank(2);
		bank.set(0, new Pair(1, 2));
		bank.set(1, new Pair(3, 4));
		Assert.assertEquals(1.0, bank.get(0).getX(), Math.pow(10, -10));
		Assert.assertEquals(2.0, bank.get(0).getY(), Math.pow(10, -10));
		Assert.assertEquals(3.0, bank.get(1).getX(), Math.pow(10, -10));
		Assert.assertEquals(4.0, bank.get(1).getY(), Math.pow(10, -10));
	}
}
