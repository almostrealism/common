/*
 * Copyright 2026 Michael Murray
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
import org.almostrealism.algebra.VectorFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

/**
 * A surface defined implicitly as the zero set {@code f(p) = 0} of a function of position,
 * with {@code f(p) > 0} outside the surface and {@code f(p) < 0} inside it.
 *
 * <p>Every operation is a producer graph over a position, or a batch of positions: given an
 * {@code (n, 3)} batch of positions, {@link #value(Producer)} produces the {@code (n, 1)} batch
 * of values and {@link #normal(Producer)} the {@code (n, 3)} batch of normals. A single
 * {@code (3)} position works the same way with a single result.</p>
 *
 * <p>The function need not be a distance. A ray marcher steps along a ray by
 * {@code f(p) / L}, where {@code L} is the {@link #lipschitzBound()}: no point of the surface
 * is closer to {@code p} than that, so the step can never pass through the surface. A signed
 * distance function has {@code L = 1}; the height field {@code y - h(x, z)} has
 * {@code L = sqrt(1 + slope^2)}, where {@code slope} bounds the gradient of {@code h}.</p>
 *
 * @see SignedDistanceFunction
 * @see HeightField
 * @author Michael Murray
 */
public interface ImplicitSurface extends VectorFeatures {
	/**
	 * The distance between the samples that {@link #normal(Producer)} uses to estimate the
	 * gradient of the function, when an implementation does not compute it analytically.
	 */
	double GRADIENT_DELTA = 1e-3;

	/**
	 * Returns the value of the implicit function at the given position: positive outside the
	 * surface, zero on it and negative inside.
	 *
	 * @param position a {@code (3)} position, or an {@code (n, 3)} batch of positions
	 * @return the {@code (1)} value, or the {@code (n, 1)} batch of values
	 */
	CollectionProducer value(Producer<PackedCollection> position);

	/**
	 * Returns a bound {@code L} on the rate of change of {@link #value(Producer)} with
	 * position, so that {@code |f(a) - f(b)| <= L |a - b|}. Dividing the value by this bound
	 * gives a distance that a ray can safely advance without crossing the surface.
	 *
	 * <p>The bound is a producer of a single value so that it can follow data that changes
	 * between evaluations. A {@link SignedDistanceFunction} has the bound {@code 1}.</p>
	 *
	 * @return a producer of the {@code (1)} bound
	 */
	CollectionProducer lipschitzBound();

	/**
	 * Returns the unit outward normal of the surface at the given position, the normalized
	 * gradient of {@link #value(Producer)}. The default estimates the gradient by central
	 * differences with spacing {@link #GRADIENT_DELTA}; implementations that know the gradient
	 * analytically override this.
	 *
	 * @param position a {@code (3)} position, or an {@code (n, 3)} batch of positions
	 * @return the {@code (3)} normal, or the {@code (n, 3)} batch of normals
	 */
	default CollectionProducer normal(Producer<PackedCollection> position) {
		return normalize(vector(
				partialDerivative(position, vector(GRADIENT_DELTA, 0.0, 0.0)),
				partialDerivative(position, vector(0.0, GRADIENT_DELTA, 0.0)),
				partialDerivative(position, vector(0.0, 0.0, GRADIENT_DELTA))));
	}

	/**
	 * Estimates the derivative of {@link #value(Producer)} along an offset, up to the
	 * constant factor {@code 2 |offset|}, as the central difference
	 * {@code f(p + offset) - f(p - offset)}.
	 *
	 * @param position a {@code (3)} position, or an {@code (n, 3)} batch of positions
	 * @param offset the {@code (3)} offset, shared by every position of a batch
	 * @return the {@code (1)} difference, or the {@code (n, 1)} batch of differences
	 */
	default CollectionProducer partialDerivative(Producer<PackedCollection> position,
												 Producer<PackedCollection> offset) {
		Producer<PackedCollection> o = repeatItems(shape(position), offset);
		return value(add(position, o)).subtract(value(subtract(position, o)));
	}
}
