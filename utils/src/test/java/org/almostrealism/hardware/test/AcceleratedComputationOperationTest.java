package org.almostrealism.hardware.test;

import io.almostrealism.expression.Expression;
import io.almostrealism.expression.MultiExpression;
import io.almostrealism.expression.Sum;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Provider;
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

public class AcceleratedComputationOperationTest implements HardwareFeatures, TestFeatures {
	@Test
	public void sum() {
		Producer<Vector> v = vector(1.0, 2.0, 3.0);
		Producer<Vector> in = Input.value(Vector.shape(), 0);

		Evaluable<Vector> s = compileProducer(add(v, in));
	}

	@Test
	public void providerExpressionComputation() {
		Function<List<MultiExpression<Double>>, Expression<Double>> expression = args ->
				new Sum(args.get(1).getValue(0), args.get(2).getValue(0));

		PackedCollection<?> a = new PackedCollection(1);
		PackedCollection<?> b = new PackedCollection(1);
		a.setMem(3.0);
		b.setMem(5.0);

		ExpressionComputation<PackedCollection<?>> computation =
				new ExpressionComputation<>(List.of(expression),
						() -> new Provider<>(a),
						() -> new Provider<>(b));

		PackedCollection<?> out = new PackedCollection<>(1);
		Assignment<?> assign = a(1, p(out), computation);

		Runnable r = assign.get();
		r.run();
		assertEquals(8.0, out.toArray(0, 1)[0]);
	}
}
