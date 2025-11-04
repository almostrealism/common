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

package org.almostrealism.projection;

import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.PairFeatures;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.geometry.Ray;
import org.almostrealism.geometry.RayFeatures;

public interface ProjectionFeatures extends PairFeatures, RayFeatures {
	default CollectionProducer<Ray> rayAt(Producer<Pair<?>> pos, Producer<Pair<?>> sd, Vector location, Pair projectionDimensions,
										  double blur, double focalLength, Vector u, Vector v, Vector w) {
		return ray(v(location),
				direction(pos, sd, projectionDimensions, focalLength, u, v, w, new Pair(blur, blur)));
	}

	default CollectionProducer<Vector> direction(Producer<Pair<?>> pos, Producer<Pair<?>> sd,
												 Pair projectionDimensions, double focalLength,
												 Vector u, Vector v, Vector w, Pair blur) {
		Producer<Pair<?>> pd = v(projectionDimensions);

		CollectionProducer<PackedCollection<?>> sdx = l(sd);
		CollectionProducer<PackedCollection<?>> sdy = r(sd);

		CollectionProducer<PackedCollection<?>> pdx = l(pd);
		CollectionProducer<PackedCollection<?>> pdy = r(pd);

		var p = pdx.multiply(l(pos))
								.multiply(sdx.add(c(-1.0)).pow(c(-1.0))).add(pdx.multiply(c(-0.5)));
		var q = pdy.multiply(r(pos))
								.multiply(sdy.add(c(-1.0)).pow(-1.0)).add(pdy.multiply(c(-0.5)));
		var r = c(-focalLength);

		var x = p.multiply(c(u.getX())).add(q.multiply(c(v.getX()))).add(r.multiply(c(w.getX())));
		var y = p.multiply(c(u.getY())).add(q.multiply(c(v.getY()))).add(r.multiply(c(w.getY())));
		var z = p.multiply(c(u.getZ())).add(q.multiply(c(v.getZ()))).add(r.multiply(c(w.getZ())));

		CollectionProducer<Vector> pqr = vector(x, y, z);
		Producer<Scalar> len = length(pqr);

		if (blur.getX() != 0.0 || blur.getY() != 0.0) {
			CollectionProducer<Vector> wv = normalize(pqr);
			CollectionProducer<Vector> uv = u(wv, t(pqr));
			CollectionProducer<Vector> vv = v(wv, uv);

			Producer<PackedCollection<?>> random = rand(2);
			Producer<PackedCollection<?>> rx = add(c(-0.5), c(random, 0));
			Producer<PackedCollection<?>> ry = add(c(-0.5), c(random, 1));

			pqr = (CollectionProducer) pqr.add(multiply(uv, c(blur.getX())).multiply(rx));
			pqr = (CollectionProducer) pqr.add(multiply(vv, c(blur.getY())).multiply(ry));

			pqr = scalarMultiply(pqr, len);
			pqr = scalarMultiply(pqr, length(pqr).pow(-1.0));
		} else {
			// Normalize direction vector even when blur is 0 (required for correct intersection distances)
			pqr = normalize(pqr);
		}

		return pqr;
	}

	private Producer<Vector> t(CollectionProducer<Vector> pqr) {
		Producer<Vector> ft = scalarLessThan(y(pqr), x(pqr)).and(scalarLessThan(y(pqr), z(pqr)),
				vector(x(pqr), c(1.0), z(pqr)),
				vector(x(pqr), y(pqr), c(1.0)));

		Producer<Vector> t = scalarLessThan(x(pqr), y(pqr)).and(scalarLessThan(y(pqr), z(pqr)),
				vector(c(1.0), y(pqr), z(pqr)), ft);

		return t;
	}

	private CollectionProducer<Vector> u(CollectionProducer<Vector> w, Producer<Vector> t) {
		CollectionProducer<Scalar> x = y(t).multiply(z(w)).add(z(t).multiply(y(w)).multiply(scalar(-1.0)));
		CollectionProducer<Scalar> y = z(t).multiply(x(w)).add(x(t).multiply(z(w)).multiply(scalar(-1.0)));
		CollectionProducer<Scalar> z = x(t).multiply(y(w)).add(y(t).multiply(x(w)).multiply(scalar(-1.0)));
		return normalize(vector(x, y, z));
	}

	private CollectionProducer<Vector> v(CollectionProducer<Vector> w, CollectionProducer<Vector> u) {
		CollectionProducer<Scalar>  x = y(w).multiply(z(u)).add(z(w).multiply(y(u)).multiply(scalar(-1.0)));
		CollectionProducer<Scalar>  y = z(w).multiply(x(u)).add(x(w).multiply(z(u)).multiply(scalar(-1.0)));
		CollectionProducer<Scalar>  z = x(w).multiply(y(u)).add(y(w).multiply(x(u)).multiply(scalar(-1.0)));
		return vector(x, y, z);
	}
}
