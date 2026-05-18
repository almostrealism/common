/*
 * Copyright 2016 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.geometry;

import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorMath;

/**
 * Computes an ellipse from a conic section defined by a ray intersecting a cone.
 * Provides utilities for sampling random points on the resulting ellipse,
 * which is useful in rendering effects such as area light sampling.
 *
 * @author Michael Murray
 */
public class Elipse {
	/** The major semi-axis vector of the computed ellipse. */
	private static double[] major;

	/** The minor semi-axis vector of the computed ellipse. */
	private static double[] minor;

	/**
	 * Computes the ellipse formed by the intersection of a cone (defined by apex
	 * {@code p}, axis {@code n}, and half-angle {@code theta}) with the plane
	 * perpendicular to the vector from apex to point {@code x}.
	 * Stores the resulting ellipse major and minor axes in static fields.
	 *
	 * @param x     the point on the geometry surface (cone base reference)
	 * @param p     the apex of the cone
	 * @param n     the axis direction of the cone (unit vector)
	 * @param theta the half-angle of the cone in radians
	 */
	public static void loadConicSection(double[] x, double[] p, double[] n, double theta) {
		double[] l = VectorMath.subtract(x, p);
		double nl = new Vector(n).dotProduct(new Vector(l));
		double[] m = VectorMath.addMultiple(l, n, -nl);
		VectorMath.multiply(m, 1.0 / VectorMath.length(m));
		
		double ll = VectorMath.length(l);
		double lls = ll * Math.sin(theta);
		double nnl = nl / ll;
		double cnnl = Math.acos(nnl);
		
		double c1 = lls / Math.sin(0.5 * Math.PI - theta + cnnl);
		double c2 = lls / Math.sin(0.5 * Math.PI - theta - cnnl);
		
		Elipse.major = VectorMath.multiply(m, 0.5 * (c1 + c2));
		
		double[] nm = new Vector(n).crossProduct(new Vector(Elipse.major)).toArray();
		VectorMath.multiply(nm, 1.0 / VectorMath.length(nm));
		double nml = new Vector(nm).dotProduct(new Vector(l)) / ll;
		double cnml = Math.acos(nml);
		
		Elipse.minor = VectorMath.multiply(nm, lls / Math.sin(Math.PI - theta - cnml));
	}
	
	/**
	 * Returns a uniformly distributed random sample point on the ellipse
	 * previously computed by {@link #loadConicSection}.
	 *
	 * @return a point on the ellipse as a 3D coordinate array
	 */
	public static double[] getSample() {
		double x = 1.0;
		double y = 1.0;
		
		while (x * x + y * y > 1.0) {
			x = Math.random();
			y = Math.random();
		}
		
		double[] a = VectorMath.multiply(Elipse.major, x, true);
		VectorMath.addMultiple(a, Elipse.minor, y);
		
		return a;
	}
}
