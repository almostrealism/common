package org.almostrealism.graph.test;

import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Provider;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarFeatures;
import org.almostrealism.graph.TimeCell;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.hardware.cl.CLOperator;
import org.almostrealism.hardware.computations.Assignment;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

public class TimeCellTest implements TestFeatures {
	@Test
	public void timeCell() {
		CLOperator.enableVerboseLog = true;

		TimeCell cell = new TimeCell(null, v(44100));
		cell.setup().get().run();

		Runnable tick = cell.tick().get();
		for (int i = 0; i < 100; i++) {
			tick.run();
		}

		assertEquals(100.0, cell.frameScalar().get().evaluate());
	}

	@Test
	public void fmod() {
		Scalar time = new Scalar();
		Producer<Scalar> loopDuration = v(2.0);

		Producer<Scalar> left = l(() -> new Provider<>(time));
		left = greaterThan(loopDuration, v(0.0),
				scalarMod(scalarAdd(left, ScalarFeatures.of(new Scalar(1.0))), loopDuration),
				scalarAdd(left, ScalarFeatures.of(new Scalar(1.0))), false);

		Producer<Scalar> right = r(() -> new Provider<>(time));
		right = scalarAdd(right, ScalarFeatures.of(1.0));

		Runnable r = new Assignment<>(2, () -> new Provider<>(time), pair(left, right)).get();

		HardwareOperator.verboseLog(() -> {
			for (int i = 0; i < 5; i++) {
				r.run();
			}
		});

		assertEquals(1.0, time);
	}
}
