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

package org.almostrealism.algebra;

@Deprecated
public class VectorMath {
	
	/**
	 * Do not construct this class.
	 */
	private VectorMath() {}
	
	/**
	 * Returns a copy of the specified vector.
	 * 
	 * @param x  {x, y, z} - Vector to copy.
	 */
	public static double[] clone(double x[]) { return new double[] {x[0], x[1], x[2]}; }

	public static double[] normalize(double x[], boolean clone) {
		double l = VectorMath.length(x);
		
		if (clone) {
			return new double[] {x[0] / l, x[1] / l, x[2] / l};
		} else {
			x[0] = x[0] / l;
			x[1] = x[1] / l;
			x[2] = x[2] / l;
			return x;
		}
	}
	
	public static double length(double x[]) {
		return Math.sqrt(x[0] * x[0] + x[1] * x[1] + x[2] * x[2]);
	}
	
	public static double[] multiply(double x[], double k) { return multiply(x, k, false); }
	
	public static double[] multiply(double x[], double k, boolean clone) {
		if (clone) {
			double c[] = {x[0] * k, x[1] * k, x[2] * k};
			return c;
		} else {
			x[0] *= k;
			x[1] *= k;
			x[2] *= k;
			return x;
		}
	}
	
	/**
	 * Adds a k * times y to x and returns x.
	 * 
	 * @param x  Vector to add to.
	 * @param y  Vector to add.
	 * @param k  Scale factor for y vector.
	 * @return  The x vector after adding the scaled y vector.
	 */
	public static double[] addMultiple(double x[], double y[], double k) {
		x[0] = x[0] + k * y[0];
		x[1] = x[1] + k * y[1];
		x[2] = x[2] + k * y[2];
		return x;
	}
	
	public static double[] add(double x[], double y[]) {
		return new double[] {x[0] + y[0], x[1] + y[1], x[2] + y[2]};
	}
	
	public static double[] addTo(double x[], double y[]) {
		x[0] = x[0] + y[0];
		x[1] = x[1] + y[1];
		x[2] = x[2] + y[2];
		return x;
	}
	
	public static double[] subtract(double x[], double y[]) {
		return new double[] {x[0] - y[0], x[1] - y[1], x[2] - y[2]};
	}
	
	public static double distance(double x[], double y[]) {
		x[0] = x[0] - y[0];
		x[1] = x[1] - y[1];
		x[2] = x[2] - y[2];
		
		return Math.sqrt(x[0] * x[0] + x[1] * x[1] + x[2] * x[2]);
	}
}
