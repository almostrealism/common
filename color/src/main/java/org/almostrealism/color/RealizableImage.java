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

package org.almostrealism.color;

import org.almostrealism.algebra.Pair;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.HardwareOperator;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Evaluable;

import java.util.function.Function;

public class RealizableImage implements Producer<RGB[][]> {
	private Producer<RGB> source;
	private Function<Pair, RGB> func;
	private Pair dim;

	public RealizableImage(Producer<RGB> source, Pair dimensions) {
		this.source = source;
		this.dim = dimensions;
	}

	public RealizableImage(Function<Pair, RGB> source, Pair dimensions) {
		this.func = source;
		this.dim = dimensions;
	}

	public Producer<RGB> getSource() { return source; }

	public Pair getDimensions() { return dim; }

	// TODO  This should be Evaluable<PackedCollection<?>>
	@Override
	public Evaluable<RGB[][]> get() {
		return args -> {
			if (HardwareOperator.enableKernelLog) System.out.println("RealizableImage: Evaluating source kernel...");

			if (args == null || args.length <= 0) {
				args = new Object[]{new Pair(0, 0)};
			}

			Pair xy = (args != null && args.length > 0) ? (Pair) args[0] : new Pair(0, 0);
			int x = (int) xy.getX();
			int y = (int) xy.getY();
			int w = (int) dim.getX();
			int h = (int) dim.getY();
			int size = w * h;
			PackedCollection<Pair<?>> input = generateKernelInput(x, y, w, h);
			PackedCollection<RGB> output = RGB.bank(size);

			if (source != null) {
				source.get().into(output).evaluate(input);
			} else if (func != null) {
				RGB result[] = input.stream().map(func).toArray(RGB[]::new);
				for (int i = 0; i < result.length; i++) output.set(i, result[i]);
			} else {
				throw new UnsupportedOperationException();
			}

			return processKernelOutput(w, h, output);
		};
	}

	public static PackedCollection<Pair<?>> generateKernelInput(int x, int y, int width, int height) {
		int size = width * height;
		PackedCollection<Pair<?>> input = Pair.bank(size);

		for (int i = 0; i < width; i++) {
			for (int j = 0; j < height; j++) {
				input.set(j * width + i, x + i, height - 1 - j - y);
			}
		}

		return input;
	}

	public static RGB[][] processKernelOutput(int w, int h, PackedCollection<RGB> output) {
		RGB image[][] = new RGB[w][h];

		for (int i = 0; i < w; i++) {
			for (int j = 0; j < h; j++) {
				image[i][j] = output.get(j * w + i);
			}
		}

		return image;
	}
}
