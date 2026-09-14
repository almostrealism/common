/*
 * Copyright 2026 Michael Murray
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

import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.AdjustableDelayCell;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.time.AcceleratedTimeSeries;
import org.almostrealism.time.CursorPair;
import org.almostrealism.time.TemporalScalar;
import org.almostrealism.time.computations.AcceleratedTimeSeriesAdd;
import org.almostrealism.time.computations.AcceleratedTimeSeriesPurge;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * Verifies that purging an {@link AcceleratedTimeSeries} reclaims the storage it frees.
 *
 * <p>Purging advances the begin cursor while the end cursor only ever grows, so a series
 * that is continuously fed and purged, such as the delay line inside
 * {@link AdjustableDelayCell}, must compact its live entries back to the front of the
 * storage before the end cursor walks past the allocation. Without that, the accelerated
 * add writes outside the buffer once the total number of entries ever added reaches the
 * capacity, regardless of how few entries are actually live.</p>
 */
public class AcceleratedTimeSeriesPurgeCompactionTest extends TestSuiteBase implements CodeFeatures {
	/**
	 * A series that has been filled to capacity and then purged must accept new entries
	 * again, and the entries that survived the purge must still interpolate correctly
	 * after being moved to the front of the storage.
	 */
	@Test(timeout = 30000)
	public void purgeReclaimsSlotsOfFullSeries() {
		AcceleratedTimeSeries series = new AcceleratedTimeSeries(6);

		int added = 0;
		while (true) {
			try {
				series.add(new TemporalScalar(added + 1.0, (added + 1) * 10.0));
				added++;
			} catch (RuntimeException e) {
				break;
			}
		}

		Assert.assertEquals(added, series.getLength());
		Assert.assertEquals(10.0 * added, series.valueAt(added).getValue(), 1e-10);

		series.purge(p(new CursorPair(3.5, 4.5))).get().run();

		int surviving = added - 2;
		Assert.assertEquals(surviving, series.getLength());
		Assert.assertEquals(30.0, series.valueAt(3.0).getValue(), 1e-10);
		Assert.assertEquals(45.0, series.valueAt(4.5).getValue(), 1e-10);
		Assert.assertEquals(10.0 * added, series.valueAt(added).getValue(), 1e-10);

		// Two slots were freed by the purge, so two more entries must fit
		series.add(new TemporalScalar(added + 1.0, (added + 1) * 10.0));
		series.add(new TemporalScalar(added + 2.0, (added + 2) * 10.0));
		Assert.assertEquals(surviving + 2, series.getLength());
		Assert.assertEquals(10.0 * added + 5.0, series.valueAt(added + 0.5).getValue(), 1e-10);
		Assert.assertEquals(10.0 * (added + 2), series.valueAt(added + 2).getValue(), 1e-10);
	}

	/**
	 * A delay line sized for its delay window, as the {@link AdjustableDelayCell}
	 * constructor documents, must keep its end cursor inside the allocation for
	 * arbitrarily many ticks while still producing the delayed signal.
	 */
	@Test(timeout = 60000)
	public void delayCellEndCursorStaysWithinBuffer() {
		int sampleRate = 100;
		int delayFrames = 4;
		int bufferSize = 2 * delayFrames;

		AdjustableDelayCell delay = new AdjustableDelayCell(sampleRate,
				c(delayFrames / (double) sampleRate), c(1.0), bufferSize);
		PackedCollection out = new PackedCollection(1);
		delay.setReceptor(protein -> a(1, p(out), protein));
		delay.setup().get().run();

		OperationList ops = new OperationList("Delay Push and Tick");
		ops.add(delay.push(c(0.1)));
		ops.add(delay.tick());
		Runnable op = ops.get();

		AcceleratedTimeSeries buffer = delay.getBuffer();
		long allocation = buffer.getCountLong();

		IntStream.range(0, 4 * bufferSize).forEach(i -> {
			op.run();

			double end = buffer.get(0).getB();
			Assert.assertTrue("tick " + i + ": end cursor " + end + " is outside the allocation of " + allocation,
					end < allocation);

			if (i >= delayFrames) {
				Assert.assertEquals("tick " + i, 0.1, out.toDouble(), 1e-6);
			}
		});
	}

	/**
	 * A delay line fed distinct values (rather than a constant) must reproduce every
	 * pushed sample after the configured delay, even across the buffer-full compaction
	 * boundary. A constant input cannot distinguish a dropped sample from a surviving
	 * one, since every entry carries the same value; this test uses a distinct value
	 * per tick so a dropped or corrupted entry changes the observed output.
	 *
	 * <p>Each pushed value is compiled to a literal at whatever precision the buffer's
	 * own memory provider uses, so the value actually stored (and expected back unchanged
	 * by compaction) is rounded to that precision rather than the original double. The
	 * expectation is narrowed by checking {@link org.almostrealism.hardware.MemoryData#getMem()}'s
	 * {@link io.almostrealism.code.MemoryProvider#getNumberSize()} on the delay buffer itself,
	 * rather than assuming a fixed precision or relying on {@link Hardware#getPrecision()}'s
	 * aggregate across every active {@link io.almostrealism.code.DataContext} (which can
	 * disagree with the context that actually compiled and ran this buffer's kernels).</p>
	 *
	 * <p>The distinct per-tick value is derived from a counter that lives on the device and is
	 * advanced by the compiled operation graph itself, rather than a fresh compile-time literal
	 * per tick or a host-computed value pushed in from Java, so the kernel built below is
	 * compiled once and reused for every tick.</p>
	 */
	@Test(timeout = 60000)
	public void delayCellPreservesDistinctSamplesAcrossCompaction() {
		int sampleRate = 100;
		int delayFrames = 4;
		int bufferSize = 2 * delayFrames;

		AdjustableDelayCell delay = new AdjustableDelayCell(sampleRate,
				c(delayFrames / (double) sampleRate), c(1.0), bufferSize);
		PackedCollection out = new PackedCollection(1);
		delay.setReceptor(protein -> a(1, p(out), protein));
		delay.setup().get().run();

		int ticks = 6 * bufferSize;

		PackedCollection counter = new PackedCollection(1);
		OperationList ops = new OperationList("Delay Push and Tick");
		ops.add(delay.push(cp(counter).add(1.0).multiply(0.001)));
		ops.add(delay.tick());
		ops.add(a(1, p(counter), cp(counter).add(1.0)));
		Runnable op = ops.get();

		for (int i = 0; i < ticks; i++) {
			op.run();

			if (i >= delayFrames) {
				double input = (i - delayFrames + 1) * 0.001;
				int numberSize = delay.getBuffer().getMem().getProvider().getNumberSize();
				double expected = numberSize == 8 ? input : (float) input;
				Assert.assertEquals("tick " + i, expected, out.toDouble(), 1e-9);
			}
		}
	}

	/**
	 * The deprecated two-argument {@link AcceleratedTimeSeriesAdd} constructor must still add
	 * entries correctly, delegating to the guarded constructor with the {@code NO_CAPACITY_GUARD}
	 * sentinel so existing callers keep working unmodified.
	 */
	@Test(timeout = 10000)
	public void deprecatedAddConstructorStillAdds() {
		AcceleratedTimeSeries series = new AcceleratedTimeSeries(6);
		CursorPair cursors = new CursorPair(0.0, 1.0);

		Supplier<Runnable> op = new AcceleratedTimeSeriesAdd(p(series),
				(Producer) temporal(r(p(cursors)), c(42.0)));
		op.get().run();

		int expectedEntries = 1;
		Assert.assertEquals(expectedEntries, series.getLength());
		Assert.assertEquals(42.0, series.valueAt(1.0).getValue(), 1e-10);
	}

	/**
	 * The deprecated three-argument {@link AcceleratedTimeSeriesPurge} constructor must preserve
	 * the pre-compaction behavior: the begin cursor still advances past stale entries, but the
	 * slots it frees are never reclaimed, since it uses the {@code NO_COMPACTION} sentinel. A
	 * series purged through this constructor therefore stays full even after entries are purged.
	 */
	@Test(timeout = 10000)
	public void deprecatedPurgeConstructorNeverCompacts() {
		AcceleratedTimeSeries series = new AcceleratedTimeSeries(6);

		int added = 0;
		while (true) {
			try {
				series.add(new TemporalScalar(added + 1.0, (added + 1) * 10.0));
				added++;
			} catch (RuntimeException e) {
				break;
			}
		}

		Assert.assertEquals(added, series.getLength());

		double everyCall = 1.0;
		new AcceleratedTimeSeriesPurge(p(series), p(new CursorPair(3.5, 4.5)), everyCall).get().run();

		int surviving = added - 2;
		Assert.assertEquals(surviving, series.getLength());

		try {
			series.add(new TemporalScalar(added + 1.0, (added + 1) * 10.0));
			Assert.fail("Expected the series to remain full because compaction is disabled");
		} catch (RuntimeException expected) {
			// Legacy behavior: freed slots are not reclaimed without compaction.
		}
	}
}
