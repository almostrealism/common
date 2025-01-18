/*
 * Copyright 2024 Michael Murray
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
 * A {@link Vector} represents a 3d vector. It stores three coordinates, x, y, z
 * in a buffer that is contiguous in memory.
 *
 * @author  Michael Murray
 */
public class Vector extends PackedCollection<Vector> implements VectorFeatures, Cloneable {
	public static final int CARTESIAN_COORDINATES = 0;
	public static final int SPHERICAL_COORDINATES = 1;

	/** Constructs a {@link Vector} with coordinates at the origin. */
	public Vector() {
		super(3);
	}

	public Vector(MemoryData delegate, int delegateOffset) {
		super(new TraversalPolicy(3), 0, delegate, delegateOffset);
	}

	/** Constructs a {@link Vector} with the same coordinates as the specified {@link Vector}. */
	public Vector(Vector v) {
		this();
		setMem(v.toArray(), 0); // TODO  Directly copy mem (offset needs to be known though)
	}

	/**
	 * Constructs a new Vector object using the specified coordinates.
	 *
	 * @param x        Cartesian X coordinate or spherical distance from origin.
	 * @param y        Cartesian Y coordinate or angle from positive Z axis (rads).
	 * @param z        Cartesian Z coordinate or angle from positive XY plane (rads).
	 * @param coordSys Vector.CARTESIAN_COORDINATES or Vector.SPHERICAL_COORDINATES.
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
	 * Constructs a new Vector object using the specified cartesian coordinates.
	 */
	public Vector(double x, double y, double z) {
		this(x, y, z, Vector.CARTESIAN_COORDINATES);
	}

	public Vector(double v[]) {
		this(v[0], v[1], v[2]);
	}

	public Vector(float v[]) {
		this(v[0], v[1], v[2]);
	}

	/**
	 * @return A random vector uniformly distributed on the unit sphere as a Vector object.
	 */
	@Deprecated
	public static Vector uniformSphericalRandom() {
		return new Vector(1.0, 2 * Math.PI * Math.random(), 2 * Math.PI * Math.random());
	}

	@Deprecated
	public double[] getData() {
		return new double[] { getX(), getY(), getZ() };
	}

	/** Sets the X coordinate of this Vector object. */
	@Deprecated
	public void setX(double x) {
		this.setMem(0, new Vector(x, 0, 0),0,1);
	}

	/** Sets the Y coordinate of this Vector object. */
	@Deprecated
	public void setY(double y) {
		this.setMem(1, new Vector(0, y, 0),1,1);
	}

	/** Sets the Z coordinate of this Vector object. */
	@Deprecated
	public void setZ(double z) {
		this.setMem(2, new Vector(0, 0, z),2,1);
	}

	/** Returns the X coordinate of this {@link Vector}. */
	public double getX() {
		return toArray()[0];
	}

	/** Returns the Y coordinate of this {@link Vector}. */
	public double getY() {
		return toArray()[1];
	}

	/** Returns the Z coordinate of this {@link Vector}. */
	public double getZ() {
		return toArray()[2];
	}

	/** Sets the ith component, 0 <= i < 3 */
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
	 * Returns the opposite of the vector represented by this {@link Vector}.
	 */
	public Vector minus() {
		double a[] = toArray();
		return new Vector(-a[0], -a[1], -a[2]);
	}

	/** Returns the sum of this {@link Vector} and the specified {@link Vector}. */
	public synchronized Vector add(Vector vector) {
		Vector v = clone();
		v.addTo(vector);
		return v;
	}

	/**
	 * Adds the vector represented by the specified Vector object to this Vector object.
	 *
	 * @param vector The Vector object to add.
	 */
	public void addTo(Vector vector) {
		double a[] = toArray();
		double b[] = vector.toArray();
		setMem(a[0] + b[0], a[1] + b[1], a[2] + b[2]);
	}

	/**
	 * Returns the difference of the vector represented by this Vector object and that of the specified Vector object.
	 * The specified vector is subtracted from this one.
	 */
	public Vector subtract(Vector vector) {
		Vector v = clone();
		v.subtractFrom(vector);
		return v;
	}

	/**
	 * Subtracts the vector represented by the specified Vector object from this Vector object.
	 *
	 * @param vector The Vector object to be subtracted.
	 */
	public synchronized void subtractFrom(Vector vector) {
		double a[] = toArray();
		double b[] = vector.toArray();
		setMem(a[0] - b[0], a[1] - b[1], a[2] - b[2]);
	}

	/**
	 * Returns the product of the vector represented by this Vector object and the specified value.
	 */
	public Vector multiply(double value) {
		Vector v = clone();
		v.multiplyBy(value);
		return v;
	}

	/**
	 * Multiplies the vector represented by this Vector object by the specified double value.
	 *
	 * @param value The factor to multiply by.
	 */
	public synchronized void multiplyBy(double value) {
		double a[] = toArray();
		setMem(a[0] * value, a[1] * value, a[2] * value);
	}

	/** Returns the quotient of the division of this {@link Vector} by the specified value. */
	public Vector divide(double value) {
		return clone().divideBy(value);
	}

	/**
	 * Divides this {@link Vector} by the specified double value.
	 *
	 * @param value The value to divide by.
	 */
	public synchronized Vector divideBy(double value) {
		double a[] = toArray();
		setMem(a[0] / value, a[1] / value, a[2] / value);
		return this;
	}

	/**
	 * Returns the dot product of this {@link Vector} and the specified {@link Vector}.
	 */
	public synchronized double dotProduct(Vector vector) {
		return dotProduct(v(this), v(vector)).get().evaluate().getValue();
	}

	/** Returns the cross product of this {@link Vector} and that of the specified {@link Vector}. */
	public Vector crossProduct(Vector vector) {
		return crossProduct(v(this), v(vector)).get().evaluate();
	}

	public float[] toFloat() {
		double d[] = toArray();
		return new float[] { (float) d[0], (float) d[1], (float) d[2] };
	}

	public void normalize() {
		// TODO  This should leverage a reusable Evaluable that takes an argument
		normalize(v(this)).get().evaluate();
	}

	/** This is the fastest way to get access to the data in this {@link Vector}. */
	public double[] toArray() {
		return getMem().toArray(getOffset(), 3);
	}

	/** @return A String representation of this {@link Vector}. */
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
	 * Returns an integer hash code value for this Vector object obtained
	 * by adding all 3 components and casting to an int.
	 */
	@Override
	public int hashCode() {
		double value = this.getX() + this.getY() + this.getZ();

		return (int) value;
	}

	/**
	 * Returns true if and only if the object specified represents a 3d
	 * vector that is geometrically equal to the vector represented by
	 * this {@link Vector}.
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

	@Override
	public int getMemLength() { return 3; }

	@Override
	public Heap getDefaultDelegate() { return Heap.getDefault(); }

	/**
	 * @see #setTo(Vector)
	 * @see java.lang.Object#clone()
	 */
	@Override
	public Vector clone() {
		Vector v = new Vector();
		v.setTo(this);
		return v;
	}

	/**
	 * @see  #describe()
	 *
	 * @return A String representation of this {@link Vector}.
	 */
	@Override
	public String toString() { return describe(); }

	public static TraversalPolicy shape() {
		return new TraversalPolicy(3);
	}

	public static Producer<Vector> blank() {
		Supplier<Vector> s = Vector::new;
		IntFunction<MemoryBank<Vector>> b = Vector::bank;
		return new DynamicProducerForMemoryData<>(s, b);
	}

	public static PackedCollection<Vector> bank(int count) {
		return new PackedCollection<>(new TraversalPolicy(count, 3), 1, delegateSpec ->
				new Vector(delegateSpec.getDelegate(), delegateSpec.getOffset()));
	}

	public static PackedCollection<Vector> bank(int count, MemoryData delegate, int delegateOffset) {
		return new PackedCollection<>(new TraversalPolicy(count, 3), 1, delegateSpec ->
				new Vector(delegateSpec.getDelegate(), delegateSpec.getOffset()),
				delegate, delegateOffset);
	}

	public static PackedCollection<PackedCollection<Vector>> table(int width, int count) {
		return new PackedCollection<>(new TraversalPolicy(count, width, 3), 1, delegateSpec ->
				Vector.bank(width, delegateSpec.getDelegate(), delegateSpec.getOffset()));
	}

	public static PackedCollection<PackedCollection<Vector>> table(int width, int count, MemoryData delegate, int delegateOffset) {
		return new PackedCollection<>(new TraversalPolicy(count, width, 3), 1, delegateSpec ->
					Vector.bank(width, delegateSpec.getDelegate(), delegateSpec.getOffset()),
				delegate, delegateOffset);
	}

	public static BiFunction<MemoryData, Integer, Vector> postprocessor() {
		return (output, offset) -> new Vector(output, offset);
	}

	/**
	 * Returns null upon failure, or a set of {@link Vector}s and integers
	 * which represent faceted (non-averaged) normals, but per-vertex.
	 *
	 * Performs bounds checking on indices with respect to vertex list.
	 * Index list must represent independent triangles; indices are
	 * taken in groups of three. If index list doesn't represent
	 * triangles or other error occurred then returns null. ccw flag
	 * indicates whether triangles are specified counterclockwise when
	 * viewed from top or not.
	 */
	public static Normals computeFacetedNormals(Vector[] vertices,
												int[] indices,
												boolean ccw) {
		if ((indices.length % 3) != 0) {
			Console.root().features(Vector.class)
					.warn("computeFacetedNormals - numIndices wasn't " +
						"divisible by 3, so it can't possibly " +
						"represent a set of triangles");
			return null;
		}

		Vector[] outputNormals = new Vector[indices.length / 3];
		int[] outputNormalIndices = new int[indices.length];

		Vector d1;
		Vector d2;
		int curNormalIndex = 0;
		for (int i = 0; i < indices.length; i += 3) {
			int i0 = indices[i];
			int i1 = indices[i + 1];
			int i2 = indices[i + 2];
			if ((i0 < 0) || (i0 >= indices.length) ||
					(i1 < 0) || (i1 >= indices.length) ||
					(i2 < 0) || (i2 >= indices.length)) {
				Console.root().features(Vector.class)
						.warn("computeFacetedNormals - " +
						"vertex index out of bounds or no end of triangle " +
						"index found");
				return null;
			}

			Vector v0 = vertices[i0];
			Vector v1 = vertices[i1];
			Vector v2 = vertices[i2];
			d1 = v1.subtract(v0);
			d2 = v2.subtract(v0);

			Vector n;

			if (ccw) {
				n = d1.crossProduct(d2);
			} else {
				n = d2.crossProduct(d1);
			}

			n.normalize();
			outputNormals[curNormalIndex] = n;
			outputNormalIndices[i] = curNormalIndex;
			outputNormalIndices[i + 1] = curNormalIndex;
			outputNormalIndices[i + 2] = curNormalIndex;
			curNormalIndex++;
		}

		return new Normals(outputNormals, outputNormalIndices);
	}

	public static void vector3Sub(Vector dest, Vector v1, Vector v2) {
		dest.setX(v1.getX() - v2.getX());
		dest.setY(v1.getY() - v2.getY());
		dest.setZ(v1.getZ() - v2.getZ());
	}

	public static void add(Vector dest, Vector v1, Vector v2) {
		dest.setX(v1.getX() + v2.getX());
		dest.setY(v1.getY() + v2.getY());
		dest.setZ(v1.getZ() + v2.getZ());
	}

	public static void add(Vector dest, Vector v1, Vector v2, Vector v3) {
		dest.setX(v1.getX() + v2.getX() + v3.getX());
		dest.setY(v1.getY() + v2.getY() + v3.getY());
		dest.setZ(v1.getZ() + v2.getZ() + v3.getZ());
	}

	public static void add(Vector dest, Vector v1, Vector v2, Vector v3, Vector v4) {
		dest.setX(v1.getX() + v2.getX() + v3.getX() + v4.getX());
		dest.setY(v1.getY() + v2.getY() + v3.getY() + v4.getY());
		dest.setZ(v1.getZ() + v2.getZ() + v3.getZ() + v4.getZ());
	}

	public static void mul(Vector dest, Vector v1, Vector v2) {
		dest.setX(v1.getX() * v2.getX());
		dest.setY(v1.getY() * v2.getY());
		dest.setZ(v1.getZ() * v2.getZ());
	}

	public static void div(Vector dest, Vector v1, Vector v2) {
		dest.setX(v1.getX() / v2.getX());
		dest.setY(v1.getY() / v2.getY());
		dest.setZ(v1.getZ() / v2.getZ());
	}

	public static void setMin(Vector a, Vector b) {
		a.setX(Math.min(a.getX(), b.getX()));
		a.setY(Math.min(a.getY(), b.getY()));
		a.setZ(Math.min(a.getZ(), b.getZ()));
	}

	public static void setMax(Vector a, Vector b) {
		a.setX(Math.max(a.getX(), b.getX()));
		a.setY(Math.max(a.getY(), b.getY()));
		a.setZ(Math.max(a.getZ(), b.getZ()));
	}

	@Deprecated
	public static float dot3(Vec4f v0, Vector v1) {
		return (float) ((v0.x() * v1.getX() + v0.y() * v1.getY() + v0.z() * v1.getZ()));
	}

	public static double dot3(Vector v0, Vector v1) {
		return (v0.getX() * v1.getX() + v0.getY() * v1.getY() + v0.getZ() * v1.getZ());
	}

	public static float dot3(Vec4f v0, Vec4f v1) {
		return (v0.x() * v1.x() + v0.y() * v1.y() + v0.z() * v1.z());
	}

	public static float lengthSquared3(Vec4f v) {
		return (v.x() * v.x() + v.y() * v.y() + v.z() * v.z());
	}

	public static void normalize3(Vec4f v) {
		float norm = (float)(1.0/Math.sqrt(v.x() * v.x() + v.y() * v.y() + v.z() * v.z()));
		v.setX(v.x() * norm);
		v.setY(v.y() * norm);
		v.setZ(v.z() * norm);
	}

	public static void cross3(Vector dest, Vec4f v1, Vec4f v2) {
		float x, y;
		x = v1.y() * v2.z() - v1.z() * v2.y();
		y = v2.x() * v1.z() - v2.z() * v1.x();
		dest.setZ(v1.x() * v2.y() - v1.y() * v2.x());
		dest.setX(x);
		dest.setY(y);
	}

	public static Vector xAxis() { return new Vector(1, 0, 0); }
	public static Vector yAxis() { return new Vector(0, 1, 0); }
	public static Vector zAxis() { return new Vector(0, 0, 1); }
	public static Vector negXAxis() { return new Vector(-1, 0, 0); }
	public static Vector negYAxis() { return new Vector(0, -1, 0); }
	public static Vector negZAxis() { return new Vector(0, 0, -1); }
}
