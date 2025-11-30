/*
 * Copyright 2023 Michael Murray
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

import io.almostrealism.relation.Factor;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.CellList;
import org.almostrealism.audio.computations.DefaultEnvelopeComputation;
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
import org.almostrealism.heredity.Gene;
import org.almostrealism.heredity.IdentityFactor;
import org.almostrealism.time.Frequency;
import org.almostrealism.time.TemporalRunner;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

public class SineWaveCellTest implements CellFeatures, TestFeatures {
	protected static final int DURATION_FRAMES = 10 * OutputLine.sampleRate;

	protected Receptor<PackedCollection> loggingReceptor() {
		return protein -> () -> () -> System.out.println(protein.get().evaluate().toDouble(0));
	}

	protected Cell<PackedCollection> loggingCell() { return new ReceptorCell<>(loggingReceptor()); }

	protected SineWaveCell cell() {
		SineWaveCell cell = new SineWaveCell();
		cell.setFreq(new DefaultKeyboardTuning().getTone(WesternChromatic.G3).asHertz());
		cell.setNoteLength(600);
		cell.setAmplitude(0.1);
		cell.setEnvelope(DefaultEnvelopeComputation::new);
		return cell;
	}

	@Test
	public void sineWave() {
		SineWaveCell cell = cell();
		cell.setReceptor(loggingReceptor());
		Runnable push = cell.push(c(0.0)).get();
		IntStream.range(0, 100).forEach(i -> push.run());
		// TODO  Add assertions
	}

	@Test
	public void withOutput() {
		CellList cells = w(new DefaultKeyboardTuning().getTone(WesternChromatic.G3))
				.o(i -> new File("results/sine-wave-cell-test.wav"));

		TemporalRunner runner = new TemporalRunner(cells, DURATION_FRAMES);
		runner.get().run();
		cells.reset();
	}

	@Test
	public void csv() {
		CellList cells = w(new Frequency(1.0)).csv(i -> new File("results/sine-wave-cell-test.csv"));

		TemporalRunner runner = new TemporalRunner(cells, OutputLine.sampleRate);
		runner.get().run();
		cells.reset();
	}

	protected Gene<PackedCollection> identityGene() {
		return new Gene<>() {
			@Override public Factor<PackedCollection> valueAt(int index) { return new IdentityFactor<>(); }
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
		SineWaveCell cell = cell();
		loggingCellPair(cell);

		Runnable push = cell.push(null).get();
		IntStream.range(0, 100).forEach(i -> push.run());
	}

	@Test
	public void withBasicDelayCell() {
		BasicDelayCell delay = new BasicDelayCell(1);
		delay.setReceptor(loggingReceptor());

		SineWaveCell cell = cell();
		cell.setReceptor(delay);

		Runnable push = cell.push(null).get();
		Runnable tick = delay.tick().get();

		IntStream.range(0, 200).forEach(i -> {
			push.run();
			tick.run();
		});
	}
}
