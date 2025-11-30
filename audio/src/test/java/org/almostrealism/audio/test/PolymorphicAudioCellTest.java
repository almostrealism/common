/*
 * Copyright 2022 Michael Murray
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

package org.almostrealism.audio.test;

import io.almostrealism.code.Computation;
import io.almostrealism.relation.Factor;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.CellList;
import org.almostrealism.audio.PolymorphicAudioCell;
import org.almostrealism.audio.WaveOutput;
import org.almostrealism.audio.computations.DefaultEnvelopeComputation;
import org.almostrealism.audio.data.PolymorphicAudioData;
import org.almostrealism.audio.filter.BasicDelayCell;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.sources.SineWaveCell;
import org.almostrealism.audio.tone.DefaultKeyboardTuning;
import org.almostrealism.audio.tone.WesternChromatic;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Cell;
import org.almostrealism.graph.CellPair;
import org.almostrealism.graph.MultiCell;
import org.almostrealism.graph.Receptor;
import org.almostrealism.graph.ReceptorCell;
import org.almostrealism.graph.temporal.CollectionTemporalCellAdapter;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.hardware.computations.Loop;
import org.almostrealism.heredity.Gene;
import org.almostrealism.heredity.IdentityFactor;
import org.almostrealism.time.TemporalRunner;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

public class PolymorphicAudioCellTest implements CellFeatures, TestFeatures {
	public static final boolean enableRunner = true;
	public static final boolean enableLoop = false;
	private static final int DURATION_FRAMES = 10 * OutputLine.sampleRate;

	protected Receptor<PackedCollection> loggingReceptor() {
		return protein -> () -> () -> System.out.println(protein.get().evaluate().toDouble(0));
	}

	protected Cell<PackedCollection> loggingCell() { return new ReceptorCell<>(loggingReceptor()); }

	protected CellList cells(int count) {
		return poly(count, PolymorphicAudioData::new, i -> c(0.5),
				new DefaultKeyboardTuning().getTone(WesternChromatic.F3),
				new DefaultKeyboardTuning().getTone(WesternChromatic.G3));
	}

	protected CollectionTemporalCellAdapter cell() {
		SineWaveCell fcell = new SineWaveCell();
		fcell.setFreq(new DefaultKeyboardTuning().getTone(WesternChromatic.F3).asHertz());
		fcell.setNoteLength(600);
		fcell.setAmplitude(0.1);
		fcell.setEnvelope(DefaultEnvelopeComputation::new);

		SineWaveCell gcell = new SineWaveCell();
		gcell.setFreq(new DefaultKeyboardTuning().getTone(WesternChromatic.G3).asHertz());
		gcell.setNoteLength(600);
		gcell.setAmplitude(0.1);
		gcell.setEnvelope(DefaultEnvelopeComputation::new);

		return new PolymorphicAudioCell(c(0.5), Arrays.asList(fcell, gcell));
	}

	@Test
	public void sineWave() {
		CollectionTemporalCellAdapter cell = cell();
		cell.setReceptor(loggingReceptor());
		Runnable push = cell.push(c(0.0)).get();
		IntStream.range(0, 100).forEach(i -> push.run());
		// TODO  Add assertions
	}

	@Test
	public void withOutput() {
		WaveOutput output = new WaveOutput(new File("results/polymorphic-cell-test.wav"));

		CellList cells = cells(1);
		cells.get(0).setReceptor(output.getWriter(0));

		if (enableRunner) {
			TemporalRunner runner = new TemporalRunner(cells, DURATION_FRAMES);
			runner.get().run();
		} else {
			CollectionTemporalCellAdapter cell = (CollectionTemporalCellAdapter) cells.get(0);

			OperationList list = new OperationList("PolymorphicAudioCell Push and Tick");
			list.add(cell.push(c(0.0)));
			list.add(cell.tick());
			Loop loop = (Loop) lp(list, enableLoop ? DURATION_FRAMES : 1);

			Runnable setup = cells.setup().get();
			Runnable tick = loop.get();

			setup.run();

			IntStream.range(0, enableLoop ? 1 : DURATION_FRAMES).forEach(i -> {
				tick.run();
				if ((i + 1) % 10000 == 0) System.out.println("PolymorphicAudioCellTest: " + (i + 1) + " iterations");
			});
		}

		System.out.println("PolymorphicAudioCellTest: Writing WAV...");
		output.write().get().run();
		System.out.println("PolymorphicAudioCellTest: Done");
	}

	@Test
	public void comparison() {
		WaveOutput output1 = new WaveOutput(new File("results/poly-comparison-a.wav"));
		WaveOutput output2 = new WaveOutput(new File("results/poly-comparison-b.wav"));

		CellList cells1 = cells(1);
		CellList cells2 = cells(1);
		cells1.get(0).setReceptor(output1.getWriter(0));
		cells2.get(0).setReceptor(output2.getWriter(0));
		CollectionTemporalCellAdapter cell1 = (CollectionTemporalCellAdapter) cells1.get(0);
		CollectionTemporalCellAdapter cell2 = (CollectionTemporalCellAdapter) cells2.get(0);

		/* One */
		OperationList list1 = new OperationList("One");
		list1.add(cell1.push(c(0.0)));
		list1.add(cell1.tick());
		Loop loop1 = (Loop) lp(list1, DURATION_FRAMES);
		Runnable setup1 = cells1.setup().get();
		Runnable tick1 = loop1.get();

		/* Two */
		Computation list2 = (Computation) cells2.tick();
		Loop loop2 = (Loop) lp(list2, DURATION_FRAMES);
		Runnable setup2 = cells2.setup().get();
		Runnable tick2 = loop2.get();

		/* Run Both */
		setup1.run();
		tick1.run();
		setup2.run();
		tick2.run();

		System.out.println("PolymorphicAudioCellTest: Writing WAV...");
		output1.write().get().run();
		System.out.println("PolymorphicAudioCellTest: Done");

		System.out.println("PolymorphicAudioCellTest: Writing WAV...");
		output2.write().get().run();
		System.out.println("PolymorphicAudioCellTest: Done");
	}

	@Test
	public void compareOneAndTwo() {
		WaveOutput output1 = new WaveOutput(new File("results/multi-poly-comparison-a.wav"));
		WaveOutput output2 = new WaveOutput(new File("results/multi-poly-comparison-b1.wav"));
		WaveOutput output3 = new WaveOutput(new File("results/multi-poly-comparison-b2.wav"));

		CellList cells1 = cells(1);
		CellList cells2 = cells(2);
		cells1.get(0).setReceptor(output1.getWriter(0));
		cells2.get(0).setReceptor(output2.getWriter(0));
		cells2.get(1).setReceptor(output3.getWriter(0));

		/* One */
		Computation list1 = (Computation) cells1.tick();
		Loop loop1 = (Loop) loop(list1, DURATION_FRAMES);
		Runnable setup1 = cells1.setup().get();
		Runnable tick1 = loop1.get();

		/* Two */
		Computation list2 = (Computation) cells2.tick();
		Loop loop2 = (Loop) loop(list2, DURATION_FRAMES);
		Runnable setup2 = cells2.setup().get();
		Runnable tick2 = loop2.get();

		/* Run Both */
		setup1.run();
		tick1.run();
		setup2.run();
		tick2.run();

		System.out.println("PolymorphicAudioCellTest: Writing WAV...");
		output1.write().get().run();
		System.out.println("PolymorphicAudioCellTest: Done");

		System.out.println("PolymorphicAudioCellTest: Writing WAV...");
		output2.write().get().run();
		System.out.println("PolymorphicAudioCellTest: Done");

		System.out.println("PolymorphicAudioCellTest: Writing WAV...");
		output3.write().get().run();
		System.out.println("PolymorphicAudioCellTest: Done");
	}

	protected <T> Gene<T> identityGene() {
		return new Gene<>() {
			@Override public Factor<T> valueAt(int index) { return new IdentityFactor<>(); }
			@Override public int length() { return 1; }
		};
	}

	protected void loggingCellPair(Cell<PackedCollection> input) {
		List<Cell<PackedCollection>> cells = new ArrayList<>();
		cells.add(loggingCell());

		MultiCell<PackedCollection> m = new MultiCell<>(cells, identityGene());
		m.setName("LoggingMultiCell");
		new CellPair(input, m, null, new IdentityFactor<>()).init();
	}

	@Test
	public void withCellPair() {
		CollectionTemporalCellAdapter cell = cell();
		loggingCellPair(cell);

		Runnable push = cell.push(null).get();
		IntStream.range(0, 100).forEach(i -> push.run());
	}

	@Test
	public void withBasicDelayCell() {
		BasicDelayCell delay = new BasicDelayCell(1);
		delay.setReceptor(loggingReceptor());

		CollectionTemporalCellAdapter cell = cell();
		cell.setReceptor(delay);

		Runnable push = cell.push(null).get();
		Runnable tick = delay.tick().get();

		IntStream.range(0, 200).forEach(i -> {
			push.run();
			tick.run();
		});
	}
}
