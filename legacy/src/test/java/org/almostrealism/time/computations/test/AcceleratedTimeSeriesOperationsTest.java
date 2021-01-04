package org.almostrealism.time.computations.test;

import io.almostrealism.code.ArrayVariable;
import io.almostrealism.code.OperationAdapter;
import io.almostrealism.code.Scope;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.computations.ScalarFromPair;
import org.almostrealism.hardware.AcceleratedComputationOperation;
import org.almostrealism.hardware.AcceleratedComputationEvaluable;
import org.almostrealism.hardware.HardwareFeatures;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.time.AcceleratedTimeSeries;
import org.almostrealism.time.CursorPair;
import org.almostrealism.time.TemporalScalar;
import org.almostrealism.time.computations.AcceleratedTimeSeriesValueAt;
import org.almostrealism.time.computations.TemporalScalarFromScalars;
import org.almostrealism.util.CodeFeatures;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.function.Supplier;
import java.util.stream.IntStream;

public class AcceleratedTimeSeriesOperationsTest implements CodeFeatures, HardwareFeatures {
	private CursorPair cursors;
	private AcceleratedTimeSeries series;
	private Scalar value;

	protected AcceleratedTimeSeries series() {
		AcceleratedTimeSeries series = new AcceleratedTimeSeries(100);
		series.add(new TemporalScalar(1.0, 10));
		series.add(new TemporalScalar(2.0, 12));
		series.add(new TemporalScalar(3.0, 15));
		series.add(new TemporalScalar(3.5, 16));
		series.add(new TemporalScalar(5.0, 24));
		return series;
	}

	protected CursorPair cursors(double t) {
		CursorPair cursors = new CursorPair();
		cursors.setCursor(t);
		cursors.setDelayCursor(t + 1.0);
		return cursors;
	}

	@Test
	public void purgeTest() {
		for (int i = 0; i < 2; i++) {
			CursorPair cursors = cursors(3.2);
			AcceleratedTimeSeries series = series();
			Assert.assertEquals(5, series.getLength());

			Supplier<Runnable> r = series.purge(p(cursors));
			AcceleratedComputationOperation op = (AcceleratedComputationOperation) r.get();
			op.compile();
			System.out.println(op.getFunctionDefinition());

			op.run();

			Assert.assertEquals(3, series.getLength());
			valueAtAssertions(series);
		}
	}

	@Test
	public void valueAt() {
		AcceleratedTimeSeries series = series();
		AcceleratedTimeSeriesValueAt valueAt = new AcceleratedTimeSeriesValueAt(p(series), p(cursors(3.25)));
		AcceleratedComputationEvaluable<Scalar> compiled = (AcceleratedComputationEvaluable) valueAt.get();
		compiled.compile();
		System.out.println(compiled.getFunctionDefinition());

		Assert.assertEquals(series.valueAt(3.25).getValue(), compiled.evaluate().getValue(), Math.pow(10, -10));
	}

	protected void valueAtAssertions(AcceleratedTimeSeries series) {
		Assert.assertEquals(15.0, series.valueAt(3.0).getValue(), Math.pow(10, -10));
		Assert.assertEquals(15.5, series.valueAt(3.25).getValue(), Math.pow(10, -10));
		Assert.assertEquals(24.0, series.valueAt(4.999999).getValue(), Math.pow(10, -5));
		Assert.assertEquals(24.0, series.valueAt(5.0).getValue(), Math.pow(10, -10));
	}

	protected void init() {
		cursors = cursors(5);
		series = series();
		value = new Scalar();
	}

	protected Supplier<Runnable> add() {
		return series.add(temporal(r(p(cursors)), v(30)));
	}

	protected Supplier<Runnable> assign() {
		return a(2, p(value), series.valueAt(p(cursors)));
	}

	protected Supplier<Runnable> purge() {
		return series.purge(p(cursors));
	}

	protected Supplier<Runnable> increment() {
		return cursors.increment(v(1));
	}

	@Test
	public void addTest() {
		init();

		Supplier<Runnable> r = add();
		AcceleratedComputationOperation opr = (AcceleratedComputationOperation) r.get();

		opr.run();
		cursors.setCursor(6);
		cursors.setDelayCursor(7);

		TemporalScalarFromScalars t = (TemporalScalarFromScalars) ((ArrayVariable) opr.getArguments().get(1)).getProducer();
		ScalarFromPair s = (ScalarFromPair) t.getArguments().get(1).getProducer();
		AcceleratedComputationEvaluable<Scalar> ev = (AcceleratedComputationEvaluable) s.get();
		System.out.println(ev.getFunctionDefinition());
		Assert.assertEquals(7.0, ev.evaluate().getValue(), Math.pow(10, -10));
		Assert.assertEquals(7.0, t.get().evaluate().getTime(), Math.pow(10, -10));

		opr.run();
		Assert.assertEquals(7.0, series.get(series.getLength()).getTime(), Math.pow(10, -10));
	}

	@Test
	public void addTestWithScopeExpansion() {
		init();

		Supplier<Runnable> r = add();
		AcceleratedComputationOperation opr = (AcceleratedComputationOperation) r.get();
		Scope s = opr.compile();
		s.convertArgumentsToRequiredScopes();
		System.out.println(opr.getFunctionDefinition());
	}

	public void runAllOperations(int index) {
		Supplier<Runnable> r = add();
		Supplier<Runnable> a = assign();
		Supplier<Runnable> p = purge();
		Supplier<Runnable> i = increment();

		AcceleratedComputationOperation opr = (AcceleratedComputationOperation) r.get();
		opr.compile();

		AcceleratedComputationOperation opa = (AcceleratedComputationOperation) a.get();
		opa.compile();

		AcceleratedComputationOperation opp = (AcceleratedComputationOperation) p.get();
		opp.compile();

		AcceleratedComputationOperation opi = (AcceleratedComputationOperation) i.get();
		opi.compile();

		opr.run();
		opa.run();
		opp.run();
		opi.run();

		postPurgeAssertions(index);
	}

	protected void postPurgeAssertions(int index) {
		Assert.assertEquals(2, series.getLength());
		Assert.assertEquals(30.0, series.valueAt(index + 6.0).getValue(), Math.pow(10, -10));
		Assert.assertEquals(30.0, series.valueAt(p(cursors)).get().evaluate().getValue(), Math.pow(10, -10));
	}

	@Test
	public void allOperationsTest() {
		init();
		IntStream.range(0, 25).forEach(this::runAllOperations);
		System.out.println(cursors);
	}

	protected OperationList operationList(boolean enableCompilation) {
		OperationList op = new OperationList(enableCompilation);
		op.add(add());
		op.add(assign());
		op.add(purge());
		op.add(increment());
		return op;
	}

	protected void operationListAssertions(OperationList opl) {
		Runnable op = opl.get();
		if (op instanceof OperationAdapter)
			((OperationAdapter) op).compile();

		IntStream.range(0, 25).forEach(i -> {
			op.run();
			postPurgeAssertions(i);
		});
	}

	@Test
	public void operationListTest() {
		init();
		operationListAssertions(operationList(false));
	}

	@Test
	public void operationListCompiledTest() {
		init();
		operationListAssertions(operationList(true));
	}
}
