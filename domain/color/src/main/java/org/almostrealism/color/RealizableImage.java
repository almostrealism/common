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

import io.almostrealism.compute.Process;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Pair;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.io.ConsoleFeatures;

import java.util.function.Function;

/**
 * A {@link Producer} that renders a 2D array of {@link RGB} pixels by evaluating a color
 * source over a grid of pixel coordinates.
 *
 * <p>Two modes are supported:</p>
 * <ul>
 *   <li><b>Producer mode:</b> a {@link Producer} that accepts a {@link PackedCollection} of
 *       pixel coordinates and produces a flat RGB bank, which is then reshaped into a 2D array.</li>
 *   <li><b>Function mode:</b> a {@link Function} that maps each pixel coordinate pair directly
 *       to an {@link RGB} value.</li>
 * </ul>
 *
 * <p>The {@link #get()} method generates kernel input (a bank of {@link org.almostrealism.algebra.Pair}
 * coordinates) for all pixels in the image, evaluates the source, and returns the result as
 * a 2D {@code RGB[width][height]} array.</p>
 *
 * @see RGB
 * @author Michael Murray
 */
public class RealizableImage implements Producer<RGB[][]>, ConsoleFeatures {
	/** The producer-based color source evaluated over a grid of pixel coordinates. */
	private Producer<PackedCollection> source;

	/** The function-based color source mapping pixel coordinates to RGB values. */
	private Function<Pair, RGB> func;

	/** The width and height of the image as a {@link Pair}. */
	private Pair dim;

	/**
	 * Constructs a {@link RealizableImage} backed by a {@link Producer} color source.
	 *
	 * @param source     the producer that evaluates colors over packed pixel coordinates
	 * @param dimensions the image dimensions as a {@link Pair} (width, height)
	 */
	public RealizableImage(Producer<PackedCollection> source, Pair dimensions) {
		this.source = source;
		this.dim = dimensions;
	}

	/**
	 * Constructs a {@link RealizableImage} backed by a function that maps pixel coordinates
	 * to {@link RGB} values.
	 *
	 * @param source     a function mapping {@link Pair} pixel coordinates to {@link RGB} colors
	 * @param dimensions the image dimensions as a {@link Pair} (width, height)
	 */
	public RealizableImage(Function<Pair, RGB> source, Pair dimensions) {
		this.func = source;
		this.dim = dimensions;
	}

	/**
	 * Returns the producer-based color source, or {@code null} if the function-based mode is used.
	 *
	 * @return the color producer, or {@code null}
	 */
	public Producer<PackedCollection> getSource() { return source; }

	/**
	 * Returns the image dimensions as a {@link Pair} (width, height).
	 *
	 * @return the image dimensions
	 */
	public Pair getDimensions() { return dim; }

	/**
	 * Returns an {@link Evaluable} that renders the image by evaluating the color source
	 * over a grid of pixel coordinates.
	 *
	 * <p>An optional argument of type {@link Pair} may be passed to specify the top-left pixel
	 * offset (default {@code (0, 0)}). The result is a 2D {@code RGB[width][height]} array.</p>
	 *
	 * @return an {@link Evaluable} producing a 2D array of {@link RGB} pixels
	 */
	// TODO  This should be Evaluable<PackedCollection>
	@Override
	public Evaluable<RGB[][]> get() {
		return args -> {
			if (HardwareOperator.enableVerboseLog)
				log("Evaluating source kernel...");

			if (args == null || args.length <= 0) {
				args = new Object[]{new Pair(0, 0)};
			}

			Pair xy = (args != null && args.length > 0) ? (Pair) args[0] : new Pair(0, 0);
			int x = (int) xy.getX();
			int y = (int) xy.getY();
			int w = (int) dim.getX();
			int h = (int) dim.getY();
			int size = w * h;
			PackedCollection input = generateKernelInput(x, y, w, h);
			PackedCollection output = RGB.bank(size);

			if (source != null) {
				Process.optimized(source).get().into(output).evaluate(input);
			} else if (func != null) {
				RGB result[] = input.stream().map(p -> func.apply((Pair) p)).toArray(RGB[]::new);
				for (int i = 0; i < result.length; i++) output.set(i, result[i]);
			} else {
				throw new UnsupportedOperationException();
			}

			return processKernelOutput(w, h, output);
		};
	}

	/**
	 * Generates a flat bank of pixel coordinate pairs for the given viewport.
	 *
	 * <p>For each pixel at grid position {@code (i, j)}, the output contains a
	 * {@link Pair} with coordinates {@code (x + i, height - 1 - j - y)} (Y-flipped
	 * so that row 0 of the bank corresponds to the bottom of the viewport).</p>
	 *
	 * @param x      the left offset of the viewport in image space
	 * @param y      the top offset of the viewport in image space
	 * @param width  the number of columns
	 * @param height the number of rows
	 * @return a {@link PackedCollection} of {@code width * height} coordinate pairs
	 */
	public static PackedCollection generateKernelInput(int x, int y, int width, int height) {
		int size = width * height;
		PackedCollection input = Pair.bank(size);

		for (int i = 0; i < width; i++) {
			for (int j = 0; j < height; j++) {
				input.set(j * width + i, x + i, height - 1 - j - y);
			}
		}

		return input;
	}

	/**
	 * Reshapes a flat {@link PackedCollection} RGB bank into a 2D {@code RGB[w][h]} array.
	 *
	 * <p>Pixels are stored in the bank in row-major order: element {@code j * w + i}
	 * corresponds to {@code image[i][j]}.</p>
	 *
	 * @param w      the image width
	 * @param h      the image height
	 * @param output a flat RGB bank of size {@code w * h}
	 * @return a 2D array of {@link RGB} objects indexed as {@code [column][row]}
	 */
	public static RGB[][] processKernelOutput(int w, int h, PackedCollection output) {
		RGB image[][] = new RGB[w][h];

		for (int i = 0; i < w; i++) {
			for (int j = 0; j < h; j++) {
				image[i][j] = (RGB) output.get(j * w + i);
			}
		}

		return image;
	}
}
