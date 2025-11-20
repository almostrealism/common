/*
 * Copyright 2025 Michael Murray
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

import io.almostrealism.relation.Producer;
import io.almostrealism.util.NumberFormats;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.hardware.DynamicProducerForMemoryData;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.hardware.mem.Heap;
import org.almostrealism.io.Console;

import java.util.function.BiFunction;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * A hardware-accelerated 3D vector with x, y, z coordinates stored contiguously in memory.
 *
 * <p>
 * {@link Vector} represents a three-dimensional vector backed by {@link MemoryData}, enabling
 * hardware-accelerated vector operations through the computation graph framework. Vectors are
 * fundamental to 3D graphics, physics simulations, geometry processing, and spatial computations.
 * </p>
 *
 * <h2>Memory Layout</h2>
 * <p>
 * A Vector occupies 3 memory positions storing coordinates in order:
 * </p>
 * <ul>
 *   <li><strong>Position 0</strong>: X coordinate</li>
 *   <li><strong>Position 1</strong>: Y coordinate</li>
 *   <li><strong>Position 2</strong>: Z coordinate</li>
 * </ul>
 *
 * <h2>Coordinate Systems</h2>
 * <p>
 * Vectors support both Cartesian and spherical coordinate initialization:
 * </p>
 * <ul>
 *   <li><strong>Cartesian</strong>: Standard (x, y, z) coordinates</li>
 *   <li><strong>Spherical</strong>: (r, theta, phi) where r = distance from origin, theta = angle from Z axis, phi = angle from XY plane</li>
 * </ul>
 *
 * <h2>Usage Examples</h2>
 *
 * <h3>Creating Vectors</h3>
 * <pre>{@code
 * // Cartesian coordinates
 * Vector v1 = new Vector(1.0, 2.0, 3.0);
 *
 * // Spherical coordinates (r=1, theta=pi/4, phi=pi/6)
 * Vector v2 = new Vector(1.0, Math.PI/4, Math.PI/6, Vector.SPHERICAL_COORDINATES);
 *
 * // Copy constructor
 * Vector v3 = new Vector(v1);
 *
 * // From array
 * Vector v4 = new Vector(new double[]{1, 2, 3});
 * }</pre>
 *
 * <h3>Vector Operations</h3>
 * <pre>{@code
 * Vector a = new Vector(1, 0, 0);
 * Vector b = new Vector(0, 1, 0);
 *
 * // Immutable operations (return new Vector)
 * Vector sum = a.add(b);              // (1, 1, 0)
 * Vector diff = a.subtract(b);        // (1, -1, 0)
 * Vector scaled = a.multiply(5.0);    // (5, 0, 0)
 * Vector cross = a.crossProduct(b);   // (0, 0, 1)
 *
 * // Mutable operations (modify in place)
 * a.addTo(b);          // a becomes (1, 1, 0)
 * a.normalize();       // a becomes unit vector
 *
 * // Scalar products
 * double dot = a.dotProduct(b);  // 0.0
 * }</pre>
 *
 * <h3>Creating Vector Collections</h3>
 * <pre>{@code
 * // Bank of 100 vectors
 * PackedCollection<Vector> vectors = Vector.bank(100);
 *
 * // Access individual vectors
 * Vector first = vectors.get(0);
 * first.setX(1.0);
 * first.setY(2.0);
 * first.setZ(3.0);
 *
 * // Table of vectors (2D array)
 * PackedCollection<PackedCollection<Vector>> table = Vector.table(10, 100);
 * }</pre>
 *
 * <h3>Hardware-Accelerated Computations</h3>
 * <pre>{@code
 * // Using VectorFeatures for computation graph operations
 * Producer<Vector> a = vector(1.0, 2.0, 3.0);
 * Producer<Vector> b = vector(4.0, 5.0, 6.0);
 *
 * // Hardware-accelerated cross product
 * Producer<Vector> cross = crossProduct(a, b);
 * Vector result = cross.get().evaluate();
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 * <p>
 * Some mutating operations (add, subtract, multiply, divide, dotProduct) are synchronized
 * for thread safety. However, for high-performance concurrent vector operations, prefer
 * using immutable computation graph operations via {@link VectorFeatures}.
 * </p>
 *
 * @author  Michael Murray
 * @see PackedCollection
 * @see VectorFeatures
 * @see MemoryData
 */
public class Vector extends PackedCollection<Vector> implements VectorFeatures, Cloneable {
	public static final int CARTESIAN_COORDINATES = 0;
	public static final int SPHERICAL_COORDINATES = 1;

	/**
	 * Constructs a new {@link Vector} with coordinates at the origin (0, 0, 0).
	 */
	public Vector() {
		super(3);
	}

	/**
	 * Constructs a new {@link Vector} backed by the specified {@link MemoryData} at the given offset.
	 * This constructor is used internally for creating vectors within {@link PackedCollection}s.
	 *
	 * @param delegate        the memory data to use as backing storage
	 * @param delegateOffset  the offset within the delegate where this vector's data begins
	 */
	public Vector(MemoryData delegate, int delegateOffset) {
		super(new TraversalPolicy(3), 0, delegate, delegateOffset);
	}

	/**
	 * Constructs a new {@link Vector} with the same coordinates as the specified {@link Vector}.
	 * This is a copy constructor that creates an independent copy of the vector data.
	 *
	 * @param v  the vector to copy
	 */
	public Vector(Vector v) {
		this();
		setMem(v.toArray(), 0); // TODO  Directly copy mem (offset needs to be known though)
	}

	/**
	 * Constructs a new {@link Vector} using the specified coordinates and coordinate system.
	 *
	 * <p>
	 * For Cartesian coordinates (coordSys = {@link #CARTESIAN_COORDINATES}):
	 * The parameters represent standard (x, y, z) coordinates.
	 * </p>
	 *
	 * <p>
	 * For spherical coordinates (coordSys = {@link #SPHERICAL_COORDINATES}):
	 * </p>
	 * <ul>
	 *   <li>x = r (distance from origin)</li>
	 *   <li>y = theta (theta, angle from positive Z axis in radians)</li>
	 *   <li>z = phi (phi, angle from positive XY plane in radians)</li>
	 * </ul>
	 * <p>
	 * Spherical coordinates are converted to Cartesian using:
	 * x = r.sin(theta).cos(phi), y = r.sin(theta).sin(phi), z = r.cos(theta)
	 * </p>
	 *
	 * @param x        Cartesian X coordinate or spherical distance from origin
	 * @param y        Cartesian Y coordinate or spherical angle from positive Z axis (radians)
	 * @param z        Cartesian Z coordinate or spherical angle from positive XY plane (radians)
	 * @param coordSys {@link #CARTESIAN_COORDINATES} or {@link #SPHERICAL_COORDINATES}
	 * @throws IllegalArgumentException if coordSys is not a valid coordinate system type
	 */
	public Vector(double x, double y, double z, int coordSys) {
		this();

		if (coordSys == Vector.CARTESIAN_COORDINATES) {
			setMem(x, y, z);
		} else if (coordSys == Vector.SPHERICAL_COORDINATES) {
			setMem(x * Math.sin(y) * Math.cos(z),
					x * Math.sin(y) * Math.sin(z),
					x * Math.cos(y));
		} else {
			throw new IllegalArgumentException(coordSys + " is not a valid coordinate system type code");
		}
	}

	/**
	 * Constructs a new {@link Vector} using the specified Cartesian coordinates.
	 *
	 * @param x  the X coordinate
	 * @param y  the Y coordinate
	 * @param z  the Z coordinate
	 */
	public Vector(double x, double y, double z) {
		this(x, y, z, Vector.CARTESIAN_COORDINATES);
	}

	/**
	 * Constructs a new {@link Vector} from a double array containing three coordinates.
	 *
	 * @param v  the array containing [x, y, z] coordinates (must have length >= 3)
	 */
	public Vector(double v[]) {
		this(v[0], v[1], v[2]);
	}

	/**
	 * Constructs a new {@link Vector} from a float array containing three coordinates.
	 *
	 * @param v  the array containing [x, y, z] coordinates (must have length >= 3)
	 */
	public Vector(float v[]) {
		this(v[0], v[1], v[2]);
	}

	/**
	 * Generates a random vector uniformly distributed on the unit sphere.
	 * Uses spherical coordinates with random angles.
	 *
	 * @return a random unit vector
	 * @deprecated This method is deprecated and will be removed in a future version.
	 *             Use computation graph operations for random vector generation instead.
	 */
	@Deprecated
	public static Vector uniformSphericalRandom() {
		return new Vector(1.0, 2 * Math.PI * Math.random(), 2 * Math.PI * Math.random());
	}

	/**
	 * Returns the vector coordinates as a double array.
	 *
	 * @return a new array containing [x, y, z]
	 * @deprecated Use {@link #toArray()} instead.
	 */
	@Deprecated
	public double[] getData() {
		return new double[] { getX(), getY(), getZ() };
	}

	/**
	 * Sets the X coordinate of this {@link Vector}.
	 *
	 * @param x  the new X coordinate
	 * @deprecated Prefer using {@link #setMem(double, double, double)} to set all coordinates at once,
	 *             or use computation graph operations for better performance.
	 */
	@Deprecated
	public void setX(double x) {
		this.setMem(0, new Vector(x, 0, 0),0,1);
	}

	/**
	 * Sets the Y coordinate of this {@link Vector}.
	 *
	 * @param y  the new Y coordinate
	 * @deprecated Prefer using {@link #setMem(double, double, double)} to set all coordinates at once,
	 *             or use computation graph operations for better performance.
	 */
	@Deprecated
	public void setY(double y) {
		this.setMem(1, new Vector(0, y, 0),1,1);
	}

	/**
	 * Sets the Z coordinate of this {@link Vector}.
	 *
	 * @param z  the new Z coordinate
	 * @deprecated Prefer using {@link #setMem(double, double, double)} to set all coordinates at once,
	 *             or use computation graph operations for better performance.
	 */
	@Deprecated
	public void setZ(double z) {
		this.setMem(2, new Vector(0, 0, z),2,1);
	}

	/**
	 * Returns the X coordinate of this {@link Vector}.
	 *
	 * @return the X coordinate
	 */
	public double getX() {
		return toArray()[0];
	}

	/**
	 * Returns the Y coordinate of this {@link Vector}.
	 *
	 * @return the Y coordinate
	 */
	public double getY() {
		return toArray()[1];
	}

	/**
	 * Returns the Z coordinate of this {@link Vector}.
	 *
	 * @return the Z coordinate
	 */
	public double getZ() {
		return toArray()[2];
	}

	/**
	 * Sets the i-th component of this {@link Vector}, where 0 <= i < 3.
	 *
	 * @param i  the component index (0=X, 1=Y, 2=Z)
	 * @param v  the value to set
	 * @return this {@link Vector} for method chaining
	 * @throws IndexOutOfBoundsException if i is not in the range [0, 2]
	 * @deprecated Use {@link #setMem(double, double, double)} or computation graph operations instead.
	 */
	@Deprecated
	public Vector set(int i, double v) {
		switch (i) {
			case 0:
				setX(v);
				break;
			case 1:
				setY(v);
				break;
			case 2:
				setZ(v);
				break;
			default:
				throw new IndexOutOfBoundsException();
		}

		return this;
	}

	/**
	 * Sets the coordinates of this {@link Vector} to the coordinates of the
	 * specified {@link Vector}.
	 *
	 * @param v The Vector to set coordinates from.
	 * @return This Vector.
	 */
	public Vector setTo(Vector v) {
		if (v.getMem().getProvider() == this.getMem().getProvider()) {
			setMem(0, v, 0, 3);
		} else {
			setMem(v.toArray(), 0);
		}

		return this;
	}

	/**
	 * Returns the negation of this {@link Vector} (opposite direction).
	 * Creates a new vector with all components negated.
	 *
	 * @return a new {@link Vector} representing -this
	 */
	public Vector minus() {
		double a[] = toArray();
		return new Vector(-a[0], -a[1], -a[2]);
	}

	/**
	 * Returns the sum of this {@link Vector} and the specified {@link Vector}.
	 * This is an immutable operation that creates a new vector.
	 *
	 * @param vector  the vector to add
	 * @return a new {@link Vector} representing this + vector
	 * @see #addTo(Vector)
	 */
	public synchronized Vector add(Vector vector) {
		Vector v = clone();
		v.addTo(vector);
		return v;
	}

	/**
	 * Adds the specified {@link Vector} to this {@link Vector} in place.
	 * This is a mutable operation that modifies this vector.
	 *
	 * @param vector  the vector to add
	 * @see #add(Vector)
	 */
	public void addTo(Vector vector) {
		double a[] = toArray();
		double b[] = vector.toArray();
		setMem(a[0] + b[0], a[1] + b[1], a[2] + b[2]);
	}

	/**
	 * Returns the difference between this {@link Vector} and the specified {@link Vector}.
	 * This is an immutable operation that creates a new vector.
	 * The specified vector is subtracted from this one.
	 *
	 * @param vector  the vector to subtract
	 * @return a new {@link Vector} representing this - vector
	 * @see #subtractFrom(Vector)
	 */
	public Vector subtract(Vector vector) {
		Vector v = clone();
		v.subtractFrom(vector);
		return v;
	}

	/**
	 * Subtracts the specified {@link Vector} from this {@link Vector} in place.
	 * This is a mutable operation that modifies this vector.
	 *
	 * @param vector  the vector to subtract
	 * @see #subtract(Vector)
	 */
	public synchronized void subtractFrom(Vector vector) {
		double a[] = toArray();
		double b[] = vector.toArray();
		setMem(a[0] - b[0], a[1] - b[1], a[2] - b[2]);
	}

	/**
	 * Returns this {@link Vector} multiplied by the specified scalar value.
	 * This is an immutable operation that creates a new vector.
	 *
	 * @param value  the scalar to multiply by
	 * @return a new {@link Vector} representing this * value
	 * @see #multiplyBy(double)
	 */
	public Vector multiply(double value) {
		Vector v = clone();
		v.multiplyBy(value);
		return v;
	}

	/**
	 * Multiplies this {@link Vector} by the specified scalar value in place.
	 * This is a mutable operation that modifies this vector.
	 *
	 * @param value  the scalar to multiply by
	 * @see #multiply(double)
	 */
	public synchronized void multiplyBy(double value) {
		double a[] = toArray();
		setMem(a[0] * value, a[1] * value, a[2] * value);
	}

	/**
	 * Returns this {@link Vector} divided by the specified scalar value.
	 * This is an immutable operation that creates a new vector.
	 *
	 * @param value  the scalar to divide by
	 * @return a new {@link Vector} representing this / value
	 * @see #divideBy(double)
	 */
	public Vector divide(double value) {
		return clone().divideBy(value);
	}

	/**
	 * Divides this {@link Vector} by the specified scalar value in place.
	 * This is a mutable operation that modifies this vector.
	 *
	 * @param value  the scalar to divide by
	 * @return this {@link Vector} for method chaining
	 * @see #divide(double)
	 */
	public synchronized Vector divideBy(double value) {
		double a[] = toArray();
		setMem(a[0] / value, a[1] / value, a[2] / value);
		return this;
	}

	/**
	 * Computes the dot product (scalar product) of this {@link Vector} and the specified {@link Vector}.
	 * The dot product is calculated as: x1*x2 + y1*y2 + z1*z2
	 *
	 * <p>
	 * This method uses hardware-accelerated computation. For maximum performance with
	 * multiple operations, use {@link VectorFeatures#dotProduct(Producer, Producer)}.
	 * </p>
	 *
	 * @param vector  the vector to compute the dot product with
	 * @return the dot product value
	 */
	public synchronized double dotProduct(Vector vector) {
		return dotProduct(v(this), v(vector)).evaluate().toDouble();
	}

	/**
	 * Computes the cross product of this {@link Vector} and the specified {@link Vector}.
	 * The cross product is a vector perpendicular to both input vectors.
	 *
	 * <p>
	 * The cross product is calculated as:
	 * </p>
	 * <ul>
	 *   <li>x = this.y * v.z - this.z * v.y</li>
	 *   <li>y = this.z * v.x - this.x * v.z</li>
	 *   <li>z = this.x * v.y - this.y * v.x</li>
	 * </ul>
	 *
	 * @param v  the vector to compute the cross product with
	 * @return a new {@link Vector} representing this x v
	 */
	public Vector crossProduct(Vector v) {
		double x = getY() * v.getZ() - getZ() * v.getY();
		double y = getZ() * v.getX() - getX() * v.getZ();
		double z = getX() * v.getY() - getY() * v.getX();
		return new Vector(x, y, z);
	}

	/**
	 * Converts this {@link Vector} to a float array.
	 *
	 * @return a new float array containing [x, y, z]
	 */
	public float[] toFloat() {
		double d[] = toArray();
		return new float[] { (float) d[0], (float) d[1], (float) d[2] };
	}

	/**
	 * Normalizes this {@link Vector} in place to unit length (magnitude = 1).
	 * This modifies the vector to point in the same direction with length 1.
	 *
	 * <p>
	 * Uses hardware-accelerated computation via {@link VectorFeatures#normalize(Producer)}.
	 * </p>
	 */
	public void normalize() {
		normalize(cp(this)).into(this).evaluate();
	}

	/**
	 * Returns the coordinates of this {@link Vector} as a double array.
	 * This is the fastest way to access the raw data.
	 *
	 * @return a new double array containing [x, y, z]
	 */
	public double[] toArray() {
		return getMem().toArray(getOffset(), 3);
	}

	/**
	 * Returns a formatted string representation of this {@link Vector}.
	 *
	 * @return a string in the format "[x, y, z]" with formatted numbers
	 */
	public String describe() {
		StringBuffer value = new StringBuffer();

		value.append("[");
		value.append(NumberFormats.formatNumber(getX()));
		value.append(", ");
		value.append(NumberFormats.formatNumber(getY()));
		value.append(", ");
		value.append(NumberFormats.formatNumber(getZ()));
		value.append("]");


		return value.toString();
	}

	/**
	 * Returns an integer hash code value for this {@link Vector}.
	 * The hash code is computed by summing all three components and casting to int.
	 *
	 * <p>
	 * Note: This hash code implementation may produce collisions for vectors with
	 * different coordinates that sum to the same value.
	 * </p>
	 *
	 * @return the hash code value
	 */
	@Override
	public int hashCode() {
		double value = this.getX() + this.getY() + this.getZ();

		return (int) value;
	}

	/**
	 * Tests whether this {@link Vector} is geometrically equal to the specified object.
	 * Returns true if and only if the specified object is a {@link Vector} with
	 * identical x, y, and z coordinates.
	 *
	 * @param obj  the object to compare with
	 * @return true if the vectors are equal, false otherwise
	 */
	@Override
	public boolean equals(Object obj) {
		if (!(obj instanceof Vector))
			return false;

		Vector vector = (Vector) obj;

		if (vector.getX() == this.getX() && vector.getY() == this.getY() && vector.getZ() == this.getZ())
			return true;
		else
			return false;
	}

	/**
	 * Returns the memory length required for a {@link Vector}.
	 *
	 * @return 3 (for x, y, z coordinates)
	 */
	@Override
	public int getMemLength() { return 3; }

	/**
	 * Returns the default memory delegate for {@link Vector} instances.
	 *
	 * @return the default {@link Heap}
	 */
	@Override
	public Heap getDefaultDelegate() { return Heap.getDefault(); }

	/**
	 * Creates and returns a deep copy of this {@link Vector}.
	 * The cloned vector is independent and modifications to it will not affect this vector.
	 *
	 * @return a new {@link Vector} with the same coordinates
	 * @see #setTo(Vector)
	 */
	@Override
	public Vector clone() {
		Vector v = new Vector();
		v.setTo(this);
		return v;
	}

	/**
	 * Returns a string representation of this {@link Vector}.
	 *
	 * @return a formatted string in the form "[x, y, z]"
	 * @see #describe()
	 */
	@Override
	public String toString() { return describe(); }

	/**
	 * Returns the standard {@link TraversalPolicy} for a {@link Vector}.
	 * A vector has a shape of [3], representing x, y, z coordinates.
	 *
	 * @return the traversal policy for vectors
	 */
	public static TraversalPolicy shape() {
		return new TraversalPolicy(3);
	}

	/**
	 * Creates a dynamic {@link Producer} that generates blank {@link Vector}s.
	 * Used internally by the computation graph framework for allocating output vectors.
	 *
	 * @return a producer that creates new vectors
	 */
	public static Producer<Vector> blank() {
		Supplier<Vector> s = Vector::new;
		IntFunction<MemoryBank<Vector>> b = Vector::bank;
		return new DynamicProducerForMemoryData<>(s, b);
	}

	/**
	 * Creates a {@link PackedCollection} containing the specified number of {@link Vector}s.
	 * Each vector in the bank can be accessed and modified independently.
	 *
	 * <pre>{@code
	 * PackedCollection<Vector> vectors = Vector.bank(100);
	 * vectors.get(0).setMem(1.0, 2.0, 3.0);
	 * }</pre>
	 *
	 * @param count  the number of vectors to allocate
	 * @return a packed collection of vectors
	 */
	public static PackedCollection<Vector> bank(int count) {
		return new PackedCollection<>(new TraversalPolicy(count, 3), 1, delegateSpec ->
				new Vector(delegateSpec.getDelegate(), delegateSpec.getOffset()));
	}

	/**
	 * Creates a {@link PackedCollection} of {@link Vector}s backed by the specified {@link MemoryData}.
	 *
	 * @param count           the number of vectors to allocate
	 * @param delegate        the memory data to use as backing storage
	 * @param delegateOffset  the offset within the delegate where the vector bank begins
	 * @return a packed collection of vectors
	 */
	public static PackedCollection<Vector> bank(int count, MemoryData delegate, int delegateOffset) {
		return new PackedCollection<>(new TraversalPolicy(count, 3), 1, delegateSpec ->
				new Vector(delegateSpec.getDelegate(), delegateSpec.getOffset()),
				delegate, delegateOffset);
	}

	/**
	 * Creates a 2D table of {@link Vector}s as a {@link PackedCollection} of {@link PackedCollection}s.
	 * This creates a matrix-like structure where each row contains multiple vectors.
	 *
	 * @param width  the number of vectors in each row
	 * @param count  the number of rows
	 * @return a 2D collection of vectors
	 */
	public static PackedCollection<PackedCollection<Vector>> table(int width, int count) {
		return new PackedCollection<>(new TraversalPolicy(count, width, 3), 1, delegateSpec ->
				Vector.bank(width, delegateSpec.getDelegate(), delegateSpec.getOffset()));
	}

	/**
	 * Creates a 2D table of {@link Vector}s backed by the specified {@link MemoryData}.
	 *
	 * @param width           the number of vectors in each row
	 * @param count           the number of rows
	 * @param delegate        the memory data to use as backing storage
	 * @param delegateOffset  the offset within the delegate where the table begins
	 * @return a 2D collection of vectors
	 */
	public static PackedCollection<PackedCollection<Vector>> table(int width, int count, MemoryData delegate, int delegateOffset) {
		return new PackedCollection<>(new TraversalPolicy(count, width, 3), 1, delegateSpec ->
					Vector.bank(width, delegateSpec.getDelegate(), delegateSpec.getOffset()),
				delegate, delegateOffset);
	}

	/**
	 * Returns a postprocessor function that creates {@link Vector}s from {@link MemoryData}.
	 * Used internally by the computation framework to wrap output memory in Vector objects.
	 *
	 * @return a function that creates vectors from memory data and offset
	 */
	public static BiFunction<MemoryData, Integer, Vector> postprocessor() {
		return (output, offset) -> new Vector(output, offset);
	}

	/**
	 * Subtracts vector v2 from v1 and stores the result in dest.
	 * Performs component-wise subtraction: dest = v1 - v2
	 *
	 * @param dest  the destination vector to store the result
	 * @param v1    the first vector (minuend)
	 * @param v2    the second vector (subtrahend)
	 */
	public static void vector3Sub(Vector dest, Vector v1, Vector v2) {
		dest.setX(v1.getX() - v2.getX());
		dest.setY(v1.getY() - v2.getY());
		dest.setZ(v1.getZ() - v2.getZ());
	}

	/**
	 * Adds two vectors and stores the result in dest.
	 * Performs component-wise addition: dest = v1 + v2
	 *
	 * @param dest  the destination vector to store the result
	 * @param v1    the first vector
	 * @param v2    the second vector
	 */
	public static void add(Vector dest, Vector v1, Vector v2) {
		dest.setX(v1.getX() + v2.getX());
		dest.setY(v1.getY() + v2.getY());
		dest.setZ(v1.getZ() + v2.getZ());
	}

	/**
	 * Adds three vectors and stores the result in dest.
	 * Performs component-wise addition: dest = v1 + v2 + v3
	 *
	 * @param dest  the destination vector to store the result
	 * @param v1    the first vector
	 * @param v2    the second vector
	 * @param v3    the third vector
	 */
	public static void add(Vector dest, Vector v1, Vector v2, Vector v3) {
		dest.setX(v1.getX() + v2.getX() + v3.getX());
		dest.setY(v1.getY() + v2.getY() + v3.getY());
		dest.setZ(v1.getZ() + v2.getZ() + v3.getZ());
	}

	/**
	 * Adds four vectors and stores the result in dest.
	 * Performs component-wise addition: dest = v1 + v2 + v3 + v4
	 *
	 * @param dest  the destination vector to store the result
	 * @param v1    the first vector
	 * @param v2    the second vector
	 * @param v3    the third vector
	 * @param v4    the fourth vector
	 */
	public static void add(Vector dest, Vector v1, Vector v2, Vector v3, Vector v4) {
		dest.setX(v1.getX() + v2.getX() + v3.getX() + v4.getX());
		dest.setY(v1.getY() + v2.getY() + v3.getY() + v4.getY());
		dest.setZ(v1.getZ() + v2.getZ() + v3.getZ() + v4.getZ());
	}

	/**
	 * Performs component-wise multiplication of two vectors and stores the result in dest.
	 * This is the Hadamard product: dest = v1 O v2
	 *
	 * @param dest  the destination vector to store the result
	 * @param v1    the first vector
	 * @param v2    the second vector
	 */
	public static void mul(Vector dest, Vector v1, Vector v2) {
		dest.setX(v1.getX() * v2.getX());
		dest.setY(v1.getY() * v2.getY());
		dest.setZ(v1.getZ() * v2.getZ());
	}

	/**
	 * Performs component-wise division of v1 by v2 and stores the result in dest.
	 *
	 * @param dest  the destination vector to store the result
	 * @param v1    the dividend vector
	 * @param v2    the divisor vector
	 */
	public static void div(Vector dest, Vector v1, Vector v2) {
		dest.setX(v1.getX() / v2.getX());
		dest.setY(v1.getY() / v2.getY());
		dest.setZ(v1.getZ() / v2.getZ());
	}

	/**
	 * Sets vector a to the component-wise minimum of a and b.
	 * Each component of a is set to the minimum of that component in a and b.
	 *
	 * @param a  the vector to modify (becomes min(a, b))
	 * @param b  the vector to compare against
	 */
	public static void setMin(Vector a, Vector b) {
		a.setX(Math.min(a.getX(), b.getX()));
		a.setY(Math.min(a.getY(), b.getY()));
		a.setZ(Math.min(a.getZ(), b.getZ()));
	}

	/**
	 * Sets vector a to the component-wise maximum of a and b.
	 * Each component of a is set to the maximum of that component in a and b.
	 *
	 * @param a  the vector to modify (becomes max(a, b))
	 * @param b  the vector to compare against
	 */
	public static void setMax(Vector a, Vector b) {
		a.setX(Math.max(a.getX(), b.getX()));
		a.setY(Math.max(a.getY(), b.getY()));
		a.setZ(Math.max(a.getZ(), b.getZ()));
	}

	/**
	 * Returns a unit vector along the positive X axis.
	 *
	 * @return a new {@link Vector} (1, 0, 0)
	 */
	public static Vector xAxis() { return new Vector(1, 0, 0); }

	/**
	 * Returns a unit vector along the positive Y axis.
	 *
	 * @return a new {@link Vector} (0, 1, 0)
	 */
	public static Vector yAxis() { return new Vector(0, 1, 0); }

	/**
	 * Returns a unit vector along the positive Z axis.
	 *
	 * @return a new {@link Vector} (0, 0, 1)
	 */
	public static Vector zAxis() { return new Vector(0, 0, 1); }

	/**
	 * Returns a unit vector along the negative X axis.
	 *
	 * @return a new {@link Vector} (-1, 0, 0)
	 */
	public static Vector negXAxis() { return new Vector(-1, 0, 0); }

	/**
	 * Returns a unit vector along the negative Y axis.
	 *
	 * @return a new {@link Vector} (0, -1, 0)
	 */
	public static Vector negYAxis() { return new Vector(0, -1, 0); }

	/**
	 * Returns a unit vector along the negative Z axis.
	 *
	 * @return a new {@link Vector} (0, 0, -1)
	 */
	public static Vector negZAxis() { return new Vector(0, 0, -1); }
}
