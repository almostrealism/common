/*
 * Copyright 2023 Michael Murray
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
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.ExpressionComputation;
import org.almostrealism.geometry.Intersection;
import org.almostrealism.geometry.Ray;
import org.almostrealism.geometry.RayFeatures;
import org.almostrealism.bool.AcceleratedConjunctionScalar;
import org.almostrealism.bool.GreaterThanScalar;
import org.almostrealism.bool.LessThanScalar;
import io.almostrealism.relation.Evaluable;

import java.util.function.Supplier;

public class TriangleIntersectAt extends LessThanScalar {
	private static TriangleIntersectAt create(Supplier<Evaluable<? extends PackedCollection<?>>> t, Supplier<Evaluable<? extends Ray>> r) {
		return create(TriangleFeatures.getInstance().abc(t), TriangleFeatures.getInstance().def(t), TriangleFeatures.getInstance().jkl(t),
				TriangleFeatures.getInstance().normal(t), RayFeatures.getInstance().origin(r), RayFeatures.getInstance().direction(r));
	}

	private static TriangleIntersectAt create(ExpressionComputation<Vector> abc, ExpressionComputation<Vector> def, ExpressionComputation<Vector> jkl,
								  ExpressionComputation<Vector> normal, ExpressionComputation<Vector> origin, ExpressionComputation<Vector> direction) {
		return create(abc, def, jkl, normal, origin, direction, s(jkl, origin));
	}

	private static TriangleIntersectAt create(ExpressionComputation<Vector> abc, ExpressionComputation<Vector> def, ExpressionComputation<Vector> jkl,
								  ExpressionComputation<Vector> normal, ExpressionComputation<Vector> origin, ExpressionComputation<Vector> direction,
								  Producer<Vector> s) {
		return create(abc, def, jkl, normal, origin, direction, f(abc, h(def, direction)), q(abc, s), s);
	}

	private static TriangleIntersectAt create(ExpressionComputation<Vector> abc, ExpressionComputation<Vector> def, ExpressionComputation<Vector> jkl,
								  ExpressionComputation<Vector> normal, ExpressionComputation<Vector> origin, ExpressionComputation<Vector> direction,
								  ExpressionComputation<Scalar> f, ExpressionComputation<Vector> q, Producer<Vector> s) {
		return createv(abc, def, jkl, normal, origin, direction, f, q, s, v(direction, f.pow(-1.0), q));
	}

	private static TriangleIntersectAt createv(ExpressionComputation<Vector> abc, ExpressionComputation<Vector> def, ExpressionComputation<Vector> jkl,
								  ExpressionComputation<Vector> normal, ExpressionComputation<Vector> origin, ExpressionComputation<Vector> direction,
								  ExpressionComputation<Scalar> f, ExpressionComputation<Vector> q, Producer<Vector> s, Producer<Scalar> v) {
		return createu(abc, def, jkl, normal, origin, direction, f, q, s, u(s, h(def, direction), f.pow(-1.0)), v);
	}

	private static TriangleIntersectAt createu(ExpressionComputation<Vector> abc, ExpressionComputation<Vector> def, ExpressionComputation<Vector> jkl,
								  ExpressionComputation<Vector> normal, ExpressionComputation<Vector> origin, ExpressionComputation<Vector> direction,
								  ExpressionComputation<Scalar> f, ExpressionComputation<Vector> q, Producer<Vector> s,
								  Producer<Scalar> u, Producer<Scalar> v) {
		return createt(abc, def, jkl, normal, origin, direction, f, q, s, u, v, t(def, f.pow(-1.0), q));
	}

	private static TriangleIntersectAt createt(ExpressionComputation<Vector> abc, ExpressionComputation<Vector> def, ExpressionComputation<Vector> jkl,
								  ExpressionComputation<Vector> normal, ExpressionComputation<Vector> origin, ExpressionComputation<Vector> direction,
								  ExpressionComputation<Scalar> f, ExpressionComputation<Vector> q, Producer<Vector> s,
								  Producer<Scalar> u, Producer<Scalar> v, Producer<Scalar> t) {
		return new TriangleIntersectAt(abc, def, jkl, normal, origin, direction, f, q, s,
				new AcceleratedConjunctionScalar(
						t, ScalarFeatures.getInstance().scalar(-1.0),
						Ops.ops().greaterThan(u, Ops.ops().v(0.0), true),
						Ops.ops().lessThan(u, Ops.ops().v(1.0), true),
						Ops.ops().greaterThan(v, Ops.ops().v(0.0), true),
						Ops.ops().lessThan(Ops.ops().add(u, v), Ops.ops().v(1.0), true)));
	}

	protected TriangleIntersectAt(ExpressionComputation<Vector> abc, ExpressionComputation<Vector> def, ExpressionComputation<Vector> jkl,
								  ExpressionComputation<Vector> normal, ExpressionComputation<Vector> origin, ExpressionComputation<Vector> direction,
								  ExpressionComputation<Scalar> f, ExpressionComputation<Vector> q, Producer<Vector> s,
								  AcceleratedConjunctionScalar trueValue) {
		super(f, ScalarFeatures.getInstance().scalar(-Intersection.e), trueValue,
				new GreaterThanScalar(f, ScalarFeatures.getInstance().scalar(Intersection.e), trueValue,
						ScalarFeatures.getInstance().scalar(-1.0)), true);
	}

	// TODO  Make private
	public static ExpressionComputation<Vector> h(Producer<Vector> def, Producer<Vector> direction) {
		return Ops.ops().crossProduct(direction, def);
	}

	// TODO  Make private
	public static ExpressionComputation<Scalar> f(Producer<Vector> abc, ExpressionComputation<Vector> h) {
		return Ops.ops().dotProduct(abc, h);
	}

	// TODO  Make private
	public static Producer<Vector> s(Producer<Vector> jkl, Producer<Vector> origin) {
		return Ops.ops().subtract(origin, jkl);
	}

	// TODO  Make private
	public static Producer<Scalar> u(Producer<Vector> s, Producer<Vector> h, ExpressionComputation<Scalar> f) {
		return Ops.ops().scalar(f.multiply(Ops.ops().dotProduct(s, h)));
	}

	// TODO  Make private
	public static ExpressionComputation<Vector> q(Producer<Vector> abc, Producer<Vector> s) {
		return Ops.ops().crossProduct(s, abc);
	}

	// TODO  Make private
	public static Producer<Scalar> v(Producer<Vector> direction, ExpressionComputation<Scalar> f, ExpressionComputation<Vector> q) {
		return Ops.ops().scalar(f.multiply(Ops.ops().dotProduct(direction, q)));
	}

	private static Producer<Scalar> t(Producer<Vector> def, Producer<Scalar> f, ExpressionComputation<Vector> q) {
		return Ops.ops().multiply(f, Ops.ops().dotProduct(def, q));
	}

	public static TriangleIntersectAt construct(Supplier<Evaluable<? extends PackedCollection<?>>> t, Supplier<Evaluable<? extends Ray>> r) {
		return create(t, r);
	}
}
