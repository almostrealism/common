package org.almostrealism.util;

import io.almostrealism.code.OperationAdapter;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarProducer;
import org.almostrealism.algebra.computations.DefaultScalarEvaluable;
import org.almostrealism.hardware.AcceleratedComputationEvaluable;
import org.almostrealism.hardware.AcceleratedComputationOperation;
import org.junit.Test;

import java.util.function.Supplier;

public class CodeFeaturesTests implements TestFeatures {
	@Test
	public void partialComputation1() {
		ScalarProducer p = scalarsMultiply(scalar(1.0), scalar(2.0));
		ScalarProducer q = scalarAdd(scalar(5.0), p);

		AcceleratedComputationEvaluable<Scalar> pev = (AcceleratedComputationEvaluable) p.get();
		AcceleratedComputationEvaluable<Scalar> qev = (AcceleratedComputationEvaluable) q.get();
		pev.compile();

		assertEquals(2.0, pev.evaluate());
		assertEquals(7.0, qev.evaluate());
	}

	@Test
	public void partialComputation2() {
		ScalarProducer p = scalarsMultiply(scalar(1.0), scalar(2.0));
		ScalarProducer q = scalarAdd(scalar(5.0), p);

		AcceleratedComputationEvaluable<Scalar> pev = (AcceleratedComputationEvaluable) p.get();
		AcceleratedComputationEvaluable<Scalar> qev = (AcceleratedComputationEvaluable) q.get();
		qev.compile();

		assertEquals(2.0, pev.evaluate());
		assertEquals(7.0, qev.evaluate());
	}

	@Test
	public void partialComputation3() {
		Scalar multiplier = new Scalar(1.0);
		ScalarProducer p = scalarsMultiply(p(multiplier), scalar(2.0));
		ScalarProducer q = scalarAdd(scalar(5.0), p);

		AcceleratedComputationEvaluable<Scalar> pev = (AcceleratedComputationEvaluable) p.get();
		AcceleratedComputationEvaluable<Scalar> qev = (AcceleratedComputationEvaluable) q.get();

		assertEquals(2.0, pev.evaluate());
		assertEquals(7.0, qev.evaluate());

		multiplier.setValue(2.0);
		assertEquals(4.0, pev.evaluate());
		assertEquals(9.0, qev.evaluate());
	}

	@Test
	public void partialComputation4() {
		Scalar multiplier = new Scalar(1.0);
		ScalarProducer p = scalarsMultiply(p(multiplier), scalar(2.0));
		ScalarProducer q = scalarAdd(scalar(5.0), p);

		assertEquals(7.0, q.get().evaluate());
		assertEquals(2.0, p.get().evaluate());

		multiplier.setValue(2.0);
		assertEquals(4.0, p.get().evaluate());
		assertEquals(9.0, q.get().evaluate());
	}

	@Test
	public void partialComputation5() {
		ScalarProducer p = scalarsMultiply(scalar(1.0), scalar(2.0));
		ScalarProducer q = scalarAdd(scalar(5.0), p);
		ScalarProducer r = scalarsMultiply(scalar(5.0), p);

		AcceleratedComputationEvaluable<Scalar> pev = (AcceleratedComputationEvaluable) p.get();
		AcceleratedComputationEvaluable<Scalar> qev = (AcceleratedComputationEvaluable) q.get();
		AcceleratedComputationEvaluable<Scalar> rev = (AcceleratedComputationEvaluable) r.get();
		qev.compile();

		assertEquals(2.0, pev.evaluate());
		assertEquals(7.0, qev.evaluate());
		assertEquals(10.0, rev.evaluate());
	}

	@Test
	public void partialComputation6() {
		Scalar multiplier = new Scalar(1.0);
		ScalarProducer p = scalarsMultiply(p(multiplier), scalar(2.0));
		ScalarProducer q = scalarAdd(scalar(5.0), p);
		ScalarProducer r = scalarsMultiply(scalar(5.0), p);

		assertEquals(10.0, r.get().evaluate());
		assertEquals(2.0, p.get().evaluate());
		assertEquals(7.0, q.get().evaluate());
	}

	@Test
	public void partialComputation7() {
		Scalar multiplier = new Scalar(1.0);
		ScalarProducer p = scalarsMultiply(() -> args -> multiplier, scalar(2.0));
		ScalarProducer q = scalarAdd(scalar(5.0), p);
		ScalarProducer r = scalarsMultiply(scalar(5.0), p);

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
		ScalarProducer s = v(1).add(p(value));
		value.setValue(2);

		DefaultScalarEvaluable ev = (DefaultScalarEvaluable) s.get();
		ev.compile();
		System.out.println(ev.getFunctionDefinition());
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

		AcceleratedComputationOperation r = (AcceleratedComputationOperation) s.get();
		r.compile();
		System.out.println(r.getFunctionDefinition());
		r.run();
		System.out.println(dest.getValue());
		assertEquals(1.5, dest.getValue());

		value.setValue(3);
		r.run();
		assertEquals(2.0, dest.getValue());
	}

	@Test
	public void loop() {
		Scalar value = new Scalar(1.0);
		Scalar dest = new Scalar(0.0);
		Supplier<Runnable> s = loop(a(2, p(dest), v(1).add(p(value)).divide(2.0)), 2);
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
}
