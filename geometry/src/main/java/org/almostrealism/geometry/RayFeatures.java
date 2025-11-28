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
import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DefaultTraversableExpressionComputation;

import java.util.function.BiFunction;
import java.util.function.IntFunction;

/**
 * A feature interface providing factory methods and utilities for working with {@link Ray} objects.
 * This interface extends {@link VectorFeatures} to provide convenient methods for creating rays,
 * extracting components, and performing ray-related calculations.
 *
 * <p>Typical usage involves implementing this interface in classes that work with rays:</p>
 * <pre>{@code
 * public class MyRayTracer implements RayFeatures {
 *     public void trace() {
 *         CollectionProducer<Ray> r = ray(origin, direction);
 *         CollectionProducer<PackedCollection> o = origin(r);
 *         CollectionProducer<PackedCollection> d = direction(r);
 *         CollectionProducer<PackedCollection> point = pointAt(r, t);
 *     }
 * }
 * }</pre>
 *
 * @author Michael Murray
 * @see Ray
 * @see VectorFeatures
 */
public interface RayFeatures extends VectorFeatures {

	/**
	 * Wraps a {@link Ray} value as a producer.
	 * Shorthand for {@link #value(Ray)}.
	 *
	 * @param value the ray to wrap
	 * @return a producer that yields the specified ray
	 */
	default CollectionProducer<Ray> v(Ray value) { return value(value); }

	/**
	 * Creates a fixed producer for the given {@link Ray} value.
	 *
	 * @param value the ray to wrap
	 * @return a producer that yields the specified ray
	 */
	default CollectionProducer<Ray> value(Ray value) {
		return (CollectionProducer<Ray>) (CollectionProducer) DefaultTraversableExpressionComputation.fixed(value, (BiFunction) Ray.postprocessor());
	}

	/**
	 * Creates a ray with the specified origin and direction coordinates.
	 *
	 * @param x the x-coordinate of the origin
	 * @param y the y-coordinate of the origin
	 * @param z the z-coordinate of the origin
	 * @param dx the x-component of the direction
	 * @param dy the y-component of the direction
	 * @param dz the z-component of the direction
	 * @return a producer that yields a ray with the specified origin and direction
	 */
	default CollectionProducer<Ray> ray(double x, double y, double z, double dx, double dy, double dz) {
		return value(new Ray(new Vector(x, y, z), new Vector(dx, dy, dz)));
	}

	/**
	 * Creates a ray from origin and direction vector producers.
	 *
	 * @param origin the producer for the ray origin
	 * @param direction the producer for the ray direction
	 * @return a producer that yields a ray composed of the origin and direction
	 */
	default <T extends PackedCollection> CollectionProducer<Ray>
			ray(Producer<T> origin, Producer<T> direction) {
		return (CollectionProducer) concat(shape(6), (Producer) origin, (Producer) direction);
	}

	/**
	 * Creates a ray from a function that provides coordinate values by index.
	 * Indices 0-2 are origin coordinates, indices 3-5 are direction components.
	 *
	 * @param values a function that returns coordinate values for each index
	 * @return a producer that yields the constructed ray
	 */
	default CollectionProducer<Ray> ray(IntFunction<Double> values) {
		return ray(values.apply(0), values.apply(1), values.apply(2),
				values.apply(3), values.apply(4), values.apply(5));
	}

	/**
	 * Extracts the origin vector from a ray.
	 *
	 * @param r the ray producer
	 * @return a producer for the origin vector (first 3 components)
	 */
	default CollectionProducer<PackedCollection> origin(Producer<Ray> r) {
		return subset(shape(3), r, 0);
	}

	/**
	 * Extracts the direction vector from a ray.
	 *
	 * @param r the ray producer
	 * @return a producer for the direction vector (last 3 components)
	 */
	default CollectionProducer<PackedCollection> direction(Producer<Ray> r) {
		return subset(shape(3), r, 3);
	}

	/**
	 * Computes the point along a ray at the given parametric distance.
	 * Calculates: {@code origin + t * direction}
	 *
	 * @param r the ray producer
	 * @param t the parametric distance along the ray
	 * @return a producer for the point at distance t from the origin
	 */
	default CollectionProducer<PackedCollection> pointAt(Producer<Ray> r, Producer<PackedCollection> t) {
		return direction(r).multiply(t).add(origin(r));
	}

	/**
	 * Computes the dot product of the ray origin with itself (origin squared length).
	 *
	 * @param r the ray producer
	 * @return a producer for origin dot origin
	 */
	default CollectionProducer<PackedCollection> oDoto(Producer<Ray> r) { return dotProduct(origin(r), origin(r)); }

	/**
	 * Computes the dot product of the ray direction with itself (direction squared length).
	 *
	 * @param r the ray producer
	 * @return a producer for direction dot direction
	 */
	default CollectionProducer<PackedCollection> dDotd(Producer<Ray> r) { return dotProduct(direction(r), direction(r)); }

	/**
	 * Computes the dot product of the ray origin with the direction.
	 *
	 * @param r the ray producer
	 * @return a producer for origin dot direction
	 */
	default CollectionProducer<PackedCollection> oDotd(Producer<Ray> r) { return dotProduct(origin(r), direction(r)); }

	/**
	 * Transforms a ray by a transformation matrix.
	 * The origin is transformed as a location (including translation),
	 * the direction is transformed as an offset (no translation).
	 *
	 * @param t the transformation matrix
	 * @param r the ray to transform
	 * @return a producer for the transformed ray
	 */
	default CollectionProducer<Ray> transform(TransformMatrix t, Producer<Ray> r) {
		return ray(
				TransformMatrixFeatures.getInstance().transformAsLocation(t, origin(r)),
				TransformMatrixFeatures.getInstance().transformAsOffset(t, direction(r)));
	}

	/**
	 * Returns a default instance of {@link RayFeatures}.
	 * Useful for accessing ray utilities without implementing the interface.
	 *
	 * @return a default RayFeatures instance
	 */
	static RayFeatures getInstance() {
		return new RayFeatures() { };
	}
}
