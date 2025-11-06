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

package org.almostrealism.render;

import org.almostrealism.algebra.Pair;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.RGB;
import org.almostrealism.hardware.MemoryBank;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;

import java.util.stream.IntStream;

public class SuperSampler implements Producer<RGB> {
	protected Producer<RGB> samples[][];
	private double scale;

	public SuperSampler(Producer<RGB> samples[][]) {
		this.samples = samples;
		scale = 1.0 / (this.samples.length * this.samples[0].length);
	}

	@Override
	public Evaluable<RGB> get() {
		Evaluable<RGB> ev[][] = new Evaluable[samples.length][samples[0].length];
		IntStream.range(0, samples.length).forEach(i ->
			IntStream.range(0, samples[i].length).forEach(j -> {
				ev[i][j] = samples[i][j].get();
			}));

		return new Evaluable<>() {

			@Override
			public MemoryBank<RGB> createDestination(int size) {
				return RGB.bank(size);
			}

			@Override
			public RGB evaluate(Object... args) {
				Pair pos = (Pair) args[0];

				RGB c = new RGB(0.0, 0.0, 0.0);

				for (int i = 0; i < ev.length; i++) {
					j:
					for (int j = 0; j < ev[i].length; j++) {
						double r = pos.getX() + ((double) i / (double) ev.length);
						double q = pos.getY() + ((double) j / (double) ev[i].length);

						RGB rgb = ev[i][j].evaluate(new Pair(r, q));
						if (rgb == null) continue j;

						rgb.multiplyBy(scale);
						c.addTo(rgb);
					}
				}

				return c;
			}

			@Override
			public Evaluable into(Object destination) {
				return args -> {
					int w = ev.length;
					int h = ev[0].length;

					PackedCollection<Pair<?>> allSamples = Pair.bank(((MemoryBank) args[0]).getCount());
					PackedCollection<RGB> out[][] = new PackedCollection[w][h];

					System.out.println("SuperSampler: Evaluating sample kernels...");
					for (int i = 0; i < ev.length; i++) {
						for (int j = 0; j < ev[i].length; j++) {
							double pos[] = ((MemoryBank<?>) args[0]).toArray(0, allSamples.getMemLength());
							double pairs[] = new double[allSamples.getMemLength()];

							for (int k = 0; k < ((MemoryBank) args[0]).getCount(); k++) {
								pairs[2 * k] = pos[2 * k] + ((double) i / (double) ev.length);
								pairs[2 * k + 1] = pos[2 * k + 1] + ((double) j / (double) ev[i].length);
							}

							allSamples.setMem(pairs);

							out[i][j] = RGB.bank(allSamples.getCount());
							ev[i][j].into(out[i][j]).evaluate(allSamples);
						}
					}

					System.out.println("SuperSampler: Combining samples...");
					for (int k = 0; k < ((MemoryBank) destination).getCount(); k++) {
						for (int i = 0; i < ev.length; i++) {
							j:
							for (int j = 0; j < ev[i].length; j++) {
								((RGB) ((MemoryBank) destination).get(k)).addTo(out[i][j].get(k).multiply(scale));
							}
						}
					}

					return destination;
				};
			}
		};
	}
}
