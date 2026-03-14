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

package org.almostrealism.graph.mesh;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
import org.almostrealism.Ops;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.GreaterThanCollection;
import org.almostrealism.collect.computations.LessThanCollection;
import org.almostrealism.geometry.Intersection;
import org.almostrealism.geometry.Ray;
import org.almostrealism.geometry.RayFeatures;

/**
 * Computation for ray-triangle intersection testing using the Moller-Trumbore algorithm.
 *
 * <p>{@code TriangleIntersectAt} computes the intersection point between a ray and
 * a triangle, returning the parametric distance along the ray where the intersection
 * occurs. If no valid intersection exists, it returns -1.</p>
 *
 * <p>The algorithm uses the triangle's edge vectors (abc, def) and position (jkl)
 * along with the ray's origin and direction to compute:</p>
 * <ul>
 *   <li>h = cross(direction, def) - perpendicular to direction and second edge</li>
 *   <li>f = dot(abc, h) - determinant for parallel check</li>
 *   <li>s = origin - jkl - vector from triangle vertex to ray origin</li>
 *   <li>u, v = barycentric coordinates on the triangle</li>
 *   <li>t = distance along the ray to intersection point</li>
 * </ul>
 *
 * <p>The intersection is valid if:</p>
 * <ul>
 *   <li>|f| > epsilon (ray not parallel to triangle)</li>
 *   <li>0 <= u <= 1 (within first edge)</li>
 *   <li>0 <= v and u + v <= 1 (within triangle bounds)</li>
 * </ul>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * Producer<PackedCollection> triangle = ...;
 * Producer<Ray> ray = ...;
 * TriangleIntersectAt intersection = TriangleIntersectAt.construct(triangle, ray);
 * PackedCollection result = intersection.evaluate();
 * double t = result.toDouble(0); // -1 if no intersection
 * }</pre>
 *
 * @author Michael Murray
 * @see TriangleFeatures
 * @see LessThanCollection
 */
public class TriangleIntersectAt extends LessThanCollection {
	private static TriangleIntersectAt create(Producer<PackedCollection> t,
											  Producer<Ray> r) {
		return create(TriangleFeatures.getInstance().abc(t), TriangleFeatures.getInstance().def(t), TriangleFeatures.getInstance().jkl(t),
				TriangleFeatures.getInstance().normal(t), RayFeatures.getInstance().origin(r), RayFeatures.getInstance().direction(r));
	}

	private static TriangleIntersectAt create(CollectionProducer abc, CollectionProducer def, CollectionProducer jkl,
											  CollectionProducer normal, CollectionProducer origin, CollectionProducer direction) {
		return create(abc, def, jkl, normal, origin, direction, s(jkl, origin));
	}

	private static TriangleIntersectAt create(CollectionProducer abc, CollectionProducer def, CollectionProducer jkl,
											  CollectionProducer normal, CollectionProducer origin, CollectionProducer direction,
											  Producer<PackedCollection> s) {
		return create(abc, def, jkl, normal, origin, direction, f(abc, h(def, direction)), q(abc, s), s);
	}

	private static TriangleIntersectAt create(CollectionProducer abc, CollectionProducer def, CollectionProducer jkl,
											  CollectionProducer normal, CollectionProducer origin, CollectionProducer direction,
											  CollectionProducer f, CollectionProducer q, Producer<PackedCollection> s) {
		return createv(abc, def, jkl, normal, origin, direction, f, q, s, v(direction, f.pow(-1.0), q));
	}

	private static TriangleIntersectAt createv(CollectionProducer abc, CollectionProducer def, CollectionProducer jkl,
											   CollectionProducer normal, CollectionProducer origin, CollectionProducer direction,
											   CollectionProducer f, CollectionProducer q, Producer<PackedCollection> s,
											   Producer<PackedCollection> v) {
		return createu(abc, def, jkl, normal, origin, direction, f, q, s, u(s, h(def, direction), f.pow(-1.0)), v);
	}

	private static TriangleIntersectAt createu(CollectionProducer abc, CollectionProducer def, CollectionProducer jkl,
											   CollectionProducer normal, CollectionProducer origin, CollectionProducer direction,
											   CollectionProducer f, CollectionProducer q, Producer<PackedCollection> s,
											   Producer<PackedCollection> u, Producer<PackedCollection> v) {
		return createt(f, u, v, t(def, f.pow(-1.0), q));
	}

	private static TriangleIntersectAt createt(CollectionProducer f,
											   Producer<PackedCollection> u,
											   Producer<PackedCollection> v,
											   Producer<PackedCollection> t) {
		// Create component conditions using non-strict inequalities
		// to include edge cases (standard Moller-Trumbore algorithm)
		CollectionProducer cond1 = CollectionFeatures.getInstance().greaterThanOrEqual(u, Ops.o().c(0.0));
		CollectionProducer cond2 = CollectionFeatures.getInstance().lessThanOrEqual(u, Ops.o().c(1.0));
		CollectionProducer cond3 = CollectionFeatures.getInstance().greaterThanOrEqual(v, Ops.o().c(0.0));
		CollectionProducer cond4 = CollectionFeatures.getInstance().lessThanOrEqual(Ops.o().add(u, v), Ops.o().c(1.0));

		// Chain with AND operations: ((cond1 AND cond2) AND cond3) AND cond4
		CollectionProducer conjunction = CollectionFeatures.getInstance().and(
				CollectionFeatures.getInstance().and(
						CollectionFeatures.getInstance().and(cond1, cond2),
						cond3),
				cond4,
				t,              // true value
				Ops.o().c(-1.0) // false value
		);

		return new TriangleIntersectAt(f, conjunction);
	}

	/**
	 * Protected constructor for creating the intersection computation.
	 *
	 * @param f         the determinant producer (used for parallel ray check)
	 * @param trueValue the value to return when intersection is valid
	 */
	protected TriangleIntersectAt(CollectionProducer f,
								  CollectionProducer trueValue) {
		super(new TraversalPolicy(1), f, CollectionFeatures.getInstance().c(-Intersection.e), trueValue,
				new GreaterThanCollection(new TraversalPolicy(1),
						f, CollectionFeatures.getInstance().c(Intersection.e),
						trueValue,
						CollectionFeatures.getInstance().c(-1.0)),
				true);
	}

	/**
	 * Computes h = cross(direction, def), perpendicular to ray direction and second edge.
	 *
	 * @param def       the second edge vector of the triangle
	 * @param direction the ray direction vector
	 * @return producer for the h vector
	 */
	// TODO  Make private
	public static CollectionProducer h(Producer<PackedCollection> def, Producer<PackedCollection> direction) {
		return Ops.o().crossProduct(direction, def);
	}

	/**
	 * Computes f = dot(abc, h), the determinant used for parallel ray detection.
	 *
	 * @param abc the first edge vector of the triangle
	 * @param h   the h vector from {@link #h(Producer, Producer)}
	 * @return producer for the f scalar (determinant)
	 */
	// TODO  Make private
	public static CollectionProducer f(Producer<PackedCollection> abc, CollectionProducer h) {
		return Ops.o().dotProduct(abc, h);
	}

	/**
	 * Computes s = origin - jkl, vector from triangle vertex to ray origin.
	 *
	 * @param jkl    the position vector of the triangle (first vertex)
	 * @param origin the ray origin point
	 * @return producer for the s vector
	 */
	// TODO  Make private
	public static Producer<PackedCollection> s(Producer<PackedCollection> jkl, Producer<PackedCollection> origin) {
		return Ops.o().subtract(origin, jkl);
	}

	/**
	 * Computes u = (1/f) * dot(s, h), the first barycentric coordinate.
	 *
	 * @param s the s vector from {@link #s(Producer, Producer)}
	 * @param h the h vector from {@link #h(Producer, Producer)}
	 * @param f the inverse determinant (1/f)
	 * @return producer for the u coordinate
	 */
	// TODO  Make private
	public static Producer<PackedCollection> u(Producer<PackedCollection> s, Producer<PackedCollection> h, CollectionProducer f) {
		return f.multiply(Ops.o().dotProduct(s, h));
	}

	/**
	 * Computes q = cross(s, abc), used for computing v and t.
	 *
	 * @param abc the first edge vector of the triangle
	 * @param s   the s vector from {@link #s(Producer, Producer)}
	 * @return producer for the q vector
	 */
	// TODO  Make private
	public static CollectionProducer q(Producer<PackedCollection> abc, Producer<PackedCollection> s) {
		return Ops.o().crossProduct(s, abc);
	}

	/**
	 * Computes v = (1/f) * dot(direction, q), the second barycentric coordinate.
	 *
	 * @param direction the ray direction vector
	 * @param f         the inverse determinant (1/f)
	 * @param q         the q vector from {@link #q(Producer, Producer)}
	 * @return producer for the v coordinate
	 */
	// TODO  Make private
	public static Producer<PackedCollection> v(Producer<PackedCollection> direction,
												  CollectionProducer f,
												  CollectionProducer q) {
		return f.multiply(Ops.o().dotProduct(direction, q));
	}

	/**
	 * Computes t = (1/f) * dot(def, q), the parametric distance along the ray.
	 *
	 * @param def the second edge vector of the triangle
	 * @param f   the inverse determinant (1/f)
	 * @param q   the q vector from {@link #q(Producer, Producer)}
	 * @return producer for the t parameter (intersection distance)
	 */
	private static Producer<PackedCollection> t(Producer<PackedCollection> def, Producer<PackedCollection> f, CollectionProducer q) {
		return Ops.o().multiply(f, Ops.o().dotProduct(def, q));
	}

	/**
	 * Constructs a ray-triangle intersection computation.
	 *
	 * @param t the triangle data producer (from {@link TriangleFeatures})
	 * @param r the ray producer
	 * @return a new TriangleIntersectAt computation
	 */
	public static TriangleIntersectAt construct(Producer<PackedCollection> t, Producer<Ray> r) {
		return create(t, r);
	}

	/**
	 * {@inheritDoc}
	 *
	 * @throws UnsupportedOperationException always, as this computation does not support destination creation
	 */
	@Override
	public PackedCollection createDestination(int len) {
		throw new UnsupportedOperationException();
	}
}
