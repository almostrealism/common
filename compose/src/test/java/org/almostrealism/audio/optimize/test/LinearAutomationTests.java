/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.audio.optimize.test;

import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.CellList;
import org.almostrealism.audio.SamplingFeatures;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.optimize.OptimizeFactorFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.TimeCell;
import org.junit.Test;

import java.io.File;

public class LinearAutomationTests implements CellFeatures, SamplingFeatures, OptimizeFactorFeatures {
	@Test
	public void riseFall() {
		int sr = OutputLine.sampleRate;

		TimeCell clock = new TimeCell();

		double direction = 0.9;
		double magnitude = 0.05;
		double position = 0.0;
		double exponent = 1.0;

		Producer<PackedCollection> d = c(direction);
		Producer<PackedCollection> m = c(magnitude);
		Producer<PackedCollection> s = c(position);
		Producer<PackedCollection> e = c(exponent);

		int seconds = 30;

		Producer<PackedCollection> freq = riseFall(0, 18000, 0.0,
														d, m, s, e, clock.time(sr), c(seconds));

		CellList cells = w(0, c(0.0), c(1.0), "Library/Snare Perc DD.wav")
				.addRequirements(clock)
				.map(fc(i -> sf(0.1)))
				.map(fc(i -> lp(freq, c(0.2))))
				.o(i -> new File("results/filter-rise-fall.wav"));

		cells.sec(seconds).get().run();
	}

	@Test
	public void riseFallAutomationClock() {
		int sr = OutputLine.sampleRate;

		TimeCell clock = new TimeCell();

		double direction = 0.9;
		double magnitude = 0.5;
		double position = 0.0;
		double exponent = 1.0;

		Producer<PackedCollection> d = c(direction);
		Producer<PackedCollection> m = c(magnitude);
		Producer<PackedCollection> p = c(position);
		Producer<PackedCollection> e = c(exponent);

		int seconds = 30;

		Producer<PackedCollection> freq = riseFall(0, 1.0, 0.0,
				d, m, p, e, clock.time(sr), c(seconds));

		CellList cells = w(0, c(0.0), c(1.0), "Library/Snare Perc DD.wav")
				.addRequirements(clock)
				.map(fc(i -> sf(0.1)))
				.map(fc(i -> in -> freq))
				.o(i -> new File("results/clock-rise-fall.wav"));

		cells.sec(seconds).get().run();
	}

	@Test
	public void riseFallAutomation() {
		int sr = OutputLine.sampleRate;

		double direction = 0.9;
		double magnitude = 0.5;
		double position = 0.0;
		double exponent = 1.0;

		Producer<PackedCollection> d = c(direction);
		Producer<PackedCollection> m = c(magnitude);
		Producer<PackedCollection> p = c(position);
		Producer<PackedCollection> e = c(exponent);

		int seconds = 30;

		Factor<PackedCollection> freq = in ->
				riseFall(0, 1.0, 0.0,
						d, m, p, e, time(), c(seconds));

		PackedCollection data = new PackedCollection(seconds * sr);
		new WaveData(data.traverseEach(), sr).sample(0, freq).save(new File("results/rise-fall-test.wav"));
	}
}
