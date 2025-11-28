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

package org.almostrealism.color;

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.ScalarFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DefaultTraversableExpressionComputation;
import org.almostrealism.texture.GraphicsConverter;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Provides factory methods and utility operations for working with {@link RGB} colors.
 *
 * <p>This interface is designed to be implemented by classes that need convenient
 * access to color creation, I/O, and computation operations. It follows the "Features"
 * pattern common in the Almost Realism framework, providing default methods that
 * can be mixed into implementing classes.</p>
 *
 * <h2>Color Creation</h2>
 * <pre>{@code
 * // Create colors from components
 * CollectionProducer<PackedCollection> red = rgb(1.0, 0.0, 0.0);
 * CollectionProducer<PackedCollection> white = white();
 * CollectionProducer<PackedCollection> gray = cfromScalar(0.5);
 *
 * // Create colors from producers (for computation graphs)
 * CollectionProducer<PackedCollection> dynamic = rgb(redProducer, greenProducer, blueProducer);
 * }</pre>
 *
 * <h2>Image I/O</h2>
 * <pre>{@code
 * // Load image as RGB collection
 * CollectionProducer<PackedCollection<RGB>> image = rgb(new File("input.png"));
 *
 * // Save color data to file
 * Supplier<Runnable> save = saveRgb("output.png", colorProducer);
 * save.get().run();
 * }</pre>
 *
 * <h2>Lighting Calculations</h2>
 * <pre>{@code
 * // Calculate light attenuation
 * Producer<PackedCollection> attenuated = attenuation(0.0, 0.0, 1.0, lightColor, distanceSq);
 * }</pre>
 *
 * @see RGB
 * @see ScalarFeatures
 * @author Michael Murray
 */
public interface RGBFeatures extends ScalarFeatures {

	/**
	 * Wraps an existing RGB value as a producer.
	 *
	 * @param value the RGB color to wrap
	 * @return a producer that yields the given RGB value
	 */
	default CollectionProducer v(RGB value) { return value(value); }

	/**
	 * Creates an RGB color producer from individual channel values.
	 *
	 * @param r the red channel value (0.0 to 1.0)
	 * @param g the green channel value (0.0 to 1.0)
	 * @param b the blue channel value (0.0 to 1.0)
	 * @return a producer that yields the specified RGB color
	 */
	default CollectionProducer rgb(double r, double g, double b) { return value(new RGB(r, g, b)); }

	/**
	 * Creates an RGB color producer by concatenating channel producers.
	 *
	 * <p>This enables dynamic color construction in computation graphs where
	 * each channel is computed separately.</p>
	 *
	 * @param r producer for the red channel
	 * @param g producer for the green channel
	 * @param b producer for the blue channel
	 * @return a producer that combines the channels into an RGB color
	 */
	default CollectionProducer rgb(Producer<PackedCollection> r,
								   Producer<PackedCollection> g,
								   Producer<PackedCollection> b) {
		return (CollectionProducer) concat(shape(3), (Producer) r, (Producer) g, (Producer) b);
	}

	/**
	 * Loads an image file as a collection of RGB colors.
	 *
	 * <p>The image is loaded with pixels in row-major order (height x width x 3),
	 * with color values normalized to the 0.0-1.0 range.</p>
	 *
	 * @param file the image file to load (supports PNG, JPG, BMP, GIF, TIFF)
	 * @return a producer yielding the image as a packed RGB collection
	 * @throws IOException if the file cannot be read or decoded
	 */
	default CollectionProducer rgb(File file) throws IOException {
		return (CollectionProducer) DefaultTraversableExpressionComputation.fixed(GraphicsConverter.loadRgb(file));
	}

	/**
	 * Loads an image file with channels-first ordering.
	 *
	 * <p>Returns the image data with shape (3 x height x width) instead of
	 * (height x width x 3). This format is common for neural network inputs.</p>
	 *
	 * @param file the image file to load
	 * @return a producer yielding the image in channels-first format
	 * @throws IOException if the file cannot be read or decoded
	 */
	default CollectionProducer channels(File file) throws IOException {
		return (CollectionProducer) DefaultTraversableExpressionComputation.fixed(GraphicsConverter.loadRgb(file, true));
	}

	/**
	 * Creates a runnable that saves RGB color data to an image file.
	 *
	 * <p>The file format is determined by the file extension. The color data
	 * should have shape (height x width x 3) with values in 0.0-1.0 range.</p>
	 *
	 * @param <T> the collection type
	 * @param file the output file path (extension determines format)
	 * @param values producer of the color data to save
	 * @return a supplier that when invoked returns a runnable to perform the save
	 */
	default Supplier<Runnable> saveRgb(String file, CollectionProducer values) {
		return saveImage(file, false, values);
	}

	/**
	 * Creates a runnable that saves RGB color data to an image file.
	 *
	 * @param <T> the collection type
	 * @param file the output file
	 * @param format the image format (e.g., "png", "jpg")
	 * @param values producer of the color data to save
	 * @return a supplier that when invoked returns a runnable to perform the save
	 */
	default Supplier<Runnable> saveRgb(File file, String format,
																	   CollectionProducer values) {
		return saveImage(file, format, false, values);
	}

	/**
	 * Creates a runnable that saves channels-first color data to an image file.
	 *
	 * <p>Use this when your data has shape (3 x height x width) instead of
	 * (height x width x 3).</p>
	 *
	 * @param <T> the collection type
	 * @param file the output file path (extension determines format)
	 * @param values producer of the channels-first color data
	 * @return a supplier that when invoked returns a runnable to perform the save
	 */
	default Supplier<Runnable> saveChannels(String file, Producer<?> values) {
		return saveImage(file, true, values);
	}

	/**
	 * Creates a runnable that saves channels-first color data to an image file.
	 *
	 * @param <T> the collection type
	 * @param file the output file
	 * @param format the image format (e.g., "png", "jpg")
	 * @param values producer of the channels-first color data
	 * @return a supplier that when invoked returns a runnable to perform the save
	 */
	default Supplier<Runnable> saveChannels(File file, String format,
																			Producer<?> values) {
		return saveImage(file, format, true, values);
	}

	/**
	 * Creates a runnable that saves image data to a file.
	 *
	 * <p>The format is determined by file extension. Supported formats:
	 * png, jpg, jpeg, bmp, gif, tiff.</p>
	 *
	 * @param <T> the collection type
	 * @param file the output file path
	 * @param channelsFirst true if data is (3 x H x W), false if (H x W x 3)
	 * @param values producer of the image data
	 * @return a supplier that when invoked returns a runnable to perform the save
	 * @throws IllegalArgumentException if the file extension is not recognized
	 */
	default Supplier<Runnable> saveImage(String file,
																		 boolean channelsFirst,
																		 Producer<?> values) {
		if (file.endsWith("png")) {
			return saveImage(new File(file), "png", channelsFirst, values);
		} else if (file.endsWith("jpg")) {
			return saveImage(new File(file), "jpg", channelsFirst, values);
		} else if (file.endsWith("jpeg")) {
			return saveImage(new File(file), "jpeg", channelsFirst, values);
		} else if (file.endsWith("bmp")) {
			return saveImage(new File(file), "bmp", channelsFirst, values);
		} else if (file.endsWith("gif")) {
			return saveImage(new File(file), "gif", channelsFirst, values);
		} else if (file.endsWith("tiff")) {
			return saveImage(new File(file), "tiff", channelsFirst, values);
		} else {
			throw new IllegalArgumentException("Unknown format: " + file);
		}
	}

	/**
	 * Creates a runnable that saves image data to a file with specified format.
	 *
	 * @param <T> the collection type
	 * @param file the output file
	 * @param format the image format (e.g., "png", "jpg")
	 * @param channelsFirst true if data is (3 x H x W), false if (H x W x 3)
	 * @param values producer of the image data
	 * @return a supplier that when invoked returns a runnable to perform the save
	 */
	default Supplier<Runnable> saveImage(File file, String format,
																		 boolean channelsFirst,
																		 Producer<?> values) {
		return () -> {
			Evaluable<?> ev = values.get();

			return () -> {
				try {
					BufferedImage img = GraphicsConverter.convertToAWTImage((PackedCollection) ev.evaluate(), channelsFirst);
					ImageIO.write(img, format, file);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			};
		};
	}

	/**
	 * Creates a grayscale RGB color from a single value.
	 *
	 * <p>The value is used for all three channels, creating a shade of gray.</p>
	 *
	 * @param v the grayscale intensity (0.0 = black, 1.0 = white)
	 * @return a producer yielding the grayscale RGB color
	 */
	default CollectionProducer rgb(double v) { return cfromScalar(v); }

	/**
	 * Creates a white color producer.
	 *
	 * @return a producer yielding RGB(1.0, 1.0, 1.0)
	 */
	default CollectionProducer white() { return rgb(1.0, 1.0, 1.0); }

	/**
	 * Creates a black color producer.
	 *
	 * @return a producer yielding RGB(0.0, 0.0, 0.0)
	 */
	default CollectionProducer black() { return rgb(0.0, 0.0, 0.0); }

	/**
	 * Wraps an RGB value as a fixed producer.
	 *
	 * @param value the RGB color to wrap
	 * @return a producer that yields the given RGB value
	 */
	default CollectionProducer value(RGB value) {
		return (CollectionProducer) DefaultTraversableExpressionComputation.fixed(value, (BiFunction) RGB.postprocessor());
	}

	/**
	 * Wraps a PackedCollection value as a fixed producer.
	 *
	 * @param value the PackedCollection to wrap
	 * @return a producer that yields the given value
	 */
	default CollectionProducer value(PackedCollection value) {
		return (CollectionProducer) DefaultTraversableExpressionComputation.fixed(value);
	}

	/**
	 * Creates an RGB color from a scalar producer by broadcasting to all channels.
	 *
	 * @param <T> the producer type
	 * @param value producer of the scalar value to broadcast
	 * @return a producer yielding RGB with all channels equal to the scalar
	 */
	default CollectionProducer cfromScalar(Producer<?> value) {
		return rgb((Producer) value, (Producer) value, (Producer) value);
	}

	/**
	 * Creates an RGB color by broadcasting a scalar to all channels.
	 *
	 * @param value the scalar value for all channels
	 * @return a producer yielding RGB with all channels equal to value
	 */
	default CollectionProducer cfromScalar(double value) {
		return cfromScalar(c(value));
	}

	/**
	 * Calculates light attenuation based on distance.
	 *
	 * <p>Implements the attenuation formula: {@code color / (da*d^2 + db*d + dc)}
	 * where d is the distance (sqrt of distanceSq). This models how light
	 * intensity decreases with distance from the source.</p>
	 *
	 * <p>Common configurations:</p>
	 * <ul>
	 *   <li>{@code (0, 0, 1)}: Constant intensity (no falloff)</li>
	 *   <li>{@code (1, 0, 0)}: Inverse-square law (physically realistic)</li>
	 *   <li>{@code (0, 1, 0)}: Linear falloff</li>
	 * </ul>
	 *
	 * @param da coefficient for quadratic (d^2) term
	 * @param db coefficient for linear (d) term
	 * @param dc constant term
	 * @param color the light color to attenuate
	 * @param distanceSq the squared distance from the light source
	 * @return a producer yielding the attenuated color
	 */
	default Producer<PackedCollection> attenuation(double da, double db, double dc,
									  Producer<PackedCollection> color, Producer<PackedCollection> distanceSq) {
		return multiply(color, multiply(c(da), distanceSq)
				.add(c(db).multiply(pow(distanceSq, c(0.5))))
				.add(c(dc)));
	}


	/**
	 * Returns a singleton instance of RGBFeatures.
	 *
	 * <p>Use this when you need access to RGBFeatures methods without
	 * implementing the interface.</p>
	 *
	 * @return a shared RGBFeatures instance
	 */
	static RGBFeatures getInstance() { return new RGBFeatures() {}; }
}
