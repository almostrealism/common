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
import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.AcceleratedComputationOperation;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.time.AcceleratedTimeSeries;
import org.almostrealism.time.CursorPair;
import org.almostrealism.time.TemporalScalar;
import org.almostrealism.time.computations.AcceleratedTimeSeriesValueAt;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * Tests for accelerated time series operations.
 */
public class AcceleratedTimeSeriesOperationsTest extends TestSuiteBase implements CodeFeatures {
	/** Cursor pair for time series operations. */
	private CursorPair cursors;

	/** Accelerated time series under test. */
	private AcceleratedTimeSeries series;

	/** Collection to hold values. */
	private PackedCollection value;

	/**
	 * Creates a test time series with sample data.
	 */
	protected AcceleratedTimeSeries series() {
		AcceleratedTimeSeries series = new AcceleratedTimeSeries(100);
		series.add(new TemporalScalar(1.0, 10));
		series.add(new TemporalScalar(2.0, 12));
		series.add(new TemporalScalar(3.0, 15));
		series.add(new TemporalScalar(3.5, 16));
		series.add(new TemporalScalar(5.0, 24));
		return series;
	}

	/**
	 * Creates a cursor pair at the specified time.
	 */
	protected CursorPair cursors(double t) {
		CursorPair cursors = new CursorPair();
		cursors.setCursor(t);
		cursors.setDelayCursor(t + 1.0);
		return cursors;
	}

	/**
	 * Tests purge operation on accelerated time series.
	 */
	@Test(timeout = 10000)
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

	/**
	 * Tests valueAt operation on accelerated time series.
	 */
	@Test(timeout = 10000)
	public void valueAt() {
		AcceleratedTimeSeries series = series();
		AcceleratedTimeSeriesValueAt valueAt = new AcceleratedTimeSeriesValueAt(p(series), p(cursors(3.25)));
		Evaluable<PackedCollection> compiled = valueAt.get();
		Assert.assertEquals(series.valueAt(3.25).toDouble(1), compiled.evaluate().toDouble(), Math.pow(10, -10));
	}

	/**
	 * Validates valueAt results for the test series.
	 */
	protected void valueAtAssertions(AcceleratedTimeSeries series) {
		Assert.assertEquals(15.0, series.valueAt(3.0).getValue(), Math.pow(10, -10));
		Assert.assertEquals(15.5, series.valueAt(3.25).getValue(), Math.pow(10, -10));
		Assert.assertEquals(24.0, series.valueAt(4.999999).getValue(), Math.pow(10, -5));
		Assert.assertEquals(24.0, series.valueAt(5.0).getValue(), Math.pow(10, -10));
	}

	/**
	 * Initializes test fixtures.
	 */
	protected void init() {
		cursors = cursors(5);
		series = series();
		value = new PackedCollection(1);
	}

	/**
	 * Creates an add operation for the test series.
	 */
	protected Supplier<Runnable> add() {
		return series.add((Producer) temporal(r(p(cursors)), c(30)));
	}

	/**
	 * Creates an assign operation for the test series.
	 */
	protected Supplier<Runnable> assign() {
		return a(1, p(value), series.valueAt(p(cursors)));
	}

	/**
	 * Creates a purge operation for the test series.
	 */
	protected Supplier<Runnable> purge() {
		return series.purge(p(cursors));
	}

	/**
	 * Creates an increment operation for cursors.
	 */
	protected Supplier<Runnable> increment() {
		return cursors.increment(c(1));
	}

	/**
	 * Tests add operation on accelerated time series.
	 */
	@Test(timeout = 10000)
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

	/**
	 * Runs all test operations for a given index.
	 */
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

	/**
	 * Validates state after purge operations.
	 */
	protected void postPurgeAssertions(int index) {
		Assert.assertEquals(2, series.getLength());
		Assert.assertEquals(30.0, series.valueAt(index + 6.0).getValue(), Math.pow(10, -10));
		Assert.assertEquals(30.0, series.valueAt(p(cursors)).get().evaluate().toDouble(), Math.pow(10, -10));
	}

	/**
	 * Tests all operations on accelerated time series.
	 */
	// TODO  @Test(timeout = 10000)
	public void allOperationsTest() {
		init();
		IntStream.range(0, 25).forEach(this::runAllOperations);
		log(String.valueOf(cursors));
	}

	/**
	 * Creates an operation list for testing.
	 */
	protected OperationList operationList(boolean enableCompilation) {
		OperationList op = new OperationList("Accelerated Time Series Operations Test", enableCompilation);
		op.add(add());
		op.add(assign());
		op.add(purge());
		op.add(increment());
		return op;
	}

	/**
	 * Validates operation list execution results.
	 */
	protected void operationListAssertions(OperationList opl) {
		Runnable op = opl.get();

		IntStream.range(0, 25).forEach(i -> {
			op.run();
			postPurgeAssertions(i);
		});
	}

	/**
	 * Tests operation list creation.
	 */
	// TODO  @Test(timeout = 10000)
	public void operationListTest() {
		init();
		operationListAssertions(operationList(false));
	}

	/**
	 * Tests compiled operation list creation.
	 */
	// TODO  @Test(timeout = 10000)
	public void operationListCompiledTest() {
		init();
		operationListAssertions(operationList(true));
	}
}
