/*
 * Copyright 2020 Michael Murray
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

import org.almostrealism.algebra.Intersection;
import org.almostrealism.algebra.ScalarProducer;
import org.almostrealism.algebra.VectorProducer;
import org.almostrealism.geometry.Ray;
import org.almostrealism.math.bool.AcceleratedConjunctionScalar;
import org.almostrealism.math.bool.GreaterThanScalar;
import org.almostrealism.math.bool.LessThanScalar;
import org.almostrealism.relation.Evaluable;
import static org.almostrealism.util.Ops.*;

import java.util.function.Supplier;

public class TriangleIntersectAt extends LessThanScalar {
	public TriangleIntersectAt(Supplier<Evaluable<? extends TriangleData>> t, Supplier<Evaluable<? extends Ray>> r) {
		this(ops().abc(t), ops().def(t), ops().jkl(t),
				ops().normal(t), ops().origin(r), ops().direction(r));
	}

	protected TriangleIntersectAt(VectorProducer abc, VectorProducer def, VectorProducer jkl,
								  VectorProducer normal, VectorProducer origin, VectorProducer direction) {
		this(abc, def, jkl, normal, origin, direction, s(jkl, origin));
	}

	protected TriangleIntersectAt(VectorProducer abc, VectorProducer def, VectorProducer jkl,
								  VectorProducer normal, VectorProducer origin, VectorProducer direction,
								  VectorProducer s) {
		this(abc, def, jkl, normal, origin, direction, f(abc, h(def, direction)), q(abc, s), s);
	}

	protected TriangleIntersectAt(VectorProducer abc, VectorProducer def, VectorProducer jkl,
								  VectorProducer normal, VectorProducer origin, VectorProducer direction,
								  ScalarProducer f, VectorProducer q, VectorProducer s) {
		this(abc, def, jkl, normal, origin, direction, f, q, s, v(direction, f.pow(-1.0), q));
	}

	protected TriangleIntersectAt(VectorProducer abc, VectorProducer def, VectorProducer jkl,
								  VectorProducer normal, VectorProducer origin, VectorProducer direction,
								  ScalarProducer f, VectorProducer q, VectorProducer s, ScalarProducer v) {
		this(abc, def, jkl, normal, origin, direction, f, q, s, u(s, h(def, direction), f.pow(-1.0)), v);
	}

	protected TriangleIntersectAt(VectorProducer abc, VectorProducer def, VectorProducer jkl,
								  VectorProducer normal, VectorProducer origin, VectorProducer direction,
								  ScalarProducer f, VectorProducer q, VectorProducer s,
								  ScalarProducer u, ScalarProducer v) {
		this(abc, def, jkl, normal, origin, direction, f, q, s, u, v, t(def, f.pow(-1.0), q));
	}

	protected TriangleIntersectAt(VectorProducer abc, VectorProducer def, VectorProducer jkl,
								  VectorProducer normal, VectorProducer origin, VectorProducer direction,
								  ScalarProducer f, VectorProducer q, VectorProducer s,
								  ScalarProducer u, ScalarProducer v, ScalarProducer t) {
		this(abc, def, jkl, normal, origin, direction, f, q, s,
				new AcceleratedConjunctionScalar(
						t, ops().scalar(-1.0),
						u.greaterThan(0.0, true),
						u.lessThan(1.0, true),
						v.greaterThan(0.0, true),
						u.add(v).lessThan(1.0, true)));
	}

	protected TriangleIntersectAt(VectorProducer abc, VectorProducer def, VectorProducer jkl,
								  VectorProducer normal, VectorProducer origin, VectorProducer direction,
								  ScalarProducer f, VectorProducer q, VectorProducer s,
								  AcceleratedConjunctionScalar trueValue) {
		super(f, ops().scalar(-Intersection.e), trueValue,
				new GreaterThanScalar(f, ops().scalar(Intersection.e), trueValue,
						ops().scalar(-1.0)), true);
	}

	// TODO  Make private
	public static VectorProducer h(VectorProducer def, VectorProducer direction) {
		return direction.crossProduct(def);
	}

	// TODO  Make private
	public static ScalarProducer f(VectorProducer abc, VectorProducer h) {
		return abc.dotProduct(h);
	}

	// TODO  Make private
	public static VectorProducer s(VectorProducer jkl, VectorProducer origin) {
		return origin.subtract(jkl);
	}

	// TODO  Make private
	public static ScalarProducer u(VectorProducer s, VectorProducer h, ScalarProducer f) {
		return f.multiply(s.dotProduct(h));
	}

	// TODO  Make private
	public static VectorProducer q(VectorProducer abc, VectorProducer s) {
		return s.crossProduct(abc);
	}

	// TODO  Make private
	public static ScalarProducer v(VectorProducer direction, ScalarProducer f, VectorProducer q) {
		return f.multiply(direction.dotProduct(q));
	}

	private static ScalarProducer t(VectorProducer def, ScalarProducer f, VectorProducer q) {
		return f.multiply(def.dotProduct(q));
	}

	public static TriangleIntersectAt construct(Supplier<Evaluable<? extends TriangleData>> t, Supplier<Evaluable<? extends Ray>> r) {
		return new TriangleIntersectAt(t, r);
	}
}
