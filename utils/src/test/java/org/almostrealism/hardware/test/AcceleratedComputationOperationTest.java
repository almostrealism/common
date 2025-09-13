package org.almostrealism.hardware.test;

import io.almostrealism.code.Computation;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.Sum;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Provider;
import io.almostrealism.scope.ArrayVariable;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.ExpressionComputation;
import org.almostrealism.hardware.HardwareFeatures;
import org.almostrealism.hardware.Input;
import org.almostrealism.hardware.computations.Assignment;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

import java.util.List;
import java.util.function.Function;

public class AcceleratedComputationOperationTest implements TestFeatures {
	@Test
	public void sum() {
		Producer<Vector> v = vector(1.0, 2.0, 3.0);
		Producer<Vector> in = Input.value(Vector.shape(), 0);

		Evaluable<Vector> s = compileProducer((Computation) add(v, in));
	}
}
