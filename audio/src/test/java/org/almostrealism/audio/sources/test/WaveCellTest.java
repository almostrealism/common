/*
 * Copyright 2025 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package org.almostrealism.audio.sources.test;

import io.almostrealism.relation.Evaluable;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.CellList;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.ReceptorCell;
import org.almostrealism.graph.TimeCell;
import org.almostrealism.graph.temporal.DefaultWaveCellData;
import org.almostrealism.graph.temporal.WaveCell;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.heredity.ScaleFactor;
import org.almostrealism.time.TemporalList;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

public class WaveCellTest implements CellFeatures, TestFeatures {
	protected WaveCell cell() throws IOException {
		return WaveData.load(new File("src/main/resources/test.wav"))
						.toCell(0, 1000, null, c(10))
				.apply(new DefaultWaveCellData());
	}

	@Test
	public void push() throws IOException {
		WaveCell cell = cell();
		cell.setReceptor(protein -> {
			Evaluable<? extends PackedCollection> ev = protein.get();
			return () -> () -> System.out.println(ev.evaluate().toDouble(0));
		});

		OperationList l = (OperationList) cell.push(c(0.0));

		Runnable r = l.get();
		IntStream.range(0, 100).forEach(i -> r.run());
	}

	@Test
	public void endless() {
		if (skipLongTests || testDepth < 1) return;

		AtomicInteger total = new AtomicInteger();

		IntStream.range(0, 100).forEach(x -> {
			dc(() -> {
				CellList cells = w(0, "Library/Snare Perc DD.wav")
						.o(i -> new File("results/snare-clean-test.wav"));
				Runnable r = cells.sec(70).get();

				for (int i = 0; i < 80; i++) {
					r.run();
					int tot = total.incrementAndGet();

					if (tot % 10 == 0) {
						System.out.println(Instant.now() + " - Completed " + tot);
					}
				}
			});
		});
	}

	@Test
	public void clean() {
		int count = 8;

		CellList cells = w(0, "Library/Snare Perc DD.wav")
				.o(i -> new File("results/snare-clean-test.wav"));

		cells.sec(bpm(128).l(count)).get().run();
	}

	@Test
	public void repeatHat() {
		int count = 32;

		CellList cells = w(0, c(bpm(128).l(0.5)), c(bpm(128).l(4)),
						"Library/GT_HAT_31.wav")
				.o(i -> new File("results/hat-repeat-test.wav"));

		cells.sec(bpm(128).l(count)).get().run();
	}

	@Test
	public void repeatSnare() {
		int count = 32;

		CellList cells = w(0, c(bpm(128).l(1)), c(bpm(128).l(2)),
				"Library/Snare Perc DD.wav")
				.o(i -> new File("results/snare-repeat-test.wav"));

		cells.sec(bpm(128).l(count)).get().run();
	}

	@Test
	public void sequence() {
		int count = 32;

		CellList cells = silence().and(w(0, c(bpm(128).l(0.5)), c(bpm(128).l(1)),
							"Library/GT_HAT_31.wav"))
							.gr(bpm(128).l(count), count, i -> 1)
							.f(i -> i == 0 ? new ScaleFactor(0.5) : new ScaleFactor(0.1))
							.o(i -> new File("results/wav-cell-seq-test-" + i + ".wav"));

		cells.sec(bpm(128).l(count)).get().run();
	}

	@Test
	public void assignment() {
		PackedCollection out = new PackedCollection(1);
		CellList cells = w(0, c(0), c(bpm(128).l(2)),
				"Library/Snare Perc DD.wav")
				.map(i -> new ReceptorCell<>(protein -> a(1, p(out), protein)));
		OperationList ops = (OperationList) cells.sec(10);
		Runnable r = ops.get();
		r.run();
		System.out.println("Result after 10s: " + out.toDouble(0) + " (expected !" + 0.0 + ")");
		Assert.assertNotEquals(0.0, out.toDouble(0), 0.0);
	}

	@Test
	public void internalClock() {
		double rate = 2 * Math.PI / 1000;

		PackedCollection data = new PackedCollection(OutputLine.sampleRate).traverseEach();
		data.setMem(IntStream.range(0, OutputLine.sampleRate).mapToDouble(i -> Math.sin(i * rate)).toArray());

		WaveCell cell = new WaveCell(data, OutputLine.sampleRate);
		PackedCollection out = new PackedCollection(1);
		cell.setReceptor(protein -> a(1, p(out), protein));

		TemporalList tick = new TemporalList();
		tick.add(cell);

		TemporalList temporals = new TemporalList();
		temporals.add(() -> cell.push(c(0.0)));
		temporals.add(tick);

		OperationList op = new OperationList();
		op.add(cell.setup());
		op.add(sec(temporals, 0.3));

		Runnable r = op.get();
		r.run();

		System.out.println("Result after 0.3s: " + out.toDouble(0) + " (expected " +
				data.toDouble((int) (0.3 * OutputLine.sampleRate) - 1) + ")");
		assertEquals(data.toDouble((int) (0.3 * OutputLine.sampleRate) - 1), out.toDouble(0));
	}

	@Test
	public void externalClock() {
		double rate = 2 * Math.PI / 1000;

		PackedCollection data = new PackedCollection(OutputLine.sampleRate).traverseEach();
		data.setMem(IntStream.range(0, OutputLine.sampleRate).mapToDouble(i -> Math.sin(i * rate)).toArray());

		TimeCell clock = new TimeCell();
		WaveCell cell = new WaveCell(data, clock);

		TemporalList tick = new TemporalList();
		tick.add(clock);
		tick.add(cell);

		TemporalList temporals = new TemporalList();
		temporals.add(() -> cell.push(c(0.0)));
		temporals.add(tick);

		PackedCollection out = new PackedCollection(1);
		cell.setReceptor(protein -> a(1, p(out), protein));

		OperationList op = new OperationList();
		op.add(cell.setup());
		op.add(sec(temporals, 0.3));
		op.get().run();

		System.out.println("Clock after 0.3s: " + clock.frame().evaluate().toDouble() +
				" (expected " + 0.3 * OutputLine.sampleRate + ")");
		System.out.println("Result after 0.3s: " + out.toDouble(0) + " (expected " +
				data.toDouble((int) (0.3 * OutputLine.sampleRate) - 1) + ")");
		assertEquals(data.toDouble((int) (0.3 * OutputLine.sampleRate) - 1), out.toDouble(0));
	}
}
