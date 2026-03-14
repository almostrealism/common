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

/**
 * Deprecated utility class providing static methods for 3D vector operations on double arrays.
 *
 * <p>
 * {@link VectorMath} provides basic vector arithmetic operations for 3-element double arrays
 * representing 3D vectors. All methods assume vectors are in the form {x, y, z}.
 * </p>
 *
 * <h2>Immutable vs Mutable Operations</h2>
 * <ul>
 *   <li>{@link #add(double[], double[])} - creates new vector (immutable)</li>
 *   <li>{@link #addTo(double[], double[])} - modifies first vector (mutable)</li>
 *   <li>{@link #multiply(double[], double, boolean)} - optionally creates new or modifies</li>
 * </ul>
 *
 * @deprecated This class is deprecated and will be removed in a future version.
 *             Use {@link Vector} and {@link VectorFeatures} for hardware-accelerated
 *             vector operations instead.
 * @author  Michael Murray
 * @see Vector
 * @see VectorFeatures
 */
@Deprecated
public class VectorMath {

	/**
	 * Private constructor to prevent instantiation.
	 * This is a utility class with only static methods.
	 */
	private VectorMath() {}

	/**
	 * Creates a copy of the specified 3D vector.
	 *
	 * @param x  the vector to copy {x, y, z}
	 * @return a new vector with the same components
	 */
	public static double[] clone(double[] x) { return new double[] {x[0], x[1], x[2]}; }

	/**
	 * Computes the length (magnitude) of a 3D vector: ||v|| = sqrt(x^2 + y^2 + z^2).
	 *
	 * @param x  the vector {x, y, z}
	 * @return the length of the vector
	 */
	public static double length(double[] x) {
		return Math.sqrt(x[0] * x[0] + x[1] * x[1] + x[2] * x[2]);
	}

	/**
	 * Multiplies a vector by a scalar, modifying the original vector.
	 * Equivalent to calling {@link #multiply(double[], double, boolean)} with clone=false.
	 *
	 * @param x  the vector to multiply {x, y, z}
	 * @param k  the scalar multiplier
	 * @return the modified vector
	 */
	public static double[] multiply(double[] x, double k) { return multiply(x, k, false); }

	/**
	 * Multiplies a vector by a scalar, optionally creating a new vector.
	 *
	 * @param x  the vector to multiply {x, y, z}
	 * @param k  the scalar multiplier
	 * @param clone  if true, creates a new vector; if false, modifies the original
	 * @return the result vector (either new or the modified original)
	 */
	public static double[] multiply(double[] x, double k, boolean clone) {
		if (clone) {
			double[] c = {x[0] * k, x[1] * k, x[2] * k};
			return c;
		} else {
			x[0] *= k;
			x[1] *= k;
			x[2] *= k;
			return x;
		}
	}

	/**
	 * Adds a scaled vector to another vector in-place: x = x + k * y.
	 * This modifies the first vector.
	 *
	 * @param x  the vector to modify {x, y, z}
	 * @param y  the vector to add {x, y, z}
	 * @param k  the scale factor for y
	 * @return the modified x vector
	 */
	public static double[] addMultiple(double[] x, double[] y, double k) {
		x[0] = x[0] + k * y[0];
		x[1] = x[1] + k * y[1];
		x[2] = x[2] + k * y[2];
		return x;
	}

	/**
	 * Adds two vectors, creating a new result vector (immutable operation).
	 *
	 * @param x  the first vector {x, y, z}
	 * @param y  the second vector {x, y, z}
	 * @return a new vector containing x + y
	 */
	public static double[] add(double[] x, double[] y) {
		return new double[] {x[0] + y[0], x[1] + y[1], x[2] + y[2]};
	}

	/**
	 * Adds a vector to another in-place: x = x + y (mutable operation).
	 * This modifies the first vector.
	 *
	 * @param x  the vector to modify {x, y, z}
	 * @param y  the vector to add {x, y, z}
	 * @return the modified x vector
	 */
	public static double[] addTo(double[] x, double[] y) {
		x[0] = x[0] + y[0];
		x[1] = x[1] + y[1];
		x[2] = x[2] + y[2];
		return x;
	}

	/**
	 * Subtracts one vector from another, creating a new result vector.
	 *
	 * @param x  the first vector {x, y, z}
	 * @param y  the second vector {x, y, z}
	 * @return a new vector containing x - y
	 */
	public static double[] subtract(double[] x, double[] y) {
		return new double[] {x[0] - y[0], x[1] - y[1], x[2] - y[2]};
	}

	/**
	 * Computes the Euclidean distance between two vectors.
	 *
	 * <p>
	 * <b>WARNING:</b> This method modifies the first vector as a side effect.
	 * After calling this method, x will contain (x - y).
	 * </p>
	 *
	 * @param x  the first vector {x, y, z} (will be modified)
	 * @param y  the second vector {x, y, z}
	 * @return the distance between x and y: ||x - y||
	 */
	public static double distance(double[] x, double[] y) {
		x[0] = x[0] - y[0];
		x[1] = x[1] - y[1];
		x[2] = x[2] - y[2];

		return Math.sqrt(x[0] * x[0] + x[1] * x[1] + x[2] * x[2]);
	}
}
