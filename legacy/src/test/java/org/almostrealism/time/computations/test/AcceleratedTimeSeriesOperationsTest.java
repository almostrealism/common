package org.almostrealism.time.computations.test;

import org.almostrealism.algebra.Scalar;
import org.almostrealism.hardware.AcceleratedComputationOperation;
import org.almostrealism.hardware.AcceleratedComputationEvaluable;
import org.almostrealism.time.AcceleratedTimeSeries;
import org.almostrealism.time.CursorPair;
import org.almostrealism.time.TemporalScalar;
import org.almostrealism.time.computations.AcceleratedTimeSeriesValueAt;
import org.almostrealism.util.CodeFeatures;
import org.junit.Assert;
import org.junit.Test;

import java.util.function.Supplier;

public class AcceleratedTimeSeriesOperationsTest implements CodeFeatures {
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
	public void purge() {
		for (int i = 0; i < 2; i++) {
			CursorPair cursors = cursors(3.2);
			AcceleratedTimeSeries series = series();
			Assert.assertEquals(5, series.getLength());

			Supplier<Runnable> r = series.purge(p(cursors));
			AcceleratedComputationOperation op = (AcceleratedComputationOperation) r.get();
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
		System.out.println(compiled.getFunctionDefinition());

		Assert.assertEquals(series.valueAt(3.25).getValue(), compiled.evaluate().getValue(), Math.pow(10, -10));
	}

	protected void valueAtAssertions(AcceleratedTimeSeries series) {
		Assert.assertEquals(15.0, series.valueAt(3.0).getValue(), Math.pow(10, -10));
		Assert.assertEquals(15.5, series.valueAt(3.25).getValue(), Math.pow(10, -10));
		Assert.assertEquals(24.0, series.valueAt(4.999999).getValue(), Math.pow(10, -5));
		Assert.assertEquals(24.0, series.valueAt(5.0).getValue(), Math.pow(10, -10));
	}
}
