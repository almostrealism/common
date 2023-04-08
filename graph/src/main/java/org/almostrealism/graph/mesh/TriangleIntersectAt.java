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

import org.almostrealism.algebra.ScalarFeatures;
import org.almostrealism.algebra.ScalarProducerBase;
import org.almostrealism.algebra.VectorProducerBase;
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
	public TriangleIntersectAt(Supplier<Evaluable<? extends PackedCollection<?>>> t, Supplier<Evaluable<? extends Ray>> r) {
		this(TriangleFeatures.getInstance().abc(t), TriangleFeatures.getInstance().def(t), TriangleFeatures.getInstance().jkl(t),
				TriangleFeatures.getInstance().normal(t), RayFeatures.getInstance().origin(r), RayFeatures.getInstance().direction(r));
	}

	protected TriangleIntersectAt(VectorProducerBase abc, VectorProducerBase def, VectorProducerBase jkl,
								  VectorProducerBase normal, VectorProducerBase origin, VectorProducerBase direction) {
		this(abc, def, jkl, normal, origin, direction, s(jkl, origin));
	}

	protected TriangleIntersectAt(VectorProducerBase abc, VectorProducerBase def, VectorProducerBase jkl,
								  VectorProducerBase normal, VectorProducerBase origin, VectorProducerBase direction,
								  VectorProducerBase s) {
		this(abc, def, jkl, normal, origin, direction, f(abc, h(def, direction)), q(abc, s), s);
	}

	protected TriangleIntersectAt(VectorProducerBase abc, VectorProducerBase def, VectorProducerBase jkl,
								  VectorProducerBase normal, VectorProducerBase origin, VectorProducerBase direction,
								  ScalarProducerBase f, VectorProducerBase q, VectorProducerBase s) {
		this(abc, def, jkl, normal, origin, direction, f, q, s, v(direction, f.pow(-1.0), q));
	}

	protected TriangleIntersectAt(VectorProducerBase abc, VectorProducerBase def, VectorProducerBase jkl,
								  VectorProducerBase normal, VectorProducerBase origin, VectorProducerBase direction,
								  ScalarProducerBase f, VectorProducerBase q, VectorProducerBase s, ScalarProducerBase v) {
		this(abc, def, jkl, normal, origin, direction, f, q, s, u(s, h(def, direction), f.pow(-1.0)), v);
	}

	protected TriangleIntersectAt(VectorProducerBase abc, VectorProducerBase def, VectorProducerBase jkl,
								  VectorProducerBase normal, VectorProducerBase origin, VectorProducerBase direction,
								  ScalarProducerBase f, VectorProducerBase q, VectorProducerBase s,
								  ScalarProducerBase u, ScalarProducerBase v) {
		this(abc, def, jkl, normal, origin, direction, f, q, s, u, v, t(def, f.pow(-1.0), q));
	}

	protected TriangleIntersectAt(VectorProducerBase abc, VectorProducerBase def, VectorProducerBase jkl,
								  VectorProducerBase normal, VectorProducerBase origin, VectorProducerBase direction,
								  ScalarProducerBase f, VectorProducerBase q, VectorProducerBase s,
								  ScalarProducerBase u, ScalarProducerBase v, ScalarProducerBase t) {
		this(abc, def, jkl, normal, origin, direction, f, q, s,
				new AcceleratedConjunctionScalar(
						t, ScalarFeatures.getInstance().scalar(-1.0),
						u.greaterThan(0.0, true),
						u.lessThan(1.0, true),
						v.greaterThan(0.0, true),
						u.add(v).lessThan(1.0, true)));
	}

	protected TriangleIntersectAt(VectorProducerBase abc, VectorProducerBase def, VectorProducerBase jkl,
								  VectorProducerBase normal, VectorProducerBase origin, VectorProducerBase direction,
								  ScalarProducerBase f, VectorProducerBase q, VectorProducerBase s,
								  AcceleratedConjunctionScalar trueValue) {
		super(f, ScalarFeatures.getInstance().scalar(-Intersection.e), trueValue,
				new GreaterThanScalar(f, ScalarFeatures.getInstance().scalar(Intersection.e), trueValue,
						ScalarFeatures.getInstance().scalar(-1.0)), true);
	}

	// TODO  Make private
	public static VectorProducerBase h(VectorProducerBase def, VectorProducerBase direction) {
		return direction.crossProduct(def);
	}

	// TODO  Make private
	public static ScalarProducerBase f(VectorProducerBase abc, VectorProducerBase h) {
		return abc.dotProduct(h);
	}

	// TODO  Make private
	public static VectorProducerBase s(VectorProducerBase jkl, VectorProducerBase origin) {
		return origin.subtract(jkl);
	}

	// TODO  Make private
	public static ScalarProducerBase u(VectorProducerBase s, VectorProducerBase h, ScalarProducerBase f) {
		return f.multiply(s.dotProduct(h));
	}

	// TODO  Make private
	public static VectorProducerBase q(VectorProducerBase abc, VectorProducerBase s) {
		return s.crossProduct(abc);
	}

	// TODO  Make private
	public static ScalarProducerBase v(VectorProducerBase direction, ScalarProducerBase f, VectorProducerBase q) {
		return f.multiply(direction.dotProduct(q));
	}

	private static ScalarProducerBase t(VectorProducerBase def, ScalarProducerBase f, VectorProducerBase q) {
		return f.multiply(def.dotProduct(q));
	}

	public static TriangleIntersectAt construct(Supplier<Evaluable<? extends PackedCollection<?>>> t, Supplier<Evaluable<? extends Ray>> r) {
		return new TriangleIntersectAt(t, r);
	}
}
