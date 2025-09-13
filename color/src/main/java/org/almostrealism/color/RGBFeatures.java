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

import io.almostrealism.expression.Expression;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ArrayVariable;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarFeatures;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DefaultTraversableExpressionComputation;
import org.almostrealism.collect.computations.ExpressionComputation;
import org.almostrealism.texture.GraphicsConverter;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;

public interface RGBFeatures extends ScalarFeatures {

	default CollectionProducer<RGB> v(RGB value) { return value(value); }

	default CollectionProducer<RGB> rgb(double r, double g, double b) { return value(new RGB(r, g, b)); }

	default CollectionProducer<RGB> rgb(Producer<RGB> rgb) {
		List<Function<List<ArrayVariable<Double>>, Expression<Double>>> comp = new ArrayList<>();
		IntStream.range(0, 3).forEach(i -> comp.add(args -> args.get(1).getValueRelative(i)));
		return (ExpressionComputation<RGB>) new ExpressionComputation<>(comp, (Supplier) rgb).setPostprocessor(RGB.postprocessor());
	}

	default CollectionProducer<RGB> rgb(Producer<PackedCollection<?>> r,
										Producer<PackedCollection<?>> g,
										Producer<PackedCollection<?>> b) {
		return concat(shape(3), r, g, b);
	}

	default CollectionProducer<PackedCollection<RGB>> rgb(File file) throws IOException {
		return DefaultTraversableExpressionComputation.fixed(GraphicsConverter.loadRgb(file));
	}

	default CollectionProducer<PackedCollection<?>> channels(File file) throws IOException {
		return DefaultTraversableExpressionComputation.fixed(GraphicsConverter.loadRgb(file, true));
	}

	default <T extends PackedCollection<?>> Supplier<Runnable> saveRgb(String file, CollectionProducer<T> values) {
		return saveImage(file, false, values);
	}

	default <T extends PackedCollection<?>> Supplier<Runnable> saveRgb(File file, String format,
																	   CollectionProducer<T> values) {
		return saveImage(file, format, false, values);
	}

	default <T extends PackedCollection<?>> Supplier<Runnable> saveChannels(String file, Producer<T> values) {
		return saveImage(file, true, values);
	}

	default <T extends PackedCollection<?>> Supplier<Runnable> saveChannels(File file, String format,
																			Producer<T> values) {
		return saveImage(file, format, true, values);
	}

	default <T extends PackedCollection<?>> Supplier<Runnable> saveImage(String file,
																		 boolean channelsFirst,
																		 Producer<T> values) {
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

	default <T extends PackedCollection<?>> Supplier<Runnable> saveImage(File file, String format,
																		 boolean channelsFirst,
																		 Producer<T> values) {
		return () -> {
			Evaluable<T> ev = values.get();

			return () -> {
				try {
					BufferedImage img = GraphicsConverter.convertToAWTImage(ev.evaluate(), channelsFirst);
					ImageIO.write(img, format, file);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			};
		};
	}

	default CollectionProducer<RGB> rgb(double v) { return cfromScalar(v); }

	default CollectionProducer<RGB> white() { return rgb(1.0, 1.0, 1.0); }
	default CollectionProducer<RGB> black() { return rgb(0.0, 0.0, 0.0); }

	default CollectionProducer<RGB> value(RGB value) {
		return DefaultTraversableExpressionComputation.fixed(value, RGB.postprocessor());
	}

	default <T extends PackedCollection<?>> CollectionProducer<RGB> cfromScalar(Producer<T> value) {
		return rgb((Producer) value, (Producer) value, (Producer) value);
	}

	default CollectionProducer<RGB> cfromScalar(Scalar value) {
		return cfromScalar(ScalarFeatures.of(value));
	}

	default CollectionProducer<RGB> cfromScalar(double value) {
		return cfromScalar(new Scalar(value));
	}

	default Producer<RGB> attenuation(double da, double db, double dc,
									  Producer<RGB> color, Producer<Scalar> distanceSq) {
		return multiply(color, multiply(c(da), distanceSq)
				.add(c(db).multiply(pow(distanceSq, c(0.5))))
				.add(c(dc)));
	}


	static RGBFeatures getInstance() { return new RGBFeatures() {}; }
}
