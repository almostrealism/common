package org.almostrealism.algebra.test;

import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.algebra.Pair;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.computations.ExpressionComputation;
import org.almostrealism.hardware.cl.HardwareOperator;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class PairBankTest implements CodeFeatures {
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

	@Test
	public void pairFromPairBank() {
		PackedCollection<Pair<?>> bank = Pair.bank(10);
		bank.set(0, new Pair(1, 2));
		bank.set(1, new Pair(3, 4));
		bank.set(2, new Pair(5, 6));
		bank.set(3, new Pair(7, 8));
		bank.set(4, new Pair(9, 10));
		bank.set(5, new Pair(11, 12));
		bank.set(6, new Pair(13, 14));
		bank.set(7, new Pair(15, 16));
		bank.set(8, new Pair(17, 18));
		bank.set(9, new Pair(19, 20));

		Producer<Pair<?>> pairFromPairBank =
				pairFromBank(v(shape(10, 2).traverse(1), 0),
					c(2).multiply(v(shape(1), 1)).add(c(1.0)));

		PackedCollection<?> timeline = new PackedCollection<>(shape(4, 1));
		timeline.setMem(1.0, 2.0, 3.0, 4.0);

		PackedCollection<?> destination = new PackedCollection<>(shape(4, 2));

		pairFromPairBank.get().into(destination.traverse(1))
				.evaluate(bank.traverse(1),
						timeline.range(shape(destination.getCount(), 1).traverse(1)));

		System.out.println(Arrays.toString(destination.toArray(0, 8)));
		Assert.assertEquals(9.0, destination.valueAt(1, 0), Math.pow(10, -10));
		Assert.assertEquals(10.0, destination.valueAt(1, 1), Math.pow(10, -10));
		Assert.assertEquals(13.0, destination.valueAt(3, 0), Math.pow(10, -10));
		Assert.assertEquals(14.0, destination.valueAt(3, 1), Math.pow(10, -10));
	}

	@Test
	public void pairBankKernel() {
		PackedCollection<Pair<?>> bank = Pair.bank(10);
		bank.set(0, new Pair(1, 2));
		bank.set(1, new Pair(3, 4));
		bank.set(2, new Pair(5, 6));
		bank.set(3, new Pair(7, 8));
		bank.set(4, new Pair(9, 10));
		bank.set(5, new Pair(11, 12));
		bank.set(6, new Pair(13, 14));
		bank.set(7, new Pair(15, 16));
		bank.set(8, new Pair(17, 18));
		bank.set(9, new Pair(19, 20));

		int len = 100;

		HardwareOperator.disableDimensionMasks(() -> {
			PackedCollection<?> destination = new PackedCollection<>(shape(4));

			Producer<PackedCollection<?>> c = new ExpressionComputation<>(List.of(args -> args.get(1).getValueRelative(1)),
					v(shape(len, 2).traverse(1), 0));

			TraversalPolicy subset = shape(bank.getShape().length(0) - 1, bank.getShape().length(1));
			c.get().into(destination.traverse(1)).evaluate(bank.range(subset, 2).traverse(1));

			System.out.println(Arrays.toString(destination.toArray(0, 4)));
			Assert.assertEquals(6.0, destination.valueAt(1), Math.pow(10, -10));
			Assert.assertEquals(8.0, destination.valueAt(2), Math.pow(10, -10));
		});
	}
}
