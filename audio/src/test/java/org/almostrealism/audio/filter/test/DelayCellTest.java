/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.audio.filter.test;

import io.almostrealism.compute.Process;
import io.almostrealism.profile.OperationProfile;
import io.almostrealism.relation.Producer;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.CellList;
import org.almostrealism.audio.filter.DelayNetwork;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.sources.SineWaveCell;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.heredity.CellularTemporalFactor;
import org.almostrealism.time.TemporalRunner;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

import java.io.File;
import java.util.function.Supplier;

public class DelayCellTest implements CellFeatures, TestFeatures {
	@Test
	public void delay() {
		CellList c = w(0, "Library/Snare Perc DD.wav")
				.d(i -> c(2.0))
				.o(i -> new File("results/delay-cell-test.wav"));
		Supplier<Runnable> r = c.sec(6);
		r.get().run();
	}

	@Test
	public void delaySum() {
		CellList c = w(0, "Library/Snare Perc DD.wav", "Library/Snare Perc DD.wav")
				.d(i -> i > 0 ? c(2.0) : c(1.0))
				.sum()
				.o(i -> new File("results/delay-cell-sum-test.wav"));
		Supplier<Runnable> r = c.sec(6);
		r.get().run();
	}

	@Test
	public void delayScaleFactor() {
		CellList c = w(0, "Library/Snare Perc DD.wav")
				.d(i -> c(2.0))
				.map(fc(i -> sf(0.5)))
				.o(i -> new File("results/delay-cell-scale-factor-test.wav"));
		Supplier<Runnable> r = c.sec(6);
		r.get().run();
	}

	@Test
	public void filter() {
		Supplier<Runnable> r =
				w(0, "Library/Snare Perc DD.wav")
						.f(i -> hp(2000, 0.1))
						.d(i -> c(2.0))
						.o(i -> new File("results/filter-delay-cell.wav"))
						.sec(6);
		r.get().run();
	}

	@Test
	public void filterLoopComparison() {
		Supplier<Runnable> r =
				iter(w(0, "Library/Snare Perc DD.wav")
						.f(i -> hp(2000, 0.1))
						.d(i -> c(2.0))
						.o(i -> new File("results/filter-loop-comparison-a.wav")),
						t -> loop(t.tick(), 6 * OutputLine.sampleRate), true);

		OperationProfile profiles = new OperationProfile("Native Loop");
		OperationProfile hardwareProfile = new OperationProfile("HardwareOperator");
		HardwareOperator.timingListener = hardwareProfile.getTimingListener();

		System.out.println("Running native loop...");
		((OperationList) r).get(profiles).run();
		profiles.print();
		System.out.println();
		hardwareProfile.print();
		System.out.println("\n-----\n");

		r =
				iter(w(0, "Library/Snare Perc DD.wav")
								.f(i -> hp(2000, 0.1))
								.d(i -> c(2.0))
								.o(i -> new File("results/filter-loop-comparison-b.wav")),
						t -> loop(Process.isolated(t.tick()), 6 * OutputLine.sampleRate), true);

		profiles = new OperationProfile("Java Loop");
		hardwareProfile = new OperationProfile("HardwareOperator");
		HardwareOperator.timingListener = hardwareProfile.getTimingListener();

		System.out.println("Running Java loop...");
		((OperationList) r).get(profiles).run();
		profiles.print();
		System.out.println();
		hardwareProfile.print();
		System.out.println();
	}

	@Test
	public void reverb() {
		Supplier<Runnable> r =
				iter(w(0, "Library/Snare Perc DD.wav")
						.f(i -> hp(2000, 0.1))
						.d(i -> c(2.0))
						.map(fc(i -> new DelayNetwork(32, OutputLine.sampleRate, false)))
						.o(i -> new File("results/reverb-delay-cell-test.wav")),
						t -> new TemporalRunner(t, 6 * OutputLine.sampleRate), true);

		r.get().run();
	}

	@Test
	public void adjust() {
		SineWaveCell generator = new SineWaveCell();
		generator.setPhase(0.5);
		generator.setNoteLength(0);
		generator.setFreq(3.424);
		generator.setAmplitude(1.0);

		PackedCollection v = new PackedCollection(1);

		CellularTemporalFactor<PackedCollection> adjustment = generator.toFactor(() -> v, this::a);

		CellList cells = w(0, "Library/Snare Perc DD.wav");
		cells.addRequirement(adjustment);

		cells = cells
				.d(i -> c(2.6), i -> c(2.0).add(adjustment.getResultant(c(1.0))))
				.o(i -> new File("results/adjust-delay-cell-test.wav"));

		Supplier<Runnable> r = cells.sec(7.5);
		r.get().run();

		log(v);
	}

	@Test
	public void abortDelay() {
		PackedCollection abortFlag = new PackedCollection(1);
		OperationList.setAbortFlag(abortFlag);

		Supplier<Runnable> r =
				w(0, "Library/Snare Perc DD.wav")
						.d(i -> c(2.0))
						.o(i -> new File("results/delay-cell-abort-test.wav"))
						.sec(120);
		Runnable op = r.get();

		Runnable abort = a(1, (Producer) p((PackedCollection) OperationList.getAbortFlag()), (Producer) scalar(1.0)).get();

		new Thread(() -> {
			try {
				Thread.sleep(40);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}

			abort.run(); }).start();

		op.run();
	}
}
