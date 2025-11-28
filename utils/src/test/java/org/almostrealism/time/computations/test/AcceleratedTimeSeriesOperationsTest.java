/*
 * Copyright 2025 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.time.computations.test;

import io.almostrealism.relation.Evaluable;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.AcceleratedComputationOperation;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.time.AcceleratedTimeSeries;
import org.almostrealism.time.CursorPair;
import org.almostrealism.time.TemporalScalar;
import org.almostrealism.time.computations.AcceleratedTimeSeriesValueAt;
import org.almostrealism.CodeFeatures;
import org.junit.Assert;
import org.junit.Test;

import java.util.function.Supplier;
import java.util.stream.IntStream;

public class AcceleratedTimeSeriesOperationsTest implements CodeFeatures {
	private CursorPair cursors;
	private AcceleratedTimeSeries series;
	private PackedCollection value;

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
//		dc(() -> {
			for (int i = 0; i < 2; i++) {
//				cc(() -> {
					CursorPair cursors = cursors(3.2);
					AcceleratedTimeSeries series = series();
					Assert.assertEquals(5, series.getLength());

					Supplier<Runnable> r = series.purge(p(cursors));
					AcceleratedComputationOperation op = (AcceleratedComputationOperation) r.get();

					op.run();

					Assert.assertEquals(3, series.getLength());
					valueAtAssertions(series);
//				}, ComputeRequirement.CL);
			}
//		});
	}

	@Test
	public void valueAt() {
		AcceleratedTimeSeries series = series();
		AcceleratedTimeSeriesValueAt valueAt = new AcceleratedTimeSeriesValueAt(p(series), p(cursors(3.25)));
		Evaluable<PackedCollection> compiled = valueAt.get();
		Assert.assertEquals(series.valueAt(3.25).toDouble(1), compiled.evaluate().toDouble(), Math.pow(10, -10));
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
		value = new PackedCollection(1);
	}

	protected Supplier<Runnable> add() {
		return series.add((io.almostrealism.relation.Producer) temporal(r(p(cursors)), c(30)));
	}

	protected Supplier<Runnable> assign() {
		return a(1, p(value), series.valueAt(p(cursors)));
	}

	protected Supplier<Runnable> purge() {
		return series.purge(p(cursors));
	}

	protected Supplier<Runnable> increment() {
		return cursors.increment(c(1));
	}

	@Test
	public void addTest() {
		init();

		Supplier<Runnable> r = add();
		AcceleratedComputationOperation opr = (AcceleratedComputationOperation) r.get();

		opr.run();
		cursors.setCursor(6);
		cursors.setDelayCursor(7);

		opr.run();
		Assert.assertEquals(7.0, series.get(series.getLength()).getTime(), Math.pow(10, -10));
	}

	public void runAllOperations(int index) {
		Supplier<Runnable> r = add();
		Supplier<Runnable> a = assign();
		Supplier<Runnable> p = purge();
		Supplier<Runnable> i = increment();

		AcceleratedComputationOperation opr = (AcceleratedComputationOperation) r.get();
		AcceleratedComputationOperation opa = (AcceleratedComputationOperation) a.get();
		AcceleratedComputationOperation opp = (AcceleratedComputationOperation) p.get();
		AcceleratedComputationOperation opi = (AcceleratedComputationOperation) i.get();

		opr.run();
		opa.run();
		opp.run();
		opi.run();

		postPurgeAssertions(index);
	}

	protected void postPurgeAssertions(int index) {
		Assert.assertEquals(2, series.getLength());
		Assert.assertEquals(30.0, series.valueAt(index + 6.0).getValue(), Math.pow(10, -10));
		Assert.assertEquals(30.0, series.valueAt(p(cursors)).get().evaluate().toDouble(), Math.pow(10, -10));
	}

	// TODO  @Test
	public void allOperationsTest() {
		init();
		IntStream.range(0, 25).forEach(this::runAllOperations);
		System.out.println(cursors);
	}

	protected OperationList operationList(boolean enableCompilation) {
		OperationList op = new OperationList("Accelerated Time Series Operations Test", enableCompilation);
		op.add(add());
		op.add(assign());
		op.add(purge());
		op.add(increment());
		return op;
	}

	protected void operationListAssertions(OperationList opl) {
		Runnable op = opl.get();

		IntStream.range(0, 25).forEach(i -> {
			op.run();
			postPurgeAssertions(i);
		});
	}

	// TODO  @Test
	public void operationListTest() {
		init();
		operationListAssertions(operationList(false));
	}

	// TODO  @Test
	public void operationListCompiledTest() {
		init();
		operationListAssertions(operationList(true));
	}
}
