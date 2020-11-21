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
import org.almostrealism.algebra.ScalarSupplier;
import org.almostrealism.algebra.VectorBank;
import org.almostrealism.algebra.VectorSupplier;
import org.almostrealism.geometry.Ray;
import org.almostrealism.math.bool.AcceleratedConjunctionScalar;
import org.almostrealism.math.bool.GreaterThanScalar;
import org.almostrealism.math.bool.LessThanScalar;
import org.almostrealism.util.Producer;
import static org.almostrealism.util.Ops.*;

import java.util.function.Supplier;

public class TriangleIntersectAt extends LessThanScalar {
	public TriangleIntersectAt(Supplier<Producer<? extends TriangleData>> t, Supplier<Producer<? extends Ray>> r) {
		this(ops().abc(t), ops().def(t), ops().jkl(t),
				ops().normal(t), ops().origin(r), ops().direction(r));
	}

	protected TriangleIntersectAt(VectorSupplier abc, VectorSupplier def, VectorSupplier jkl,
								  VectorSupplier normal, VectorSupplier origin, VectorSupplier direction) {
		this(abc, def, jkl, normal, origin, direction, s(jkl, origin));
	}

	protected TriangleIntersectAt(VectorSupplier abc, VectorSupplier def, VectorSupplier jkl,
								  VectorSupplier normal, VectorSupplier origin, VectorSupplier direction,
								  VectorSupplier s) {
		this(abc, def, jkl, normal, origin, direction, f(abc, h(def, direction)), q(abc, s), s);
	}

	protected TriangleIntersectAt(VectorSupplier abc, VectorSupplier def, VectorSupplier jkl,
								  VectorSupplier normal, VectorSupplier origin, VectorSupplier direction,
								  ScalarSupplier f, VectorSupplier q, VectorSupplier s) {
		this(abc, def, jkl, normal, origin, direction, f, q, s, v(direction, f.pow(-1.0), q));
	}

	protected TriangleIntersectAt(VectorSupplier abc, VectorSupplier def, VectorSupplier jkl,
								  VectorSupplier normal, VectorSupplier origin, VectorSupplier direction,
								  ScalarSupplier f, VectorSupplier q, VectorSupplier s, ScalarSupplier v) {
		this(abc, def, jkl, normal, origin, direction, f, q, s, u(s, h(def, direction), f.pow(-1.0)), v);
	}

	protected TriangleIntersectAt(VectorSupplier abc, VectorSupplier def, VectorSupplier jkl,
								  VectorSupplier normal, VectorSupplier origin, VectorSupplier direction,
								  ScalarSupplier f, VectorSupplier q, VectorSupplier s,
								  ScalarSupplier u, ScalarSupplier v) {
		this(abc, def, jkl, normal, origin, direction, f, q, s, u, v, t(def, f.pow(-1.0), q));
	}

	protected TriangleIntersectAt(VectorSupplier abc, VectorSupplier def, VectorSupplier jkl,
				VectorSupplier normal, VectorSupplier origin, VectorSupplier direction,
								  ScalarSupplier f, VectorSupplier q, VectorSupplier s,
								  ScalarSupplier u, ScalarSupplier v, ScalarSupplier t) {
		this(abc, def, jkl, normal, origin, direction, f, q, s,
				new AcceleratedConjunctionScalar(
						t, ops().scalar(-1.0),
						new GreaterThanScalar(u, ops().scalar(0.0), true),
						new LessThanScalar(u, ops().scalar(1.0), true),
						new GreaterThanScalar(v, ops().scalar(0.0), true),
						new LessThanScalar(u.add(v), ops().scalar(1.0), true)));
	}

	protected TriangleIntersectAt(VectorSupplier abc, VectorSupplier def, VectorSupplier jkl,
				VectorSupplier normal, VectorSupplier origin, VectorSupplier direction,
				ScalarSupplier f, VectorSupplier q, VectorSupplier s,
				AcceleratedConjunctionScalar trueValue) {
		super(f, ops().scalar(-Intersection.e), trueValue,
				new GreaterThanScalar(f, ops().scalar(Intersection.e), trueValue,
						ops().scalar(-1.0)), true);
	}

	// TODO  Make private
	public static VectorSupplier h(VectorSupplier def, VectorSupplier direction) {
		return direction.crossProduct(def);
	}

	// TODO  Make private
	public static ScalarSupplier f(VectorSupplier abc, VectorSupplier h) {
		return abc.dotProduct(h);
	}

	// TODO  Make private
	public static VectorSupplier s(VectorSupplier jkl, VectorSupplier origin) {
		return origin.subtract(jkl);
	}

	// TODO  Make private
	public static ScalarSupplier u(VectorSupplier s, VectorSupplier h, ScalarSupplier f) {
		return f.multiply(s.dotProduct(h));
	}

	// TODO  Make private
	public static VectorSupplier q(VectorSupplier abc, VectorSupplier s) {
		return s.crossProduct(abc);
	}

	// TODO  Make private
	public static ScalarSupplier v(VectorSupplier direction, ScalarSupplier f, VectorSupplier q) {
		return f.multiply(direction.dotProduct(q));
	}

	private static ScalarSupplier t(VectorSupplier def, ScalarSupplier f, VectorSupplier q) {
		return f.multiply(def.dotProduct(q));
	}

	public static TriangleIntersectAt construct(Supplier<Producer<? extends TriangleData>> t, Supplier<Producer<? extends Ray>> r) {
		return new TriangleIntersectAt(t, r);
	}
}
