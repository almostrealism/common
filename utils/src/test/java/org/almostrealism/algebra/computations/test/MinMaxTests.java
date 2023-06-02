package org.almostrealism.algebra.computations.test;

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.cl.HardwareOperator;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

public class MinMaxTests implements TestFeatures {
	@Test
	public void min() {
		assertEquals(1.0, min(v(1.0), v(6.0)).get().evaluate());
		assertEquals(1.0, min(v(6.0), v(1.0)).get().evaluate());
		assertEquals(0.5, min(v(0.5), v(0.7)).get().evaluate());
	}

	@Test
	public void max() {
		assertEquals(6.0, _max(c(1.0), c(6.0)).get().evaluate().toDouble(0));
		assertEquals(6.0, _max(c(6.0), c(1.0)).get().evaluate().toDouble(0));
		assertEquals(0.7, _max(c(0.5), c(0.7)).get().evaluate().toDouble(0));
	}

	@Test
	public void floorKernel() {
		PackedCollection<?> timeline = tensor(shape(60000, 1)).pack().traverse(1);
		PackedCollection<?> speedUp = new PackedCollection<>(shape(1, 1), 1);
		speedUp.set(0, 40);

		Producer<PackedCollection<?>> in = value(timeline.getShape(), 0);
		Producer<PackedCollection<?>> speedUpDuration = value(shape(1, 1).traverse(1), 1);

		HardwareOperator.verboseLog(() -> {
			Evaluable<PackedCollection<?>> ev = divide(in, speedUpDuration).get();
			PackedCollection<?> out = ev.evaluate(timeline, speedUp);
			System.out.println(out.toDouble(10 * 4410));
			System.out.println(timeline.toDouble(10 * 4410) / speedUp.toDouble(0));
			assertEquals(timeline.toDouble(10 * 4410) / speedUp.toDouble(0), out.toDouble(10 * 4410));
		});

		HardwareOperator.verboseLog(() -> {
			Evaluable<PackedCollection<?>> ev = floor(divide(in, speedUpDuration)).get();
			PackedCollection<?> out = ev.evaluate(timeline, speedUp);
			System.out.println(out.toDouble(10 * 4410));
			System.out.println(Math.floor(timeline.toDouble(10 * 4410) / speedUp.toDouble(0)));
			assertEquals(Math.floor(timeline.toDouble(10 * 4410) / speedUp.toDouble(0)), out.toDouble(10 * 4410));
		});
	}
}
