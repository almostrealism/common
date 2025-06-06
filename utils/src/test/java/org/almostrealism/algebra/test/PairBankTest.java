package org.almostrealism.algebra.test;

import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Pair;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.computations.ExpressionComputation;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.hardware.cl.CLOperator;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class PairBankTest implements TestFeatures {
	@Test
	public void test() {
		PackedCollection<Pair<?>> bank = Pair.bank(2);
		bank.set(0, new Pair(1, 2));
		bank.set(1, new Pair(3, 4));
		assertEquals(1.0, bank.get(0).getX());
		assertEquals(2.0, bank.get(0).getY());
		assertEquals(3.0, bank.get(1).getX());
		assertEquals(4.0, bank.get(1).getY());
	}

	@Test
	public void concat() {
		Producer<PackedCollection<?>> in = v(shape(4, 1), 0);

		CollectionProducer<PackedCollection<?>> concat = concat(in, in);

		PackedCollection<?> timeline = new PackedCollection<>(shape(4, 1));
		timeline.setMem(1.0, 2.0, 3.0, 4.0);

		PackedCollection<?> destination = new PackedCollection<>(shape(4, 2));

		concat.get().into(destination.traverse(1)).evaluate(timeline.traverse(1));
		destination.traverse(1).print();

		assertEquals(3.0, destination.valueAt(2, 0));
		assertEquals(3.0, destination.valueAt(2, 1));
	}

	@Test
	public void map() {
		Producer<PackedCollection<?>> in = v(shape(4, 1), 0);

		CollectionProducer<PackedCollection<?>> concat = map(shape(2), traverse(1, in),
				v -> concat(c(2.0).multiply(v), c(2.0).multiply(v).add(c(1.0))));

		PackedCollection<?> timeline = new PackedCollection<>(shape(4, 1));
		timeline.setMem(1.0, 2.0, 3.0, 4.0);

		PackedCollection<?> destination = new PackedCollection<>(shape(4, 2));

		verboseLog(() -> {
			concat.get().into(destination.traverse(1)).evaluate(timeline.traverse(1));
			destination.print();
		});

		assertEquals(6.0, destination.valueAt(2, 0));
		assertEquals(7.0, destination.valueAt(2, 1));
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

		Producer<PackedCollection<?>> index = c(2).multiply(v(shape(4, 1), 1)).add(c(1.0));
		Producer<Pair<?>> pairFromPairBank =
				pairFromBank(v(shape(10, 2), 0), index);

		PackedCollection<?> timeline = new PackedCollection<>(shape(4, 1));
		timeline.setMem(1.0, 2.0, 3.0, 4.0);

		PackedCollection<?> destination = new PackedCollection<>(shape(4, 2));

		pairFromPairBank.get().into(destination.traverse(1))
				.evaluate(bank, timeline.traverse(1));

		destination.print();
		assertEquals(11.0, destination.valueAt(1, 0));
		assertEquals(12.0, destination.valueAt(1, 1));
		assertEquals(19.0, destination.valueAt(3, 0));
		assertEquals(20.0, destination.valueAt(3, 1));
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

			destination.print();
			assertEquals(6.0, destination.valueAt(1));
			assertEquals(8.0, destination.valueAt(2));
		});
	}
}
