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

/**
 * {@link SuperSampler} implements anti-aliasing by averaging multiple color samples
 * within a single pixel. It acts as a {@link Producer} that combines multiple
 * sub-pixel color producers into a single averaged result.
 *
 * <p>Supersampling works by casting multiple rays through slightly different positions
 * within each pixel, then averaging the resulting colors. This smooths out aliasing
 * artifacts like jagged edges on diagonal lines and high-frequency texture patterns.</p>
 *
 * <h2>Sample Grid</h2>
 * <p>Samples are organized in a 2D grid within the pixel. For example, 2x2 supersampling
 * divides each pixel into 4 sub-regions:</p>
 * <pre>
 * Pixel bounds: (x, y) to (x+1, y+1)
 *
 * Sample positions:
 *   (x + 0.00, y + 0.00)  (x + 0.50, y + 0.00)
 *   (x + 0.00, y + 0.50)  (x + 0.50, y + 0.50)
 * </pre>
 *
 * <h2>Evaluation</h2>
 * <p>When {@link #get()} is called, the SuperSampler creates an {@link Evaluable} that:</p>
 * <ol>
 *   <li>Evaluates each sample producer at the sub-pixel position</li>
 *   <li>Multiplies each result by the scale factor (1/totalSamples)</li>
 *   <li>Sums all scaled samples to produce the final averaged color</li>
 * </ol>
 *
 * <h2>Batch Evaluation</h2>
 * <p>The {@link Evaluable#into(Object)} method supports efficient batch evaluation for
 * multiple pixels at once, computing all samples and combining them in memory banks.</p>
 *
 * @see Pixel
 * @see org.almostrealism.raytrace.RenderParameters
 * @author Michael Murray
 */
public class SuperSampler implements Producer<PackedCollection> {

	/**
	 * The 2D grid of sample color producers. First index is horizontal (x),
	 * second index is vertical (y).
	 */
	protected Producer<PackedCollection> samples[][];

	/**
	 * Scale factor applied to each sample when averaging.
	 * Equals 1.0 / (samples.length * samples[0].length).
	 */
	private double scale;

	/**
	 * Creates a new SuperSampler with the given sample producer grid.
	 *
	 * @param samples A 2D array of color producers, one for each sub-pixel sample position.
	 *                The array dimensions determine the supersampling factor.
	 */
	public SuperSampler(Producer<PackedCollection> samples[][]) {
		this.samples = samples;
		scale = 1.0 / (this.samples.length * this.samples[0].length);
	}

	/**
	 * Creates an {@link Evaluable} that computes the averaged color from all samples.
	 *
	 * <p>The returned Evaluable supports both single-pixel evaluation (via {@link Evaluable#evaluate})
	 * and batch evaluation (via {@link Evaluable#into}). In batch mode, all samples are computed
	 * for all pixels efficiently before being combined.</p>
	 *
	 * @return An Evaluable that produces the averaged RGB color when evaluated with a pixel position
	 */
	@Override
	public Evaluable<PackedCollection> get() {
		Evaluable<PackedCollection> ev[][] = new Evaluable[samples.length][samples[0].length];
		IntStream.range(0, samples.length).forEach(i ->
			IntStream.range(0, samples[i].length).forEach(j -> {
				ev[i][j] = samples[i][j].get();
			}));

		return new Evaluable<>() {

			@Override
			public PackedCollection evaluate(Object... args) {
				Pair pos = (Pair) args[0];

				RGB c = new RGB(0.0, 0.0, 0.0);

				for (int i = 0; i < ev.length; i++) {
					j:
					for (int j = 0; j < ev[i].length; j++) {
						double r = pos.getX() + ((double) i / (double) ev.length);
						double q = pos.getY() + ((double) j / (double) ev[i].length);

						PackedCollection result = ev[i][j].evaluate(new Pair(r, q));
						if (result == null) continue j;

						RGB rgb = result instanceof RGB ? (RGB) result : new RGB(result.toDouble(0), result.toDouble(1), result.toDouble(2));
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

					PackedCollection allSamples = Pair.bank(((MemoryBank) args[0]).getCount());
					PackedCollection out[][] = new PackedCollection[w][h];

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
								PackedCollection sample = out[i][j].range(RGB.shape(), k * 3);
								RGB sampleRgb = new RGB(sample.toDouble(0) * scale, sample.toDouble(1) * scale, sample.toDouble(2) * scale);
								((RGB) ((MemoryBank) destination).get(k)).addTo(sampleRgb);
							}
						}
					}

					return destination;
				};
			}
		};
	}
}
