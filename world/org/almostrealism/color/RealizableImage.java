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
import org.almostrealism.hardware.KernelizedOperation;
import org.almostrealism.hardware.KernelizedEvaluable;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.util.Evaluable;

public class RealizableImage implements Evaluable<RGB[][]> {
	private KernelizedEvaluable<RGB> source;
	private Pair dim;

	public RealizableImage(KernelizedEvaluable<RGB> source, Pair dimensions) {
		this.source = source;
		this.dim = dimensions;
	}

	public KernelizedEvaluable<RGB> getSource() { return source; }

	public Pair getDimensions() { return dim; }

	@Override
	public RGB[][] evaluate() {
		return evaluate(new Object[] { new Pair(0, 0) });
	}

	@Override
	public RGB[][] evaluate(Object[] args) {
		if (KernelizedOperation.enableKernelLog) System.out.println("RealizableImage: Evaluating source kernel...");

		Pair xy = (args != null && args.length > 0) ? (Pair) args[0] : new Pair(0, 0);
		int x = (int) xy.getX();
		int y = (int) xy.getY();
		int w = (int) dim.getX();
		int h = (int) dim.getY();
		int size = w * h;
		PairBank input = generateKernelInput(x, y, w, h);
		RGBBank output = new RGBBank(size);

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

	public static PairBank generateKernelInput(int x, int y, int width, int height) {
		int size = width * height;
		PairBank input = new PairBank(size);

		for (int i = 0; i < width; i++) {
			for (int j = 0; j < height; j++) {
				input.set(j * width + i, x + i, height - 1 - j - y);
			}
		}

		return input;
	}
}
