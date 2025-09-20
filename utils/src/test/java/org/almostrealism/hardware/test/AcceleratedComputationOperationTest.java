package org.almostrealism.hardware.test;

import io.almostrealism.code.Computation;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Vector;
import org.almostrealism.hardware.Input;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

public class AcceleratedComputationOperationTest implements TestFeatures {
	@Test
	public void sum() {
		Producer<Vector> v = vector(1.0, 2.0, 3.0);
		Producer<Vector> in = Input.value(Vector.shape(), 0);

		Evaluable<Vector> s = compileProducer((Computation) add(v, in));
	}
}
