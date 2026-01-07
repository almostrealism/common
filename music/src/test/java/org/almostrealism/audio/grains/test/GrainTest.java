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

package org.almostrealism.audio.grains.test;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.WaveOutput;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.audio.data.WaveDataProviderList;
import org.almostrealism.audio.filter.EnvelopeFeatures;
import org.almostrealism.audio.filter.EnvelopeSection;
import org.almostrealism.audio.grains.Grain;
import org.almostrealism.audio.grains.GrainSet;
import org.almostrealism.audio.grains.GranularSynthesizer;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.time.Frequency;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.stream.IntStream;

public class GrainTest implements CellFeatures, EnvelopeFeatures, TestFeatures {

	@Test
	public void grainsTimeSeries() {
		WaveOutput source = new WaveOutput();
		w(0, new File("Library/organ.wav")).map(i -> source.getWriterCell(0)).sec(1.0, false).get().run();

		Grain grain = new Grain();
		grain.setStart(0.2);
		grain.setDuration(0.015);
		grain.setRate(2.0);

		TraversalPolicy grainShape = new TraversalPolicy(3);
		Producer in = v(shape(1), 0);
		Producer<PackedCollection> g = v(shape(3).traverseEach(), 1);

		CollectionProducer start = c(g, 0);
		CollectionProducer duration = c(g, 1);
		CollectionProducer rate = c(g, 2);

		int frames = 240 * OutputLine.sampleRate;

//		Producer<Scalar> pos = start.add(mod(multiply(rate, in), duration))
//									.multiply(scalar(OutputLine.sampleRate));
//		Producer pos = _mod(in, c(0.5)).multiply(c(OutputLine.sampleRate));
		Producer cursor = integers(0, frames);

		PackedCollection result = new PackedCollection(shape(frames), 1);
		System.out.println("GrainTest: Evaluating timeline kernel...");
		verboseLog(() -> {
			c(source.getChannelData(0), cursor).get().into(result).evaluate();
		});
		System.out.println("GrainTest: Timeline kernel evaluated");

		System.out.println("GrainTest: Rendering grains...");
		w(0, new WaveData(result, OutputLine.sampleRate))
				.o(i -> new File("results/grain-timeseries-test.wav"))
				.sec(5).get().run();
		System.out.println("GrainTest: Done");
	}

	@Test
	public void grains() throws IOException {
		WaveData wav = WaveData.load(new File("Library/organ.wav"));

		Grain grain = new Grain();
		grain.setStart(0.2);
		grain.setDuration(0.015);
		grain.setRate(0.3);

		PackedCollection w = new PackedCollection(1);
		w.setMem(0.75);

		Producer<PackedCollection> g = v(shape(3), 1);
		CollectionProducer start = c(g, 0).multiply(c(OutputLine.sampleRate));
		CollectionProducer duration = c(g, 1).multiply(c(OutputLine.sampleRate));
		CollectionProducer rate = c(g, 2);
		CollectionProducer wavelength = multiply(p(w), c(OutputLine.sampleRate));

		PackedCollection input = wav.getChannelData(0);
		int frames = 5 * OutputLine.sampleRate;

		Producer<PackedCollection> series = integers(0, frames);
		Producer<PackedCollection> max = c(wav.getFrameCount()).subtract(start);
		Producer<PackedCollection> pos  = start.add(mod(mod(series, duration), max));

		CollectionProducer generate = interpolate(v(1, 0), pos, rate);
		generate = generate.multiply(sinw(series, wavelength, c(1.0)));

		System.out.println("GrainTest: Evaluating kernel...");
		Evaluable<PackedCollection> ev = generate.get();
		PackedCollection result = ev.into(new PackedCollection(shape(frames), 1))
										.evaluate(input.traverse(0), grain);
		System.out.println("GrainTest: Kernel evaluated");

		System.out.println("GrainTest: Rendering grains...");
		w(0, new WaveData(result, OutputLine.sampleRate))
				.o(i -> new File("results/grain-test.wav"))
				.sec(5).get().run();
		System.out.println("GrainTest: Done");
	}

	@Test
	public void grainProcessor() throws IOException {
		WaveData wav = WaveData.load(new File("Library/organ.wav"));
		PackedCollection input = wav.getChannelData(0);

		Evaluable<PackedCollection> processor =
				sampling(OutputLine.sampleRate, 5.0, () -> grains(
					v(1, 0),
					v(shape(3), 1),
					v(shape(3), 2),
					v(shape(3), 3),
					v(1, 4))).get();

		int tot = 5 * OutputLine.sampleRate;

		w(0, IntStream.range(0, 10).mapToObj(i -> {
			Grain grain = new Grain();
			grain.setStart(Math.random() * 0.3 + 0.2);
			grain.setDuration(Math.random() * 0.015);
			grain.setRate(Math.random() * 0.5);

			PackedCollection w = new PackedCollection(1);
			w.setMem(Math.random() * 2 + 0.2);

			PackedCollection p = new PackedCollection(1);
			p.setMem(Math.random() - 0.5);

			PackedCollection a = new PackedCollection(1);
			a.setMem(1.0);

			return new WaveData(processor.into(new PackedCollection(shape(tot), 1))
					.evaluate(input.traverse(0), grain, w, p, a), OutputLine.sampleRate);
		}).toArray(WaveData[]::new))
				.sum()
				.o(i -> new File("results/grain-processor-test.wav"))
				.sec(5).get().run();
	}

	@Test
	public void grainProcessorEnvelope() throws IOException {
		WaveData wav = WaveData.load(new File("Library/organ.wav"));
		PackedCollection input = wav.getChannelData(0);

		double attack = 1.0;

		EnvelopeSection env = envelope(attack(c(attack)));

		Evaluable<PackedCollection> processor =
						sampling(OutputLine.sampleRate, 5.0,
								() -> env.get().getResultant(grains(
									v(1, 0),
									v(shape(3), 1),
									v(shape(3), 2),
									v(shape(3), 3),
									v(1, 4)))).get();

		int tot = 5 * OutputLine.sampleRate;

		w(0, IntStream.range(0, 10).mapToObj(i -> {
			Grain grain = new Grain();
			grain.setStart(Math.random() * 0.3 + 0.2);
			grain.setDuration(Math.random() * 0.015);
			grain.setRate(Math.random() * 0.5);

			PackedCollection w = new PackedCollection(1);
			w.setMem(Math.random() * 2 + 0.2);

			PackedCollection p = new PackedCollection(1);
			p.setMem(Math.random() - 0.5);

			PackedCollection a = new PackedCollection(1);
			a.setMem(0.2);

			return new WaveData(processor.into(new PackedCollection(shape(tot), 1))
					.evaluate(input.traverse(0), grain, w, p, a), OutputLine.sampleRate);
		}).toArray(WaveData[]::new))
				.sum()
				.map(fc(i -> sf(10.0)))
				.o(i -> new File("results/grain-processor-envelope-test.wav"))
				.sec(5).get().run();
	}

	@Test
	public void granularSynth() {
		GranularSynthesizer synth = new GranularSynthesizer(OutputLine.sampleRate);
		GrainSet set = synth.addFile("Library/organ.wav");
		set.addGrain(new Grain(0.2, 0.015, 2.0));

		WaveDataProviderList providers = synth.create(scalar(0.0), scalar(0.0), scalar(0.0), List.of(new Frequency(1.0)));
		providers.setup().get().run();
		providers.getProviders().get(0).get().save(new File("results/granular-synth-test.wav"));
	}
}
