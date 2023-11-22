package org.almostrealism.util;

import io.almostrealism.code.OperationAdapter;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.AcceleratedComputationOperation;
import org.junit.Test;

import java.util.function.Supplier;

public class CodeFeaturesTests implements TestFeatures {
	@Test
	public void partialComputation1() {
		Producer<Scalar> p = scalarsMultiply(scalar(1.0), scalar(2.0));
		Producer<Scalar> q = scalarAdd(scalar(5.0), p);

		Evaluable<Scalar> pev = p.get();
		Evaluable<Scalar> qev = q.get();

		assertEquals(2.0, pev.evaluate());
		assertEquals(7.0, qev.evaluate());
	}

	@Test
	public void partialComputation2() {
		Producer<Scalar> p = scalarsMultiply(scalar(1.0), scalar(2.0));
		Producer<Scalar> q = scalarAdd(scalar(5.0), p);

		Evaluable<Scalar> pev = p.get();
		Evaluable<Scalar> qev = q.get();

		assertEquals(2.0, pev.evaluate());
		assertEquals(7.0, qev.evaluate());
	}

	@Test
	public void partialComputation3() {
		Scalar multiplier = new Scalar(1.0);
		Producer<Scalar> p = scalarsMultiply(p(multiplier), scalar(2.0));
		Producer<Scalar> q = scalarAdd(scalar(5.0), p);

		Evaluable<Scalar> pev = p.get();
		Evaluable<Scalar> qev = q.get();

		assertEquals(2.0, pev.evaluate());
		assertEquals(7.0, qev.evaluate());

		multiplier.setValue(2.0);
		assertEquals(4.0, pev.evaluate());
		assertEquals(9.0, qev.evaluate());
	}

	@Test
	public void partialComputation4() {
		Scalar multiplier = new Scalar(1.0);
		Producer<Scalar> p = scalarsMultiply(p(multiplier), scalar(2.0));
		Producer<Scalar> q = scalarAdd(scalar(5.0), p);

		Evaluable<Scalar> qev = q.get();
		assertEquals(7.0, qev.evaluate());

		Evaluable<Scalar> pev = p.get();
		assertEquals(2.0, pev.evaluate());

		// Make sure the process respects the update to a provided value
		multiplier.setValue(2.0);
		assertEquals(4.0, p.get().evaluate());
		assertEquals(9.0, q.get().evaluate());

		// Make sure the original Evaluables are not affected
		assertEquals(4.0, pev.evaluate());
		assertEquals(9.0, qev.evaluate());
	}

	@Test
	public void partialComputation5() {
		Producer<Scalar> p = scalarsMultiply(scalar(1.0), scalar(2.0));
		Producer<Scalar> q = scalarAdd(scalar(5.0), p);
		Producer<Scalar> r = scalarsMultiply(scalar(5.0), p);

		Evaluable<Scalar> pev = p.get();
		Evaluable<Scalar> qev = q.get();
		Evaluable<Scalar> rev = r.get();

		assertEquals(2.0, pev.evaluate());
		assertEquals(7.0, qev.evaluate());
		assertEquals(10.0, rev.evaluate());
	}

	@Test
	public void partialComputation6() {
		Scalar multiplier = new Scalar(1.0);
		Producer<Scalar> p = scalarsMultiply(p(multiplier), scalar(2.0));
		Producer<Scalar> q = scalarAdd(scalar(5.0), p);
		Producer<Scalar> r = scalarsMultiply(scalar(5.0), p);

		assertEquals(10.0, r.get().evaluate());
		assertEquals(2.0, p.get().evaluate());
		assertEquals(7.0, q.get().evaluate());
	}

	@Test
	public void partialComputation7() {
		Scalar multiplier = new Scalar(1.0);
		Producer<Scalar> p = scalarsMultiply(() -> args -> multiplier, scalar(2.0));
		Producer<Scalar> q = scalarAdd(scalar(5.0), p);
		Producer<Scalar> r = scalarsMultiply(scalar(5.0), p);

		assertEquals(10.0, r.get().evaluate());
		assertEquals(2.0, p.get().evaluate());
		assertEquals(7.0, q.get().evaluate());

		multiplier.setValue(2.0);
		assertEquals(20.0, r.get().evaluate());
		assertEquals(9.0, q.get().evaluate());
		assertEquals(4.0, p.get().evaluate());
	}

	@Test
	public void addToProvider() {
		Scalar value = new Scalar(1.0);
		Producer<Scalar> s = add(v(1), p(value));
		value.setValue(2);

		Evaluable<Scalar> ev = s.get();
		PackedCollection out = ev.evaluate();
		assertEquals(3.0, out.toDouble(0));

		value.setValue(3);
		out = ev.evaluate();
		assertEquals(4.0, out.toDouble(0));
	}

	@Test
	public void addToProviderAndAssign() {
		Scalar value = new Scalar(1.0);
		Scalar dest = new Scalar(0.0);
		Supplier<Runnable> s = a(1, p(dest), add(v(1), p(value)).divide(v(2.0)));
		value.setValue(2);

		AcceleratedComputationOperation r = (AcceleratedComputationOperation) s.get();
		r.run();
		System.out.println(dest.getValue());
		assertEquals(1.5, dest.getValue());

		value.setValue(3);
		r.run();
		assertEquals(2.0, dest.getValue());
	}

	@Test
	public void loop1() {
		Scalar value = new Scalar(1.0);
		Scalar dest = new Scalar(0.0);
		Supplier<Runnable> s = loop((Supplier<Runnable>) a(1, p(dest), add(v(1),p(value)).divide(v(2.0))), 2);
		value.setValue(2);

		Runnable r = s.get();
		((OperationAdapter) r).compile();
		r.run();
		System.out.println(dest.getValue());
		assertEquals(1.5, dest.getValue());

		value.setValue(3);
		r.run();
		assertEquals(2.0, dest.getValue());
	}

	@Test
	public void loop2() {
		Scalar dest = new Scalar(0.0);
		Supplier<Runnable> s = loop((Supplier<Runnable>) a(1, p(dest), add(v(1.0), p(dest))), 3);
		Runnable r = s.get();

		r.run();
		System.out.println(dest.getValue());
		assertEquals(3.0, dest);

		r.run();
		System.out.println(dest.getValue());
		assertEquals(6.0, dest);
	}
}
