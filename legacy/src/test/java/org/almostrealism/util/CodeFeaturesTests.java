package org.almostrealism.util;

import io.almostrealism.code.OperationAdapter;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarProducer;
import org.junit.Test;

import java.util.function.Supplier;

public class CodeFeaturesTests implements TestFeatures {
	@Test
	public void addToProvider() {
		Scalar value = new Scalar(1.0);
		ScalarProducer s = v(1).add(p(value));
		value.setValue(2);

		Evaluable<Scalar> ev = s.get();
		((OperationAdapter) ev).compile();
		assertEquals(3.0, ev.evaluate().getValue());

		value.setValue(3);
		assertEquals(4.0, ev.evaluate().getValue());
	}

	@Test
	public void addToProviderAndAssign() {
		Scalar value = new Scalar(1.0);
		Scalar dest = new Scalar(0.0);
		Supplier<Runnable> s = a(2, p(dest), v(1).add(p(value)).divide(2.0));
		value.setValue(2);

		Runnable r = s.get();
		((OperationAdapter) r).compile();
		r.run();
		assertEquals(3.0, dest.getValue());

		value.setValue(3);
		r.run();
		assertEquals(4.0, dest.getValue());
	}
}
