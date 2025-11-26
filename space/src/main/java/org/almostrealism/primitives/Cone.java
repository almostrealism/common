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

package org.almostrealism.primitives;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.almostrealism.algebra.*;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.RGB;
import org.almostrealism.geometry.Intersection;
import org.almostrealism.geometry.Ray;
import io.almostrealism.code.Operator;
import io.almostrealism.relation.Producer;
import org.almostrealism.geometry.TransformMatrix;
import org.almostrealism.hardware.DynamicProducerForMemoryData;
import org.almostrealism.space.AbstractSurface;
import org.almostrealism.geometry.ShadableIntersection;
import org.almostrealism.CodeFeatures;
import io.almostrealism.relation.Evaluable;

// TODO Add ParticleGroup implementation.

/**
 * A {@link Cone} instance represents a cone in 3d space.
 */
public class Cone extends AbstractSurface implements CodeFeatures {
	private static final double nsq = 1.0 / 2.0;

	/**
	 * Constructs a Cone object that represents a cone with a base radius of 1.0,
	 * centered at the origin, that is black.
	 */
	public Cone() { super(); }
	
	/**
	 * Constructs a Cone object that represents a cone with the specified base
	 * radius, and the specified location, that is black.
	 */
	public Cone(Vector location, double radius) { super(location, radius); }
	
	/**
	 * Constructs a Cone object that represents a cone with the specified base radius,
	 * location, and color.
	 */
	public Cone(Vector location, double radius, RGB color) { super(location, radius, color); }
	
	/**
	 * Returns a {@link Vector} {@link Evaluable} that represents the vector normal to this
	 * cone at the point represented by the specified Vector object.
	 */
	@Override
	public Producer<Vector> getNormalAt(Producer<Vector> point) {
		Producer<Vector> normal = multiply(point, vector(1.0, -1.0, 1.0));
		return super.getTransform(true).transform(normal, TransformMatrix.TRANSFORM_AS_NORMAL);
	}

	/**
	 * @return  An {@link Intersection} storing the locations along the ray represented by
	 *          the specified {@link Ray} that intersection between the ray and the cone occurs.
	 */
	@Override
	public ShadableIntersection intersectAt(Producer r) {
		TransformMatrix m = getTransform(true);
		if (m != null) r = m.getInverse().transform(r);

		final Producer<Ray> fr = r;

		Producer<Scalar> s = new DynamicProducerForMemoryData<>(args -> {
				Ray ray = fr.get().evaluate(args);

				Vector d = ray.getDirection().divide(ray.getDirection().length());
				Vector o = ray.getOrigin().divide(ray.getOrigin().length());

				double ry = d.getY();
				double oy = o.getY();
				double od = d.dotProduct(o);
				double oo = o.dotProduct(o);

				double c2 = ry * ry - Cone.nsq;
				double c1 = ry * oy - Cone.nsq * od;
				double c0 = oy * oy - Cone.nsq * oo;

				List inter = new ArrayList();

				if (Math.abs(c2) >= Intersection.e) {
					double discr = c1 * c1 - c0 * c2;

					if (discr < 0.0) {
						return null;
					} else if (discr > Intersection.e) {
						double root = Math.sqrt(discr);
						double invC2 = 1.0 / c2;

						double t = (-c1 - root) * invC2;
						Vector p = ray.pointAt(c(t)).get().evaluate(args);
						if (p.getY() > 0.0 && p.getY() < 1.0) inter.add(t);

						t = (-c1 + root) * invC2;
						p = ray.pointAt(c(t)).get().evaluate(args);
						if (p.getY() > 0.0 && p.getY() < 1.0) inter.add(t);
					} else {
						double t = -c1 / c2;
						Vector p = ray.pointAt(c(t)).get().evaluate(args);

						if (p.getY() > 0.0 && p.getY() < 1.0) inter.add(t);
					}
				} else if (Math.abs(c1) >= Intersection.e) {
					double t = -0.5 * c0 / c1;
					Vector p = ray.pointAt(c(t)).get().evaluate(args);
					if (p.getY() > 0.0 && p.getY() < 1.0) inter.add(t);
				} else if (Math.abs(c0) < Intersection.e) {
					inter.add(0.0);
					inter.add(1.0);
				}

				double t = Double.MAX_VALUE;
				int i = 0;

				Iterator itr = inter.iterator();
				while (itr.hasNext()) {
					double n = ((Number) itr.next()).doubleValue();
					if (n >= 0.0 && n < t) t = n;
				}

				if (t == Double.MAX_VALUE) {
					return null;
				} else {
					Scalar ts = new Scalar(t);
					return ts;
				}
			});

		return new ShadableIntersection(this, fr, s);
	}

	@Override
	public Operator<PackedCollection<?>> expect() {
		return null;
	}

	@Override
	public Operator<PackedCollection<?>> get() {
		return null;
	}
}
