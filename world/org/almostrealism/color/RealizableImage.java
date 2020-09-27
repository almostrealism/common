/*
 * Copyright 2020 Michael Murray
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

package org.almostrealism.color;

import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.PairBank;
import org.almostrealism.hardware.KernelizedProducer;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.util.Producer;

public class RealizableImage implements Producer<RGB[][]> {
	private KernelizedProducer<RGB> source;
	private Pair dim;

	public RealizableImage(KernelizedProducer<RGB> source, Pair dimensions) {
		this.source = source;
		this.dim = dimensions;
	}

	public KernelizedProducer<RGB> getSource() { return source; }

	public Pair getDimensions() { return dim; }

	@Override
	public RGB[][] evaluate() {
		return evaluate(new Object[] { new Pair(0, 0) });
	}

	@Override
	public RGB[][] evaluate(Object[] args) {
		System.out.println("RealizableImage: Evaluating source kernel...");

		Pair xy = (Pair) args[0];
		int x = (int) xy.getX();
		int y = (int) xy.getY();
		int w = (int) dim.getX();
		int h = (int) dim.getY();
		int size = w * h;
		PairBank input = new PairBank(size);
		RGBBank output = new RGBBank(size);

		for (int i = 0; i < w; i++) {
			for (int j = 0; j < h; j++) {
				input.set(j * w + i, x + i, h - 1 - j - y);
			}
		}

		source.kernelEvaluate(output, new MemoryBank[] { input });

		RGB image[][] = new RGB[w][h];

		for (int i = 0; i < w; i++) {
			for (int j = 0; j < h; j++) {
				image[i][j] = output.get(j * w + i);
			}
		}

		return image;
	}

	@Override
	public void compact() { source.compact(); }
}
