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

package org.almostrealism.geometry;

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.PairFeatures;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarFeatures;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

import java.util.function.Supplier;

/**
 * A comprehensive feature interface providing geometric and trigonometric operations.
 * Extends {@link ScalarFeatures}, {@link PairFeatures}, and {@link RayFeatures} to provide
 * a complete set of utilities for geometric computations.
 *
 * <p>Key capabilities include:</p>
 * <ul>
 *   <li>Trigonometric functions (sin, cos, tan, tanh)</li>
 *   <li>Sinusoidal wave functions with wavelength and amplitude parameters</li>
 *   <li>Vector reflection calculations</li>
 *   <li>All ray manipulation utilities from {@link RayFeatures}</li>
 * </ul>
 *
 * @author Michael Murray
 * @see ScalarFeatures
 * @see RayFeatures
 */
public interface GeometryFeatures extends ScalarFeatures, PairFeatures, RayFeatures {
	/** The mathematical constant pi (approximately 3.14159). */
	double PI = Math.PI;
	/** Two times pi (approximately 6.28318). */
	double TWO_PI = 2 * PI;
	/** The square root of 2/pi, useful in probability calculations. */
	double ROOT_2_BY_PI = Math.sqrt(2 / PI);

	/**
	 * Computes the sine of each element in the input collection.
	 *
	 * @param <T> the collection type
	 * @param input the input values
	 * @return a producer for the sine of the input
	 */
	default <T extends PackedCollection<?>> CollectionProducer<T> sin(Supplier<Evaluable<? extends PackedCollection<?>>> input) {
		// TODO  Add shortcircuit
		return compute("sin",
				shape -> args -> sin(shape, args[1]), (Producer) input);
	}

	/**
	 * Computes the cosine of each element in the input collection.
	 *
	 * @param <T> the collection type
	 * @param input the input values
	 * @return a producer for the cosine of the input
	 */
	default <T extends PackedCollection<?>> CollectionProducer<T> cos(Supplier<Evaluable<? extends PackedCollection<?>>> input) {
		// TODO  Add shortcircuit
		return compute("cos",
				shape -> args -> cos(shape, args[1]), (Producer) input);
	}

	/**
	 * Computes the tangent of each element in the input collection.
	 *
	 * @param <T> the collection type
	 * @param input the input values
	 * @return a producer for the tangent of the input
	 */
	default <T extends PackedCollection<?>> CollectionProducer<T> tan(Supplier<Evaluable<? extends PackedCollection<?>>> input) {
		// TODO  Add shortcircuit
		return compute("tan",
				shape -> args -> tan(shape, args[1]), (Producer) input);
	}

	/**
	 * Computes the hyperbolic tangent of each element in the input collection.
	 *
	 * @param <T> the collection type
	 * @param input the input values
	 * @return a producer for the hyperbolic tangent of the input
	 */
	default <T extends PackedCollection<?>> CollectionProducer<T> tanh(Supplier<Evaluable<? extends PackedCollection<?>>> input) {
		return compute("tanh",
				shape -> args -> tanh(shape, args[1]), (Producer) input);
	}

	/**
	 * Computes a sinusoidal wave function: {@code sin(2*pi*input/wavelength) * amp}
	 *
	 * @param input the input value (typically time or position)
	 * @param wavelength the wavelength of the wave
	 * @param amp the amplitude of the wave
	 * @return a producer for the wave value
	 */
	default CollectionProducer<PackedCollection<?>> sinw(Producer<PackedCollection<?>> input,
														 Producer<PackedCollection<?>> wavelength,
														 Producer<PackedCollection<?>> amp) {
		return sin(c(TWO_PI).multiply(input).divide(wavelength)).multiply(amp);
	}

	/**
	 * Computes a sinusoidal wave function with phase: {@code sin(2*pi*(input/wavelength - phase)) * amp}
	 *
	 * @param input the input value (typically time or position)
	 * @param wavelength the wavelength of the wave
	 * @param phase the phase offset (as a fraction of the wavelength)
	 * @param amp the amplitude of the wave
	 * @return a producer for the wave value
	 */
	default CollectionProducer<PackedCollection<?>> sinw(Producer<PackedCollection<?>> input,
														 Producer<PackedCollection<?>> wavelength,
														 Producer<PackedCollection<?>> phase,
														 Producer<PackedCollection<?>> amp) {
		return sin(c(TWO_PI).multiply(divide(input, wavelength).subtract(phase))).multiply(amp);
	}

	/**
	 * Computes the reflection of a vector about a surface normal.
	 * Uses the formula: {@code R = -V - 2((-V).N)N / |N|^2}
	 *
	 * @param vector the incident vector (pointing toward the surface)
	 * @param normal the surface normal
	 * @return a producer for the reflected vector
	 */
	default Producer<PackedCollection<?>> reflect(Producer<PackedCollection<?>> vector, Producer<PackedCollection<?>> normal) {
		Producer<PackedCollection<?>> newVector = minus(vector);
		Producer<PackedCollection<?>> s = scalar(2).multiply(dotProduct(newVector, normal).divide(lengthSq(normal)));
		return subtract(newVector, multiply(normal, s));
	}
}
