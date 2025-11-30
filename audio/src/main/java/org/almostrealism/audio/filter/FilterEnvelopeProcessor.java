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

package org.almostrealism.audio.filter;

import io.almostrealism.relation.Producer;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.TimeCell;
import org.almostrealism.hardware.mem.MemoryDataCopy;

// TODO  This should implement AudioProcessor
public class FilterEnvelopeProcessor implements EnvelopeProcessor, CellFeatures, EnvelopeFeatures {
	public static double filterPeak = 20000;

	private final TimeCell clock;

	private final PackedCollection input;
	private final PackedCollection output;

	private final PackedCollection duration;
	private final PackedCollection attack;
	private final PackedCollection decay;
	private final PackedCollection sustain;
	private final PackedCollection release;

	private final Runnable process;

	public FilterEnvelopeProcessor(int sampleRate, double maxSeconds) {
		input = new PackedCollection((int) (sampleRate * maxSeconds));
		output = new PackedCollection((int) (sampleRate * maxSeconds));
		duration = new PackedCollection(1);
		attack = new PackedCollection(1);
		decay = new PackedCollection(1);
		sustain = new PackedCollection(1);
		release = new PackedCollection(1);

		EnvelopeSection env = envelope(cp(duration), cp(attack), cp(decay), cp(sustain), cp(release));

		clock = new TimeCell();
		Producer<PackedCollection> freq = frames(clock.frame(), () -> env.get().getResultant(c(filterPeak)));

		WaveData audio = new WaveData(input.traverse(1), sampleRate);
		process = cells(1, i -> audio.toCell(clock.frame(), 0))
				.addRequirement(clock)
				.f(i -> lp(freq, c(0.1)))
				.export(output).get();
	}

	public void setDuration(double duration) {
		this.duration.set(0, duration);
	}

	public void setAttack(double attack) {
		this.attack.set(0, attack);
	}

	public void setDecay(double decay) {
		this.decay.set(0, decay);
	}

	public void setSustain(double sustain) {
		this.sustain.set(0, sustain);
	}

	public void setRelease(double release) {
		this.release.set(0, release);
	}

	@Override
	public void process(PackedCollection input, PackedCollection output) {
		// TODO  This can be done without the copy to input
		// TODO  by just using a Provider that can be made to
		// TODO  refer to the provided data collection directly.
		new MemoryDataCopy("FilterEnvelopeProcessor input", input, this.input).get().run();
		process.run();
		new MemoryDataCopy("FilterEnvelopeProcessor output", this.output.range(shape(output.getMemLength())), output).get().run();
	}
}
