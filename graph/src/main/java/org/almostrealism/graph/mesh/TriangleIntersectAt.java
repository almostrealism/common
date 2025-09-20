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

import io.almostrealism.relation.Producer;
import org.almostrealism.Ops;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarFeatures;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.geometry.Intersection;
import org.almostrealism.geometry.Ray;
import org.almostrealism.geometry.RayFeatures;
import org.almostrealism.bool.AcceleratedConjunctionScalar;
import org.almostrealism.bool.GreaterThanScalar;
import org.almostrealism.bool.LessThanScalar;
import io.almostrealism.relation.Evaluable;

import java.util.function.Supplier;

public class TriangleIntersectAt extends LessThanScalar {
	private static TriangleIntersectAt create(Producer<PackedCollection<?>> t,
											  Producer<Ray> r) {
		return create(TriangleFeatures.getInstance().abc(t), TriangleFeatures.getInstance().def(t), TriangleFeatures.getInstance().jkl(t),
				TriangleFeatures.getInstance().normal(t), RayFeatures.getInstance().origin(r), RayFeatures.getInstance().direction(r));
	}

	private static TriangleIntersectAt create(CollectionProducer<Vector> abc, CollectionProducer<Vector> def, CollectionProducer<Vector> jkl,
								  CollectionProducer<Vector> normal, CollectionProducer<Vector> origin, CollectionProducer<Vector> direction) {
		return create(abc, def, jkl, normal, origin, direction, s(jkl, origin));
	}

	private static TriangleIntersectAt create(CollectionProducer<Vector> abc, CollectionProducer<Vector> def, CollectionProducer<Vector> jkl,
								  CollectionProducer<Vector> normal, CollectionProducer<Vector> origin, CollectionProducer<Vector> direction,
								  Producer<Vector> s) {
		return create(abc, def, jkl, normal, origin, direction, f(abc, h(def, direction)), q(abc, s), s);
	}

	private static TriangleIntersectAt create(CollectionProducer<Vector> abc, CollectionProducer<Vector> def, CollectionProducer<Vector> jkl,
											  CollectionProducer<Vector> normal, CollectionProducer<Vector> origin, CollectionProducer<Vector> direction,
											  CollectionProducer<PackedCollection<?>> f, CollectionProducer<Vector> q, Producer<Vector> s) {
		return createv(abc, def, jkl, normal, origin, direction, f, q, s, v(direction, f.pow(-1.0), q));
	}

	private static TriangleIntersectAt createv(CollectionProducer<Vector> abc, CollectionProducer<Vector> def, CollectionProducer<Vector> jkl,
								  			   CollectionProducer<Vector> normal, CollectionProducer<Vector> origin, CollectionProducer<Vector> direction,
								               CollectionProducer<PackedCollection<?>> f, CollectionProducer<Vector> q, Producer<Vector> s,
											   Producer<PackedCollection<?>> v) {
		return createu(abc, def, jkl, normal, origin, direction, f, q, s, u(s, h(def, direction), f.pow(-1.0)), v);
	}

	private static TriangleIntersectAt createu(CollectionProducer<Vector> abc, CollectionProducer<Vector> def, CollectionProducer<Vector> jkl,
								  CollectionProducer<Vector> normal, CollectionProducer<Vector> origin, CollectionProducer<Vector> direction,
								  CollectionProducer<PackedCollection<?>> f, CollectionProducer<Vector> q, Producer<Vector> s,
								  Producer<PackedCollection<?>> u, Producer<PackedCollection<?>> v) {
		return createt(abc, def, jkl, normal, origin, direction, f, q, s, u, v, t(def, f.pow(-1.0), q));
	}

	private static TriangleIntersectAt createt(CollectionProducer<Vector> abc, CollectionProducer<Vector> def, CollectionProducer<Vector> jkl,
								  CollectionProducer<Vector> normal, CollectionProducer<Vector> origin, CollectionProducer<Vector> direction,
								  CollectionProducer<PackedCollection<?>> f, CollectionProducer<Vector> q, Producer<Vector> s,
								  Producer<PackedCollection<?>> u, Producer<PackedCollection<?>> v, Producer<PackedCollection<?>> t) {
		return new TriangleIntersectAt(abc, def, jkl, normal, origin, direction, f, q, s,
				new AcceleratedConjunctionScalar(
						t, ScalarFeatures.getInstance().scalar(-1.0),
						Ops.o().scalarGreaterThan((Producer) u, Ops.o().scalar(0.0), true),
						Ops.o().scalarLessThan((Producer) u, Ops.o().scalar(1.0), true),
						Ops.o().scalarGreaterThan((Producer) v, Ops.o().scalar(0.0), true),
						Ops.o().scalarLessThan((Producer) Ops.o().add(u, v), Ops.o().scalar(1.0), true)));
	}

	protected TriangleIntersectAt(CollectionProducer<Vector> abc, CollectionProducer<Vector> def, CollectionProducer<Vector> jkl,
								  CollectionProducer<Vector> normal, CollectionProducer<Vector> origin, CollectionProducer<Vector> direction,
								  CollectionProducer<PackedCollection<?>> f, CollectionProducer<Vector> q, Producer<Vector> s,
								  AcceleratedConjunctionScalar trueValue) {
		super(f, ScalarFeatures.getInstance().scalar(-Intersection.e), trueValue,
				new GreaterThanScalar(f, ScalarFeatures.getInstance().scalar(Intersection.e), trueValue,
						ScalarFeatures.getInstance().scalar(-1.0)), true);
	}

	// TODO  Make private
	public static CollectionProducer<Vector> h(Producer<Vector> def, Producer<Vector> direction) {
		return Ops.o().crossProduct(direction, def);
	}

	// TODO  Make private
	public static CollectionProducer<PackedCollection<?>> f(Producer<Vector> abc, CollectionProducer<Vector> h) {
		return Ops.o().dotProduct(abc, h);
	}

	// TODO  Make private
	public static Producer<Vector> s(Producer<Vector> jkl, Producer<Vector> origin) {
		return Ops.o().subtract(origin, jkl);
	}

	// TODO  Make private
	public static Producer<PackedCollection<?>> u(Producer<Vector> s, Producer<Vector> h, CollectionProducer<Scalar> f) {
		return f.multiply(Ops.o().dotProduct(s, h));
	}

	// TODO  Make private
	public static CollectionProducer<Vector> q(Producer<Vector> abc, Producer<Vector> s) {
		return Ops.o().crossProduct(s, abc);
	}

	// TODO  Make private
	public static Producer<PackedCollection<?>> v(Producer<Vector> direction,
												  CollectionProducer<PackedCollection<?>> f,
												  CollectionProducer<Vector> q) {
		return f.multiply(Ops.o().dotProduct(direction, q));
	}

	private static Producer<PackedCollection<?>> t(Producer<Vector> def, Producer<PackedCollection<?>> f, CollectionProducer<Vector> q) {
		return Ops.o().multiply(f, Ops.o().dotProduct(def, q));
	}

	public static TriangleIntersectAt construct(Producer<PackedCollection<?>> t, Producer<Ray> r) {
		return create(t, r);
	}
}
