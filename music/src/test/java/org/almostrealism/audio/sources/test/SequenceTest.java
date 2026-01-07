/*
 * Copyright 2022 Michael Murray
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

package org.almostrealism.audio.sources.test;

import io.almostrealism.relation.Producer;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.CellList;
import org.almostrealism.audio.DynamicAudioCell;
import org.almostrealism.audio.WavFile;
import org.almostrealism.audio.WaveOutput;
import org.almostrealism.audio.computations.DefaultEnvelopeComputation;
import org.almostrealism.audio.data.ParameterFunctionSequence;
import org.almostrealism.audio.data.ParameterSet;
import org.almostrealism.audio.data.PolymorphicAudioData;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.sequence.ValueSequenceCell;
import org.almostrealism.audio.sequence.ValueSequencePush;
import org.almostrealism.audio.sequence.ValueSequenceTick;
import org.almostrealism.audio.sources.SineWaveCell;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Receptor;
import org.almostrealism.hardware.AcceleratedOperation;
import org.almostrealism.heredity.ScaleFactor;
import org.almostrealism.time.Frequency;
import org.almostrealism.time.TemporalRunner;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.stream.IntStream;


public class SequenceTest implements CellFeatures, TestFeatures {
	@Test
	public void valueSequencePush() {
		PolymorphicAudioData data = new PolymorphicAudioData();
		PackedCollection out = new PackedCollection(1);
		ValueSequencePush push = new ValueSequencePush(data, c(4), out, c(1.0), c(2.0));
		data.setWavePosition(3);

		AcceleratedOperation op = (AcceleratedOperation) push.get();
		op.run();
		assertEquals(2.0, out);
	}

	@Test
	public void valueSequenceTick() {
		PolymorphicAudioData data = new PolymorphicAudioData();
		ValueSequenceTick tick = new ValueSequenceTick(data, c(4), c(1.0), c(2.0));
		data.setWaveLength(1.0);

		AcceleratedOperation op = (AcceleratedOperation) tick.get();
		op.run();
		assertEquals(1.0, data.wavePosition());
	}

	@Test
	public void valueSequenceCell() {
		ValueSequenceCell cell = new ValueSequenceCell(i -> c(i + 1), c(0.1), 2);
		cell.setReceptor(loggingReceptor());

		cell.setup().get().run();
		Runnable push = cell.push(c(0.0)).get();
		Runnable tick = cell.tick().get();

		IntStream.range(0, OutputLine.sampleRate / 10).forEach(i -> {
			push.run();
			tick.run();
		});
	}

	@Test
	public void valueSequenceCsv() {
		CellList cells = seq(i -> c(i + 1), c(0.1), 2).csv(i -> new File("results/value-sequence-test.csv"));

		TemporalRunner runner = new TemporalRunner(cells, OutputLine.sampleRate / 10);
		runner.get().run();
		cells.reset();
	}

	@Test
	public void valueSequenceAssign() {
		PackedCollection out = new PackedCollection(1);

		CellList cells = seq(i -> c(i + 1), c(0.1), 2);
		cells.get(0).setReceptor(a(p(out)));

		TemporalRunner runner = new TemporalRunner(cells, OutputLine.sampleRate / 10);
		runner.get().run();
		assertEquals(2.0, out);
		cells.reset();
	}

	protected SineWaveCell cell(int freq) {
		SineWaveCell cell = new SineWaveCell();
		cell.setFreq(freq);
		cell.setNoteLength(6000);
		cell.setAmplitude(0.4);
		cell.setEnvelope(DefaultEnvelopeComputation::new);
		return cell;
	}

	@Test
	public void valueSequenceWithDynamicCell() {
		SineWaveCell cell1 = cell(196);
		SineWaveCell cell2 = cell(261);

		PackedCollection out = new PackedCollection(1);

		ValueSequenceCell seq = (ValueSequenceCell) seq(i -> c(0.25 + i * 0.5), c(2), 2).get(0);
		seq.setReceptor(a(p(out)));

		CellList cells = new CellList();
		cells.addRoot(seq);
		cells = new CellList(cells);
		cells.addRoot(new DynamicAudioCell(c(1).multiply(p(out)), Arrays.asList(data -> cell1, data -> cell2)));
		cells = cells.o(i -> new File("results/seq-dynamic-test.wav"));

		TemporalRunner runner = new TemporalRunner(cells, 4 * OutputLine.sampleRate);
		runner.get().run();
		cells.reset();

		System.out.println(out);
	}

	@Test
	public void notes() {
		CellList cells = w(new Frequency(196), new Frequency(196));
		((SineWaveCell) cells.get(0)).setNoteLength(2000);
		((SineWaveCell) cells.get(1)).setNoteLength(2000);

		cells = cells.gr(2000, 8,
				i -> i % 2 == 0 ? 0 : 1)
				.o(i -> new File("results/notes-seq-test.wav"));
		cells.sec(2).get().run();
	}

	@Test
	public void samples() {
		int count = 32;

		CellList cells =
				silence().and(w(0, c(bpm(128).l(0.5)), c(bpm(128).l(1)), "Library/GT_HAT_31.wav"))
						.gr(bpm(128).l(count), count * 2, i -> i % 2 == 0 ? 0 : 1)
				.f(i -> i == 0 ? new ScaleFactor(0.5) : new ScaleFactor(0.1))
				.sum().o(i -> new File("results/sample-seq-test.wav"));

		cells.sec(bpm(128).l(count)).get().run();
	}

	@Test
	public void stems() {
		int count = 212;

		Producer<PackedCollection> one = c(1.0);

		CellList cells = cells(
//				silence().and(w(one, "Library/BD 909 Color 06.wav"))
//						.gr(10, count, i -> 0),
				silence().and(w(0, one, "Library/Snare Perc DD.wav"))
						.gr(10, count, i -> 0)
				)
				 .o(i -> new File("results/stems-test-" + i + ".wav"));

		Runnable r = cells.sec(10).get();
		r.run();
	}

	@Test
	public void mix() {
		int count = 32;

		CellList cells = cells(
					silence().and(w(0, c(bpm(128).l(1)), "Library/BD 909 Color 06.wav"))
						.gr(bpm(128).l(count), count, i -> 1),
					silence().and(w(0, c(bpm(128).l(1)), "Library/Snare Perc DD.wav"))
						.gr(bpm(128).l(count), count, i -> i % 2 == 0 ? 0 : 1),
					silence().and(w(0, c(bpm(128).l(0.5)), c(bpm(128).l(1)), "Library/GT_HAT_31.wav"))
						.gr(bpm(128).l(count), count * 2, i -> i % 2 == 0 ? 0 : 1))
				.f(i -> i == 0 ? new ScaleFactor(0.5) : new ScaleFactor(0.1))
				.sum().o(i -> new File("results/mix-test.wav"));

		cells.sec(bpm(128).l(count)).get().run();
	}

	@Test
	public void parameterizedMix() {
		int count = 32;

		ParameterSet params = new ParameterSet();
		ParameterFunctionSequence hatSeq = ParameterFunctionSequence.random(64);
		ParameterFunctionSequence snareSeq = ParameterFunctionSequence.random(32);

		CellList cells = cells(
				silence().and(w(0, c(bpm(128).l(1)), "Library/BD 909 Color 06.wav"))
						.gr(bpm(128).l(count), count, i -> 1),
				silence().and(w(0, c(bpm(128).l(1)), "Library/Snare Perc DD.wav"))
						.gr(bpm(128).l(count), count, i -> (int) (Math.max(0, snareSeq.apply(i).apply(params)) * 2)),
				silence().and(w(0, c(bpm(128).l(0.5)), c(bpm(128).l(1)), "Library/GT_HAT_31.wav"))
						.gr(bpm(128).l(count), count * 2, i -> (int) (Math.max(0, hatSeq.apply(i).apply(params)) * 2)))
				.f(i -> i == 0 ? new ScaleFactor(0.5) : new ScaleFactor(0.1))
				.sum().o(i -> new File("results/param-mix-test.wav"));

		cells.sec(bpm(128).l(count)).get().run();
	}

	@Test
	public void mixExport() throws IOException {
		WaveOutput.enableVerbose = true;

		int count = 32;

		WaveOutput output = new WaveOutput();

		CellList cells = cells(
				silence().and(w(0, c(bpm(128).l(1)), "Library/BD 909 Color 06.wav"))
						.gr(bpm(128).l(count), count, i -> 1),
				silence().and(w(0, c(bpm(128).l(1)), "Library/Snare Perc DD.wav"))
						.gr(bpm(128).l(count), count, i -> i % 2 == 0 ? 0 : 1),
				silence().and(w(0, c(bpm(128).l(0.5)), c(bpm(128).l(1)), "Library/GT_HAT_31.wav"))
						.gr(bpm(128).l(count), count * 2, i -> i % 2 == 0 ? 0 : 1))
				.f(i -> i == 0 ? new ScaleFactor(0.5) : new ScaleFactor(0.1))
				.sum().map(i -> output.getWriterCell(0));

		cells.sec(10).get().run();

		PackedCollection export = new PackedCollection(WaveOutput.defaultTimelineFrames).traverse(1);
		output.export(0, export).get().run();

		WavFile f = WavFile.newWavFile(new File("results/mix-export-test.wav"), 1,
				10L * OutputLine.sampleRate, 24, OutputLine.sampleRate);

		for (int i = 0; i < 10 * OutputLine.sampleRate; i++) {
			f.writeFrames(new double[] { export.toDouble(i) }, 1);
		}

		f.close();
	}

	protected Receptor<PackedCollection> loggingReceptor() {
		return protein -> () -> () -> System.out.println(protein.get().evaluate());
	}

}
