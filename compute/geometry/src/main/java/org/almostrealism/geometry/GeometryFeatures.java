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

import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.PairFeatures;
import org.almostrealism.algebra.ScalarFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.CollectionCosineComputation;
import org.almostrealism.collect.computations.CollectionHyperbolicTangentComputation;
import org.almostrealism.collect.computations.CollectionSineComputation;

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
 * <p>Like all {@code Features} interfaces, this is a mixin: a type that needs these
 * operations should <em>implement</em> this interface (the methods are stateless
 * {@code default} methods) rather than accept or hold a {@code Features} instance —
 * passing one around as an object defeats the purpose of the pattern.</p>
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
	 * <p>This method uses {@link CollectionSineComputation} which implements delta() at the
	 * Producer level, creating isolated computation boundaries for efficient automatic
	 * differentiation during backpropagation.</p>
	 *
	 * @param input the input values
	 * @return a producer for the sine of the input
	 */
	default CollectionProducer sin(Producer<PackedCollection> input) {
		return new CollectionSineComputation(shape(input), input);
	}

	/**
	 * Computes the cosine of each element in the input collection.
	 *
	 * <p>This method uses {@link CollectionCosineComputation} which implements delta() at the
	 * Producer level, creating isolated computation boundaries for efficient automatic
	 * differentiation during backpropagation.</p>
	 *
	 * @param input the input values
	 * @return a producer for the cosine of the input
	 */
	default CollectionProducer cos(Producer<PackedCollection> input) {
		return new CollectionCosineComputation(shape(input), input);
	}

	/**
	 * Computes the arc-cosine (inverse cosine) of each element in the input collection.
	 *
	 * @param input the input values, each defined on {@code [-1, 1]}
	 * @return a producer for the arc-cosine of the input
	 */
	default CollectionProducer acos(Producer<PackedCollection> input) {
		return compute("acos",
				shape -> args -> acos(shape, args[1]), input);
	}

	/**
	 * Computes the tangent of each element in the input collection.
	 *
	 * @param input the input values
	 * @return a producer for the tangent of the input
	 */
	default CollectionProducer tan(Producer<PackedCollection> input) {
		// TODO  Add shortcircuit
		return compute("tan",
				shape -> args -> tan(shape, args[1]), input);
	}

	/**
	 * Computes the hyperbolic tangent of each element in the input collection.
	 *
	 * <p>This method uses {@link CollectionHyperbolicTangentComputation} which implements delta() at
	 * the Producer level, as {@link #sin} and {@link #cos} do, so the derivative of the hyperbolic
	 * tangent of a computed argument is the chain rule's {@code (1 - tanh(u)^2) * du}.</p>
	 *
	 * @param input the input values
	 * @return a producer for the hyperbolic tangent of the input
	 */
	default CollectionProducer tanh(Producer<PackedCollection> input) {
		return new CollectionHyperbolicTangentComputation(shape(input), input);
	}

	/**
	 * Computes a sinusoidal wave function: {@code sin(2*pi*input/wavelength) * amp}
	 *
	 * @param input the input value (typically time or position)
	 * @param wavelength the wavelength of the wave
	 * @param amp the amplitude of the wave
	 * @return a producer for the wave value
	 */
	default CollectionProducer sinw(Producer<PackedCollection> input,
									Producer<PackedCollection> wavelength,
									Producer<PackedCollection> amp) {
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
	default CollectionProducer sinw(Producer<PackedCollection> input,
									Producer<PackedCollection> wavelength,
									Producer<PackedCollection> phase,
									Producer<PackedCollection> amp) {
		return sin(c(TWO_PI).multiply(divide(input, wavelength).subtract(phase))).multiply(amp);
	}

	/**
	 * Computes the mirror reflection of a ray arriving at a surface.
	 * Uses the formula: {@code R = -V - 2((-V).N)N / |N|^2}, which is the familiar
	 * {@code I - 2(I.N)N / |N|^2} for the incident direction of travel {@code I = -V}.
	 *
	 * @param vector the vector from the surface toward the viewer (the negation of the
	 *               incident direction of travel)
	 * @param normal the surface normal
	 * @return a producer for the reflected direction of travel
	 */
	default Producer<PackedCollection> reflect(Producer<PackedCollection> vector, Producer<PackedCollection> normal) {
		Producer<PackedCollection> newVector = minus(vector);
		Producer<PackedCollection> s = scalar(2).multiply(dotProduct(newVector, normal).divide(lengthSq(normal)));
		return subtract(newVector, multiply(normal, s));
	}

	/**
	 * Computes a glossy reflection: the mirror reflection of {@link #reflect(Producer, Producer)},
	 * perturbed within the plane perpendicular to the normal by a uniformly distributed amount
	 * in {@code [-blur / 2, blur / 2)} along each of two tangent axes, and renormalized.
	 * A blur of 0.0 returns the mirror reflection unchanged.
	 *
	 * @param vector the vector from the surface toward the viewer
	 * @param normal the surface normal
	 * @param blur   the amount of random perturbation (0.0 = perfect mirror)
	 * @return a producer for the reflected direction of travel
	 */
	default CollectionProducer reflect(Producer<PackedCollection> vector, Producer<PackedCollection> normal,
									   double blur) {
		CollectionProducer reflected = c(reflect(vector, normal));
		if (blur == 0.0) return reflected;

		CollectionProducer n = normalize(normal);
		CollectionProducer helper = greaterThan(abs(x(n)), c(0.9),
				vector(0.0, 1.0, 0.0), vector(1.0, 0.0, 0.0));
		CollectionProducer u = normalize(crossProduct(n, helper));
		CollectionProducer v = crossProduct(n, u);

		CollectionProducer offsets = rand(2).subtract(c(0.5)).multiply(c(blur));
		return normalize(reflected
				.add(u.multiply(subset(shape(1), offsets, 0)))
				.add(v.multiply(subset(shape(1), offsets, 1))));
	}

	/**
	 * Computes the direction of a ray refracted through the boundary of a medium with the
	 * given index of refraction, surrounded by a medium of index 1, using Snell's law.
	 *
	 * <p>The viewer vector points from the surface back toward where the ray came from.
	 * When it is on the side the normal points to, the ray is entering the medium (the
	 * relative index is {@code 1 / index}); otherwise it is leaving it (the relative index
	 * is {@code index}) and the normal is flipped to face the viewer. With {@code I} the
	 * unit incident direction of travel, {@code N} the unit normal facing the viewer,
	 * {@code eta} the relative index and {@code cos = -I . N}, the refracted direction is
	 * {@code eta I + (eta cos - sqrt(k)) N} with {@code k = 1 - eta^2 (1 - cos^2)}. When
	 * {@code k < 0} the ray is totally internally reflected and the mirror reflection is
	 * returned instead.</p>
	 *
	 * @param vector the vector from the surface toward the viewer
	 * @param normal the outward surface normal
	 * @param index  the index of refraction of the medium the normal points out of
	 * @return a producer for the unit direction of travel of the refracted (or totally
	 *         internally reflected) ray
	 */
	default CollectionProducer refract(Producer<PackedCollection> vector, Producer<PackedCollection> normal,
									   double index) {
		CollectionProducer v = normalize(vector);
		CollectionProducer n = normalize(normal);
		CollectionProducer cos = dotProduct(v, n);

		CollectionProducer facing = greaterThan(cos, c(0.0), n, n.minus());
		CollectionProducer eta = greaterThan(cos, c(0.0), c(1.0 / index), c(index));
		CollectionProducer cosi = abs(cos);
		CollectionProducer k = c(1.0).subtract(eta.multiply(eta).multiply(c(1.0).subtract(cosi.multiply(cosi))));

		CollectionProducer transmitted = v.minus().multiply(eta)
				.add(facing.multiply(eta.multiply(cosi).subtract(sqrt(max(k, c(0.0))))));
		return greaterThan(k, c(0.0), transmitted, c(reflect(v, facing)), true);
	}

	/**
	 * Returns a stateless instance of {@link GeometryFeatures}.
	 *
	 * @return a {@link GeometryFeatures} instance
	 */
	static GeometryFeatures getInstance() {
		return new GeometryFeatures() { };
	}
}
