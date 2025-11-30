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

import io.almostrealism.relation.Factor;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.DynamicAudioCell;
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
import org.almostrealism.hardware.AcceleratedComputationOperation;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.heredity.Gene;
import org.almostrealism.heredity.IdentityFactor;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.IntStream;

public class DynamicAudioCellTest implements CellFeatures, TestFeatures {
	private static final int DURATION_FRAMES = 10 * OutputLine.sampleRate;

	protected Receptor<PackedCollection> loggingReceptor() {
		return protein -> () -> () -> log(protein.get().evaluate());
	}

	protected Cell<PackedCollection> loggingCell() { return new ReceptorCell<>(loggingReceptor()); }

	protected SineWaveCell choice(PackedCollection destination) {
		SineWaveCell c = new SineWaveCell();
		c.setFreq(0.5);
		c.setAmplitude(1.0);
		c.setReceptor(a(p(destination)));
		return c;
	}

	protected DynamicAudioCell cell(PackedCollection choice) {
		DefaultKeyboardTuning tuning = new DefaultKeyboardTuning();

		Function<PolymorphicAudioData, CollectionTemporalCellAdapter> cell1 = data -> {
			SineWaveCell c = new SineWaveCell(data);
			c.setFreq(tuning.getTone(WesternChromatic.G3).asHertz());
			c.setNoteLength(4000);
			c.setAmplitude(0.1);
			c.setEnvelope(DefaultEnvelopeComputation::new);
			return c;
		};

		Function<PolymorphicAudioData, CollectionTemporalCellAdapter> cell2 = data -> {
			SineWaveCell c = new SineWaveCell(data);
			c.setFreq(tuning.getTone(WesternChromatic.C3).asHertz());
			c.setNoteLength(4000);
			c.setAmplitude(0.1);
			c.setEnvelope(DefaultEnvelopeComputation::new);
			return c;
		};

		return new DynamicAudioCell(c(1).add(p(choice)).divide(c(2.0)), Arrays.asList(cell1, cell2));
	}

	@Test
	public void sineWave() {
		DynamicAudioCell cell = cell(new PackedCollection(1));
		cell.setReceptor(loggingReceptor());
		Runnable push = cell.push(c(0.0)).get();
		IntStream.range(0, 100).forEach(i -> push.run());
		// TODO  Add assertions
	}

	@Test
	public void withOutput() {
		WaveOutput output = new WaveOutput(new File("results/dynamic-cell-test.wav"));

		PackedCollection choice = new PackedCollection(1);
		SineWaveCell chooser = choice(choice);
		DynamicAudioCell cell = cell(choice);
		cell.setReceptor(output.getWriter(0));

		chooser.setup().get().run();
		cell.setup().get().run();

		OperationList op = new OperationList("Chooser and Cells");
		op.add(chooser.push(c(0.0)));
		op.add(chooser.tick());
		op.add(cell.push(c(0.0)));
		op.add(cell.tick());

		AcceleratedComputationOperation o = (AcceleratedComputationOperation) lp(op, DURATION_FRAMES).get();
		o.run();

		System.out.println("DynamicAudioCellTest: Writing WAV...");
		output.write().get().run();
		System.out.println("DynamicAudioCellTest: Done");
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
		DynamicAudioCell cell = cell(new PackedCollection(1));
		loggingCellPair(cell);

		Runnable push = cell.push(null).get();
		IntStream.range(0, 100).forEach(i -> push.run());
	}

	@Test
	public void withBasicDelayCell() {
		BasicDelayCell delay = new BasicDelayCell(1);
		delay.setReceptor(loggingReceptor());

		DynamicAudioCell cell = cell(new PackedCollection(1));
		cell.setReceptor(delay);

		Runnable push = cell.push(null).get();
		Runnable tick = delay.tick().get();

		IntStream.range(0, 200).forEach(i -> {
			push.run();
			tick.run();
		});
	}
}
