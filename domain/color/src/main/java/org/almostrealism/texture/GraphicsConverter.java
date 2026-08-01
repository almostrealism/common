/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.texture;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Pair;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.RGB;
import org.almostrealism.io.Console;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.MemoryImageSource;
import java.awt.image.RenderedImage;
import java.io.File;
import java.io.IOException;
import java.util.function.Function;

/**
 * Provides utilities for converting between Almost Realism color types and AWT graphics types.
 *
 * <p>The {@code GraphicsConverter} is the bridge between the Almost Realism color system
 * ({@link RGB}, {@link PackedCollection}) and Java's AWT graphics system ({@link Color},
 * {@link Image}, {@link BufferedImage}). It supports:</p>
 *
 * <h2>Core Capabilities</h2>
 * <ul>
 *   <li><b>Color Conversion</b>: Convert between {@link RGB} and AWT {@link Color}</li>
 *   <li><b>Image Loading</b>: Load image files into {@link PackedCollection} format</li>
 *   <li><b>Image Saving</b>: Convert packed collections to AWT images for output</li>
 *   <li><b>Pixel Manipulation</b>: Extract and process raw pixel data</li>
 * </ul>
 *
 * <h2>Memory Layouts</h2>
 * <p>Images can be loaded in two layouts:</p>
 * <ul>
 *   <li><b>RGB (channels-last)</b>: Shape [height, width, 3] - standard image format</li>
 *   <li><b>Channels-first</b>: Shape [3, height, width] - neural network input format</li>
 * </ul>
 *
 * <h2>Example Usage</h2>
 * <pre>{@code
 * // Load an image file
 * PackedCollection<RGB> image = GraphicsConverter.loadRgb(new File("input.png"));
 *
 * // Load as channels-first for neural networks
 * PackedCollection channels = GraphicsConverter.loadRgb(new File("input.png"), true);
 *
 * // Convert RGB to AWT Color
 * RGB myColor = new RGB(1.0, 0.5, 0.0);
 * Color awtColor = GraphicsConverter.convertToAWTColor(myColor);
 *
 * // Convert back
 * RGB backToRgb = GraphicsConverter.convertToRGB(awtColor);
 *
 * // Save packed collection as image
 * BufferedImage output = GraphicsConverter.convertToAWTImage(packedData, false);
 * ImageIO.write(output, "png", new File("output.png"));
 * }</pre>
 *
 * <h2>Color Range</h2>
 * <p>Almost Realism uses double values in range [0.0, 1.0] while AWT uses integers
 * in range [0, 255]. Conversion handles this automatically. Values exceeding 1.0
 * are clamped during conversion to AWT types.</p>
 *
 * @see RGB
 * @see PackedCollection
 * @see RGBFeatures
 * @author Michael Murray
 */
public class GraphicsConverter {
	/** Constant identifying 32-bit-per-pixel image data layout. */
	public static final int image32Bit = 2;

	/** Constant identifying 8-bit-per-pixel image data layout. */
	public static final int image8Bit = 4;
	
	/**
	 * Converts the specified AWT Color object to an RGB object.
	 */
	public static RGB convertToRGB(Color color) {
		double r = color.getRed() / 255d;
		double g = color.getGreen() / 255d;
		double b = color.getBlue() / 255d;
		
		return new RGB(r, g, b);
	}
	
	/**
	 * Converts the specified RGB object to an AWT Color object.
	 */
	public static Color convertToAWTColor(RGB color) {
		return new Color((float)Math.min(1.0, Math.abs(color.getRed())),
		        		(float)Math.min(1.0, Math.abs(color.getGreen())),
		        		(float)Math.min(1.0, Math.abs(color.getBlue())));
	}
	
	/**
	 * Extracts the pixel data from a {@link RenderedImage} as an array of packed 32-bit RGB integers.
	 *
	 * <p>The returned array begins with the width at index 0 and height at index 1,
	 * followed by the packed pixel values in row-major order.</p>
	 *
	 * @param im the rendered image to extract pixels from
	 * @return an array of length {@code 2 + width * height} with dimensions and pixel data
	 */
	public static int[] extract32BitImage(RenderedImage im) {
		int w = im.getWidth();
		int h = im.getHeight();
		
		BufferedImage bim = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		bim.setData(im.copyData(null));
		
		int index = 2;
		int[] rgb = new int[2 + w * h];
		rgb[0] = w;
		rgb[1] = h;
		
		for(int j = 0; j < h; j++) {
			for(int i = 0; i < w; i++) {
				rgb[index++] = bim.getRGB(i, j);
			}
		}
		
		return rgb;
	}
	
	/**
	 * Extracts the pixel data from a {@link RenderedImage} as an array of packed 8-bit pixel bytes.
	 *
	 * <p>The array begins with the width encoded as 4 big-endian bytes (indices 0–3) and the
	 * height as 4 big-endian bytes (indices 4–7). Each subsequent byte encodes one pixel with
	 * 2 bits per channel (R, G, B) packed into 6 bits.</p>
	 *
	 * @param im the rendered image to extract pixels from
	 * @return an array of length {@code 8 + width * height} with header and pixel data
	 */
	public static byte[] extract8BitImage(RenderedImage im) {
		int w = im.getWidth();
		int h = im.getHeight();
		
		BufferedImage bim = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		bim.setData(im.copyData(null));
		
		int index = 8;
		byte[] rgb = new byte[8 + w * h];
		
		rgb[0] = (byte) ((w >> 24) & 255);
		rgb[1] = (byte) ((w >> 16) & 255);
		rgb[2] = (byte) ((w >> 8) & 255);
		rgb[3] = (byte) (w & 255);
		rgb[4] = (byte) ((h >> 24) & 255);
		rgb[5] = (byte) ((h >> 16) & 255);
		rgb[6] = (byte) ((h >> 8) & 255);
		rgb[7] = (byte) (h & 255);
		
		for(int j = 0; j < h; j++) {
			for(int i = 0; i < w; i++) {
				int c = bim.getRGB(i, j);
				int r = (c >> 16) & 255;
				int g = (c >> 8) & 255;
				int b = c & 255;
				
				r = r / 4;
				g = g / 4;
				b = b / 4;
				
				c = (r << 4) + (g << 2) + b;
				
				rgb[index++] = (byte) c;
			}
		}
		
		return rgb;
	}
	
	/**
	 * Converts a region of a flat pixel array into a 2D {@link RGB} array.
	 *
	 * @param pixel  the flat packed-ARGB pixel array
	 * @param off    the offset into {@code pixel} at which the image data starts
	 * @param x      the left column of the region to extract
	 * @param y      the top row of the region to extract
	 * @param w      the width of the region in pixels
	 * @param h      the height of the region in pixels
	 * @param imageW the total width of the source image (stride)
	 * @return a 2D array of {@link RGB} values indexed as {@code [column][row]}
	 */
	public static RGB[][] convertToRGBArray(int[] pixel, int off, int x, int y, int w, int h, int imageW) {
		RGB[][] rgb = new RGB[w][h];
		
		for(int j = 0; j < h; j++) {
			for(int i = 0; i < w; i++) {
				int color = pixel[off + (j + y) * imageW + (i + x)];
				
				int rChannel = (color >> 16) & 255;
				int gChannel = (color >> 8) & 255;
				int bChannel = color & 255;
				
				double r = rChannel / 255d;
				double g = gChannel / 255d;
				double b = bChannel / 255d;
				
				rgb[i][j] = new RGB(r, g, b);
			}
		}
		
		return rgb;
	}

	/**
	 * Loads an image file as a flat {@link PackedCollection} of RGB values.
	 *
	 * <p>Pixels are stored in {@code [row, col, channel]} order with shape {@code [height, width, 3]}.</p>
	 *
	 * @param file the image file to load
	 * @return a {@link PackedCollection} containing the image RGB data
	 * @throws IOException if the file cannot be read
	 */
	public static PackedCollection loadRgb(File file) throws IOException {
		// TODO Delegate to to the PackedCollection, and apply an RGB postprocessor for the elements
		return loadRgb(file, false);
	}

	/**
	 * Loads an image file as a {@link PackedCollection} with channels in the first dimension.
	 *
	 * <p>Pixels are stored in {@code [channel, row, col]} order with shape {@code [3, height, width]}.</p>
	 *
	 * @param file the image file to load
	 * @return a {@link PackedCollection} with channels-first layout
	 * @throws IOException if the file cannot be read
	 */
	public static PackedCollection loadChannels(File file) throws IOException {
		return loadRgb(file, true);
	}

	/**
	 * Loads an image file as a {@link PackedCollection} of RGB values, with configurable axis layout.
	 *
	 * @param file          the image file to load
	 * @param channelsFirst {@code true} to use {@code [channel, row, col]} layout; {@code false} for
	 *                      {@code [row, col, channel]} layout
	 * @return a {@link PackedCollection} containing the image RGB data in the specified layout
	 * @throws IOException if the file cannot be read
	 */
	public static PackedCollection loadRgb(File file, boolean channelsFirst) throws IOException {
		BufferedImage image = ImageIO.read(file);

		int width = image.getWidth();
		int height = image.getHeight();

		TraversalPolicy shape = channelsFirst ?
				new TraversalPolicy(3, height, width).traverse(3) :
				new TraversalPolicy(height, width, 3).traverse(2);

		PackedCollection dest = new PackedCollection(shape);
		loadRgb(dest, image, 0, 0, width, height, channelsFirst);
		return dest;
	}

	/**
	 * Loads a region of a {@link BufferedImage} into a pre-allocated {@link PackedCollection}.
	 *
	 * @param rgbDestination the destination collection (must have 3 dimensions)
	 * @param bufferedImage  the source image
	 * @param xOff          the horizontal pixel offset within the source image
	 * @param yOff          the vertical pixel offset within the source image
	 * @param w             the width of the region to load
	 * @param h             the height of the region to load
	 * @param channelsFirst  {@code true} to write channels as the first dimension
	 * @throws IllegalArgumentException if the destination does not have 3 dimensions
	 */
	public static void loadRgb(PackedCollection rgbDestination,
							   BufferedImage bufferedImage,
							   int xOff, int yOff, int w, int h,
							   boolean channelsFirst) {
		TraversalPolicy destShape = rgbDestination.getShape();
		if (destShape.getDimensions() != 3) {
			throw new IllegalArgumentException();
		}

		TraversalPolicy slice = new TraversalPolicy(1, 1, 3).traverseEach();

		for(int r = 0; r < h; r++) {
			for(int c = 0; c < w; c++) {
				int color = bufferedImage.getRGB(xOff + c, yOff + r);

				int rChannel = (color >> 16) & 255;
				int gChannel = (color >> 8) & 255;
				int bChannel = color & 255;

				if (channelsFirst) {
					rgbDestination.set(destShape.index(0, r, c), rChannel / 255d);
					rgbDestination.set(destShape.index(1, r, c), gChannel / 255d);
					rgbDestination.set(destShape.index(2, r, c), bChannel / 255d);
				} else {
					rgbDestination.range(slice, destShape.index(r, c, 0))
							.set(0, rChannel / 255d, gChannel / 255d, bChannel / 255d);
				}
			}
		}
	}

	/**
	 * Loads an image file as a grayscale {@link PackedCollection} with shape {@code [height, width, 1]}.
	 *
	 * @param file the image file to load
	 * @return a {@link PackedCollection} containing the averaged grayscale values
	 * @throws IOException if the file cannot be read
	 */
	public static PackedCollection loadGrayscale(File file) throws IOException {
		BufferedImage image = ImageIO.read(file);

		int width = image.getWidth();
		int height = image.getHeight();

		PackedCollection dest = new PackedCollection(
				new TraversalPolicy(height, width, 1).traverse(2));
		loadGrayscale(dest, image, 0, 0, width, height);
		return dest;
	}

	/**
	 * Loads a region of a {@link BufferedImage} as grayscale into a pre-allocated {@link PackedCollection}.
	 *
	 * <p>The grayscale value is computed as the average of the R, G, and B channels.</p>
	 *
	 * @param rgbDestination the destination collection (must have 3 dimensions, with 1 channel)
	 * @param bufferedImage  the source image
	 * @param xOff           the horizontal pixel offset within the source image
	 * @param yOff           the vertical pixel offset within the source image
	 * @param w              the width of the region to load
	 * @param h              the height of the region to load
	 * @throws IllegalArgumentException if the destination does not have 3 dimensions
	 */
	public static void loadGrayscale(
								PackedCollection rgbDestination,
							    BufferedImage bufferedImage,
							    int xOff, int yOff, int w, int h) {
		TraversalPolicy destShape = rgbDestination.getShape();
		if (destShape.getDimensions() != 3) {
			throw new IllegalArgumentException();
		}

		TraversalPolicy slice = new TraversalPolicy(1, 1, 1).traverseEach();

		for(int r = 0; r < h; r++) {
			for(int c = 0; c < w; c++) {
				int color = bufferedImage.getRGB(xOff + c, yOff + r);

				int rChannel = (color >> 16) & 255;
				int gChannel = (color >> 8) & 255;
				int bChannel = color & 255;

				double g = (rChannel / 255d) + (gChannel / 255d) + (bChannel / 255d);
				g /= 3;

				rgbDestination.range(slice, destShape.index(r, c, 0)).set(0, g);
			}
		}
	}

	/**
	 * Computes a luminance histogram of a region of a {@link BufferedImage}.
	 *
	 * <p>The luminance of each pixel is computed as the average of R, G, and B channels (each
	 * in [0, 1]), then mapped to one of the {@code buckets} bins.</p>
	 *
	 * @param bufferedImage the source image
	 * @param xoff          the horizontal pixel offset of the region
	 * @param yoff          the vertical pixel offset of the region
	 * @param w             the width of the region
	 * @param h             the height of the region
	 * @param buckets       the number of histogram bins
	 * @return a double array of length {@code buckets} with the pixel count per bin
	 */
	public static double[] histogram(BufferedImage bufferedImage,
									 int xoff, int yoff, int w, int h,
									 int buckets) {
		double[] histogram = new double[buckets];

		for(int i = 0; i < w; i++) {
			for(int j = 0; j < h; j++) {
				int color = bufferedImage.getRGB(xoff + i, yoff + j);

				int rChannel = (color >> 16) & 255;
				int gChannel = (color >> 8) & 255;
				int bChannel = color & 255;

				double r = rChannel / 255d;
				double g = gChannel / 255d;
				double b = bChannel / 255d;
				double avg = (r + g + b) / 3.0;
				histogram[(int) Math.min(buckets * avg, buckets - 1)] += 1.0;
			}
		}

		return histogram;
	}

	/**
	 * Evaluates the specified array of {@link Evaluable}s, producing {@link RGB}s.
	 */
	public static RGB[][] convertToRGBArray(Evaluable<PackedCollection>[][] image) {
		return convertToRGBArray(image, (Producer) null);
	}

	/**
	 * Evaluates the specified array of {@link Evaluable}s, producing {@link RGB}s.
	 */
	public static RGB[][] convertToRGBArray(Evaluable<PackedCollection>[][] image, Producer notify) {
		return convertToRGBArray(image, p -> new Pair(p.getX(), image[(int) p.getX()].length - 1 - p.getY()), notify);
	}

	/**
	 * Evaluates the specified array of {@link Evaluable}s, producing {@link RGB}s.
	 */
	public static RGB[][] convertToRGBArray(Evaluable<PackedCollection>[][] image, Function<Pair, Pair> positionForImageIndices) {
		return convertToRGBArray(image, positionForImageIndices, null);
	}

	/**
	 * Evaluates the specified array of {@link Evaluable}s, producing {@link RGB}s.
	 */
	public static RGB[][] convertToRGBArray(Evaluable<PackedCollection>[][] image,
											Function<Pair, Pair> positionForImageIndices,
											Producer notify) {
		RGB[][] evaluated = new RGB[image.length][image[0].length];

		boolean wasNull = false;

		for (int j = 0; j < image[0].length; j++) {
			i: for (int i = 0; i < image.length; i++) {
				if (image[i][j] == null) {
					wasNull = true;
					continue i;
				}

				PackedCollection result = image[i][j].evaluate(positionForImageIndices.apply(new Pair(i, j)));
				evaluated[i][j] = result instanceof RGB ? (RGB) result : new RGB(result.toDouble(0), result.toDouble(1), result.toDouble(2));
			}

			if (notify != null) {
				notify.get().evaluate(evaluated);
			}
		}

		if (wasNull)
			Console.root().println("Some image data was null.");

		return evaluated;
	}

	/**
	 * Converts the specified array of RGB objects to an AWT Image object.
	 * The array locations map to pixels in the image. The image produced
	 * uses the RGB color model with no alpha channel.
	 */
	public static Image convertToAWTImage(Evaluable<PackedCollection>[][] image) {
		return convertToAWTImage(image, null);
	}

	/**
	 * Evaluates the specified array of {@link Evaluable}s as an AWT Image object.
	 * The array locations map to pixels in the image. The image produced
	 * uses the RGB color model with no alpha channel.
	 */
	public static Image convertToAWTImage(Evaluable<PackedCollection>[][] image, Producer notify) {
		return convertToAWTImage(image,  p -> new Pair(p.getX(), image[(int) p.getX()].length - 1 - p.getY()), notify);
	}

	/**
	 * Evaluates the specified array of {@link Evaluable}s as an AWT Image object.
	 * The array locations map to pixels in the image. The image produced
	 * uses the RGB color model with no alpha channel.
	 */
	public static Image convertToAWTImage(Evaluable<PackedCollection>[][] image, Function<Pair, Pair> positionForImageIndices, Producer notify) {
		int[] data = new int[image.length * image[0].length];
		
		int index = 0;
		boolean wasNull = false;
		
		for (int j = 0; j < image[0].length; j++) {
			i: for (int i = 0; i < image.length; i++) {
				if (image[i][j] == null) {
					wasNull = true;
					index++;
					continue i;
				}
				
				PackedCollection c = image[i][j].evaluate(positionForImageIndices.apply(new Pair(i, j)));

				int r = (int)(Math.min(1.0, Math.abs(c.toDouble(0))) * 255);
				int g = (int)(Math.min(1.0, Math.abs(c.toDouble(1))) * 255);
				int b = (int)(Math.min(1.0, Math.abs(c.toDouble(2))) * 255);
				
				data[index++] = 255 << 24 | r << 16 | g << 8 | b;
			}

			if (notify != null) {
				Image img = Toolkit.getDefaultToolkit().createImage(new MemoryImageSource(j + 1, image[0].length, data, 0, image.length));
				notify.get().evaluate(img);
			}
		}
		
		if (wasNull) {
			CollectionFeatures.console
					.features(GraphicsConverter.class)
					.warn("Some image data was null");
		}
		
		return Toolkit.getDefaultToolkit().createImage(
				new MemoryImageSource(image.length, image[0].length, data, 0, image.length));
	}

	/**
	 * Converts the specified array of RGB objects to an AWT Image object.
	 * The array locations map to pixels in the image. The image produced
	 * uses the RGB color model with no alpha channel.
	 */
	public static Image convertToAWTImage(RGB[][] image) {
		return convertToAWTImage(image, null);
	}

	/**
	 * Evaluates the specified array of {@link Evaluable}s as an AWT Image object.
	 * The array locations map to pixels in the image. The image produced
	 * uses the RGB color model with no alpha channel.
	 */
	// TODO  Accelerated
	public static Image convertToAWTImage(RGB[][] image, Producer notify) {
		int[] data = new int[image.length * image[0].length];

		int index = 0;
		boolean wasNull = false;

		for (int j = 0; j < image[0].length; j++) {
			i: for (int i = 0; i < image.length; i++) {
				if (image[i][j] == null) {
					wasNull = true;
					index++;
					continue i;
				}

				RGB c = image[i][j];

				int r = (int)(Math.min(1.0, Math.abs(c.getRed())) * 255);
				int g = (int)(Math.min(1.0, Math.abs(c.getGreen())) * 255);
				int b = (int)(Math.min(1.0, Math.abs(c.getBlue())) * 255);

				data[index++] = 255 << 24 | r << 16 | g << 8 | b;
			}

			if (notify != null) {
				Image img = Toolkit.getDefaultToolkit().createImage(new MemoryImageSource(j + 1, image[0].length, data, 0, image.length));
				notify.get().evaluate(img);
			}
		}

		if (wasNull) {
			CollectionFeatures.console
					.features(GraphicsConverter.class)
					.warn("Some image data was null");
		}

		return Toolkit.getDefaultToolkit().createImage(
				new MemoryImageSource(image.length, image[0].length, data, 0, image.length));
	}

	/**
	 * Converts a {@link PackedCollection} of RGB values into an AWT {@link BufferedImage}.
	 *
	 * <p>The collection must have shape {@code [3, h, w]} (channels-first) or {@code [h, w, 3]}
	 * (channels-last) depending on the {@code channelsFirst} parameter.</p>
	 *
	 * @param values        the collection of RGB pixel values
	 * @param channelsFirst {@code true} if the channel dimension is first; {@code false} if last
	 * @return a {@link BufferedImage} of type {@link BufferedImage#TYPE_INT_ARGB}
	 */
	// TODO  Accelerated
	public static BufferedImage convertToAWTImage(PackedCollection values, boolean channelsFirst) {
		int axis = channelsFirst ? 1 : 0;
		int h = values.getShape().length(axis);
		int w = values.getShape().length(axis + 1);

		int[] data = new int[h * w];
		int index = 0;

		for (int j = 0; j < h; j++) {
			for (int i = 0; i < w; i++) {
				double rd = channelsFirst ? values.valueAt(0, j, i) : values.valueAt(j, i, 0);
				double gd = channelsFirst ? values.valueAt(1, j, i) : values.valueAt(j, i, 1);
				double bd = channelsFirst ? values.valueAt(2, j, i) : values.valueAt(j, i, 2);

				int a = 255;
				int r = (int) (Math.min(1.0, Math.abs(rd)) * 255);
				int g = (int) (Math.min(1.0, Math.abs(gd)) * 255);
				int b = (int) (Math.min(1.0, Math.abs(bd)) * 255);

				data[index++] = a << 24 | r << 16 | g << 8 | b;
			}
		}

		// Create a RenderedImage from the data
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		img.setRGB(0, 0, w, h, data, 0, w);
		return img;
	}
}
