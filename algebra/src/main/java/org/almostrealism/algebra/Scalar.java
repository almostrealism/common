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

package org.almostrealism.algebra;

import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.hardware.MemoryData;

import java.util.function.BiFunction;
import java.util.function.DoubleFunction;
import java.util.function.IntFunction;

/**
 * A hardware-accelerated scalar value with an associated certainty measure.
 *
 * <p>
 * {@link Scalar} represents a single floating-point value paired with a certainty coefficient,
 * stored as a {@link Pair} of two double values. This design allows scalar computations to
 * propagate uncertainty through mathematical operations, useful for probabilistic computations,
 * sensor fusion, and machine learning applications.
 * </p>
 *
 * <h2>Memory Layout</h2>
 * <p>
 * A Scalar occupies 2 memory positions:
 * </p>
 * <ul>
 *   <li><strong>Position 0</strong>: The scalar value</li>
 *   <li><strong>Position 1</strong>: The certainty coefficient (0.0 to 1.0)</li>
 * </ul>
 *
 * <h2>Usage Examples</h2>
 *
 * <h3>Creating Scalars</h3>
 * <pre>{@code
 * // Simple scalar with full certainty
 * Scalar s1 = new Scalar(5.0);
 *
 * // Scalar with explicit certainty
 * Scalar s2 = new Scalar(3.14, 0.95);
 *
 * // From existing memory
 * Scalar s3 = new Scalar(memoryData, offset);
 * }</pre>
 *
 * <h3>Creating Scalar Collections</h3>
 * <pre>{@code
 * // Bank of 100 scalars
 * PackedCollection<Scalar> scalars = Scalar.scalarBank(100);
 *
 * // Access individual scalars
 * Scalar first = scalars.get(0);
 * first.setValue(42.0);
 * }</pre>
 *
 * <h3>Working with Uncertainty</h3>
 * <pre>{@code
 * Scalar measurement = new Scalar(10.5, 0.8);  // 80% certain
 * double value = measurement.getValue();       // 10.5
 * double confidence = measurement.getCertainty(); // 0.8
 * }</pre>
 *
 * @deprecated This class is deprecated for computational use. The certainty field is not
 *             widely used and having Scalar extend Pair causes Java generics covariance
 *             issues with batch processing. For computational purposes, use
 *             {@link PackedCollection} with size 1 instead. Scalar may still be used as
 *             a convenient wrapper for debugging and testing, but should not be used in
 *             Producer/Evaluable type parameters. Methods that previously used
 *             {@code Producer<Scalar>} should now use {@code Producer<PackedCollection<?>>}.
 *
 * @see Pair
 * @see PackedCollection
 * @see ScalarFeatures
 */
@Deprecated
public class Scalar extends Pair<Scalar> implements Comparable<Scalar> {

	private Scalar() { }

	@Override
	protected void init() {
		super.init();
		// TODO  Need to determine if certainty should be set to 1.0
		//       based on conditions such as whether we reserved a
		//       delegate from the pool
	}

	/**
	 * Sets the scalar value.
	 *
	 * @param v  the new value
	 * @return this {@link Scalar} for method chaining
	 */
	public Scalar setValue(double v) { setLeft(v); return this; }

	/**
	 * Sets the certainty coefficient for this scalar.
	 *
	 * @param c  the certainty value (typically 0.0 to 1.0, where 1.0 represents full certainty)
	 * @return this {@link Scalar} for method chaining
	 */
	public Scalar setCertainty(double c) { setRight(c); return this; }

	/**
	 * Returns the scalar value.
	 *
	 * @return the value stored in this scalar
	 */
	public double getValue() { return left(); }

	/**
	 * Returns the certainty coefficient for this scalar.
	 *
	 * @return the certainty value (0.0 to 1.0)
	 */
	public double getCertainty() { return right(); }

	/**
	 * Compares this scalar to another scalar based on their values.
	 * The comparison is normalized by the maximum absolute value to prevent overflow.
	 *
	 * @param s  the scalar to compare to
	 * @return a negative integer, zero, or a positive integer as this scalar's value
	 *         is less than, equal to, or greater than the specified scalar's value
	 */
	@Override
	public int compareTo(Scalar s) {
		double m = 2 * Math.max(Math.abs(getValue()), Math.abs(s.getValue()));
		return (int) ((this.getValue() - s.getValue() / m) * Integer.MAX_VALUE);
	}

	/**
	 * Returns a string description of this scalar including its shape and value.
	 *
	 * @return a string representation of this scalar
	 */
	@Override
	public String describe() {
		return getShape() + " " + toDouble(0);
	}

	/**
	 * Returns the standard {@link TraversalPolicy} for a {@link Scalar}.
	 * A scalar has a shape of [2], representing value and certainty.
	 *
	 * @return the traversal policy for scalars
	 */
	public static TraversalPolicy shape() {
		return new TraversalPolicy(2);
	}

	/**
	 * Converts an array of double values to float values.
	 *
	 * @param d  the double array to convert
	 * @return a float array with the same values
	 * @deprecated This utility method is deprecated and will be removed in a future version.
	 *             Use standard Java conversion or stream operations instead.
	 */
	@Deprecated
	public static float[] toFloat(double d[]) {
		float f[] = new float[d.length];
		for (int i = 0; i < d.length; i++) f[i] = (float) d[i];
		return f;
	}

	/**
	 * Computes a superformula function value at time t with default offset.
	 * This is a legacy function from earlier rendering code.
	 *
	 * @param t  the time parameter
	 * @param p  the parameter array
	 * @return the computed superformula value
	 * @deprecated This legacy superformula function is deprecated and will be removed.
	 *             Superformula computations should be implemented using the computation graph framework.
	 */
	@Deprecated
	public static float ssFunc(final double t, final float p[]) {
		return ssFunc(t, p, 0);
	}

	/**
	 * Computes a superformula function value at time t with the given parameter array offset.
	 * This implements a variation of the superformula for generating complex shapes.
	 *
	 * @param t     the time parameter
	 * @param p     the parameter array
	 * @param pOff  the offset within the parameter array
	 * @return the computed superformula value
	 * @deprecated This legacy superformula function is deprecated and will be removed.
	 *             Superformula computations should be implemented using the computation graph framework.
	 */
	@Deprecated
	public static float ssFunc(final double t, final float p[], int pOff) {
		return (float) (Math.pow(Math.pow(Math.abs(Math.cos(p[0 + pOff] * t / 4)) / p[1 + pOff], p[4 + pOff]) +
				Math.pow(Math.abs(Math.sin(p[0 + pOff] * t / 4)) / p[2 + pOff], p[5 + pOff]), 1 / p[3 + pOff]));
	}
}
