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

import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.CellList;
import org.almostrealism.audio.filter.DelayNetwork;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.graph.TimeCell;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

import java.io.File;
import java.util.function.Supplier;

public class ReverbCellTests implements CellFeatures, TestFeatures {
	private final int sampleRate = OutputLine.sampleRate;

	@Test
	public void reverb1() {
		CellList c = w(0, "Library/Snare Perc DD.wav")
				.map(fc(i -> new DelayNetwork(sampleRate, false)))
				.o(i -> new File("results/reverb1.wav"));
		Supplier<Runnable> r = c.sec(12);
		r.get().run();
	}

	@Test
	public void reverbAutomation() {
		TimeCell clock = new TimeCell();

		CellList c = w(0, c(0.35), "Library/organ.wav")
				.map(fc(i -> in -> multiply(in, c(1.0).add(sin(clock.time(sampleRate))))))
				.map(fc(i -> new DelayNetwork(sampleRate, false)))
				.addRequirement(clock)
				.o(i -> new File("results/reverb-auto.wav"));
		Supplier<Runnable> r = c.sec(12);
		r.get().run();
	}

	@Test
	public void delayReverb() {
		CellList c = w(0, "Library/Snare Perc DD.wav")
				.d(i -> c(2.0))
				.map(fc(i -> new DelayNetwork(sampleRate, false)))
				.o(i -> new File("results/delay-reverb.wav"));
		Supplier<Runnable> r = c.sec(12);
		r.get().run();
	}
}
