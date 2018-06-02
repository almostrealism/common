/*
 * Copyright 2018 Michael Murray
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

import org.almostrealism.geometry.Positioned;
import org.almostrealism.math.Hardware;
import org.almostrealism.util.Defaults;
import org.jocl.CL;
import org.jocl.Pointer;
import org.jocl.Sizeof;
import org.jocl.cl_mem;

/**
 * A {@link Vector} represents a 3d vector. It stores three coordinates, x, y, z
 * in a buffer maintained by JOCL.
 */
public class Vector implements Positioned, Triple, Cloneable {
	public static final int CARTESIAN_COORDINATES = 0;
	public static final int SPHERICAL_COORDINATES = 1;

	public static final Vector X_AXIS = new Vector(1, 0, 0);
	public static final Vector Y_AXIS = new Vector(0, 1, 0);
	public static final Vector Z_AXIS = new Vector(0, 0, 1);
	public static final Vector NEG_X_AXIS = new Vector(-1, 0, 0);
	public static final Vector NEG_Y_AXIS = new Vector(0, -1, 0);
	public static final Vector NEG_Z_AXIS = new Vector(0, 0, -1);

	private cl_mem mem; // TODO  Make final

	/** Constructs a {@link Vector} with coordinates at the origin. */
	public Vector() {
		mem = CL.clCreateBuffer(Hardware.getLocalHardware().getContext(),
								CL.CL_MEM_READ_WRITE,3 * Sizeof.cl_double,
								null, null);
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
			setMem(new double[] { x, y, z });
		} else if (coordSys == Vector.SPHERICAL_COORDINATES) {
			setMem(new double[] { x * Math.sin(y) * Math.cos(z),
								x * Math.sin(y) * Math.sin(z),
								x * Math.cos(y) });
		} else {
			throw new IllegalArgumentException("Illegal coordinate system type code: " + coordSys);
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

	/**
	 * Returns the X coordinate of this Vector object.
	 */
	public double getX() {
		return this.x;
	}

	/**
	 * Returns the Y coordinate of this Vector object.
	 */
	public double getY() {
		return this.y;
	}

	/**
	 * Returns the Z coordinate of this Vector object.
	 */
	public double getZ() {
		return this.z;
	}

	@Override
	@Deprecated
	public double getA() {
		return getX();
	}

	@Override
	@Deprecated
	public double getB() {
		return getY();
	}

	@Override
	@Deprecated
	public double getC() {
		return getZ();
	}

	@Override
	@Deprecated
	public void setA(double a) {
		setX(a);
	}

	@Override
	@Deprecated
	public void setB(double b) {
		setY(b);
	}

	@Override
	@Deprecated
	public void setC(double c) {
		setZ(c);
	}

	@Override
	@Deprecated
	public void setPosition(float x, float y, float z) {
		setX(x);
		setY(y);
		setZ(z);
	}

	@Override
	@Deprecated
	public float[] getPosition() {
		return new float[] { (float) getX(), (float) getY(), (float) getZ() };
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

	/** Gets the ith component, 0 <= i < 3 */
	@Deprecated
	public double get(int i) {
		switch (i) {
			case 0:
				return getX();
			case 1:
				return getY();
			case 2:
				return getZ();
			default:
				throw new IndexOutOfBoundsException();
		}
	}

	/**
	 * Sets the coordinates of this {@link Vector} to the coordinates of the
	 * specified {@link Vector}.
	 *
	 * @param v The Vector to set coordinates from.
	 * @return This Vector.
	 */
	public Vector setTo(Vector v) {
		setMem(0, v, 0, 3);
		return this;
	}

	/**
	 * Returns the opposite of the vector represented by this {@link Vector}.
	 */
	public Vector minus() {
		// TODO  Make fast
		return new Vector(-this.getX(), -this.getY(), -this.getZ());
	}

	/**
	 * Returns the sum of the vector represented by this Vector object and that of the specified Vector object.
	 */
	public Vector add(Vector vector) {
		// TODO  Make fast
		return new Vector(this.getX() + vector.getX(), this.getY() + vector.getY(), this.getZ() + vector.getZ());
	}

	/**
	 * Adds the vector represented by the specified Vector object to this Vector object.
	 *
	 * @param vector The Vector object to add.
	 */
	public void addTo(Vector vector) {
		// TODO  Make fast
		this.setMem(0, new Vector(getX() + vector.getX(),getY() + vector.getY(),getZ() + vector.getZ()),0,3);
	}

	/**
	 * Returns the difference of the vector represented by this Vector object and that of the specified Vector object.
	 * The specified vector is subtracted from this one.
	 */
	public Vector subtract(Vector vector) {
		// TODO  Make fast
		return new Vector(this.getX() - vector.getX(), this.getY() - vector.getY(), this.getZ() - vector.getZ());
	}

	/**
	 * Subtracts the vector represented by the specified Vector object from this Vector object.
	 *
	 * @param vector The Vector object to be subtracted.
	 */
	public void subtractFrom(Vector vector) {
		// TODO  Make fast
		setTo(new Vector(getX() - vector.getX(),
						getY() - vector.getY(),
						getZ() - vector.getZ()));
	}

	/**
	 * Returns the product of the vector represented by this Vector object and the specified value.
	 */
	public Vector multiply(double value) {
		// TODO  Make fast
		return new Vector(getX() * value, getY() * value, getZ() * value);
	}

	/**
	 * Multiplies the vector represented by this Vector object by the specified double value.
	 *
	 * @param value The factor to multiply by.
	 */
	public void multiplyBy(double value) {
		// TODO  Make fast
		setTo(new Vector(getX() * value, getY() * value, getZ() * value));
	}

	/**
	 * this = s * t
	 */
	public void scale(double s, Vector t) {
		this.x = s * t.x;
		this.y = s * t.y;
		this.z = s * t.z;
	}

	/**
	 * Returns the quotient of the division of the vector represented by this Vector object by the specified value.
	 */
	public Vector divide(double value) {
		Vector quotient = new Vector(this.getX() / value, this.getY() / value, this.getZ() / value);

		return quotient;
	}

	/**
	 * Divides the vector represented by this Vector object by the specified double value.
	 *
	 * @param value The value to divide by.
	 */
	public void divideBy(double value) {
		this.x = this.x / value;
		this.y = this.y / value;
		this.z = this.z / value;
	}

	/**
	 * Returns the dot product of the vector represented by this Vector object and that of the specified Vector object.
	 */
	public double dotProduct(Vector vector) {
		double product = this.x * vector.x + this.y * vector.y + this.z * vector.z;

		return product;
	}

	/**
	 * Returns the cross product of the vector represented by this Vector object and that of the specified Vector object.
	 */
	public Vector crossProduct(Vector vector) {
		Vector product = new Vector(this.getY() * vector.getZ() - this.getZ() * vector.getY(),
				this.getZ() * vector.getX() - this.getX() * vector.getZ(),
				this.getX() * vector.getY() - this.getY() * vector.getX());

		return product;
	}

	/**
	 * this = a cross b. NOTE: "this" must be a different vector than
	 * both a and b.
	 */
	public void cross(Vector a, Vector b) {
		x = a.y * b.z - a.z * b.y;
		y = a.z * b.x - a.x * b.z;
		z = a.x * b.y - a.y * b.x;
	}

	public float[] toFloat() {
		return new float[]{(float) getX(), (float) getY(), (float) getZ()};
	}

	/**
	 * Returns the length of the vector represented by this Vector object as a double value.
	 */
	public double length() {
		return Math.sqrt(this.lengthSq());
	}

	/**
	 * Returns the squared length of the vector represented by this
	 * {@link Vector} as a double value.
	 */
	public double lengthSq() {
		double lengthSq = this.x * this.x + this.y * this.y + this.z * this.z;

		return lengthSq;
	}

	public void normalize() {
		double len = this.length();
		if (len != 0.0 && len != 1.0) this.divideBy(len);
	}

	/**
	 * Sets the value of this {@link Vector} to the
	 * normalization of {@link Vector} v1.
	 *
	 * @param v1 the un-normalized vector
	 */
	public void normalize(Vector v1) {
		double norm = Math.sqrt(v1.x * v1.x + v1.y * v1.y + v1.z * v1.z);
		this.x = v1.x / norm;
		this.y = v1.y / norm;
		this.z = v1.z / norm;
	}

	public Vecf toVecf() {
		Vecf v = new Vecf(3);
		v.set(0, (float) x);
		v.set(1, (float) y);
		v.set(2, (float) z);
		return v;
	}

	public double[] toArray() { return new double[] {x, y, z}; }

	/**
	 * Returns an integer hash code value for this Vector object obtained
	 * by adding all 3 components and casting to an int.
	 */
	public int hashCode() {
		double value = this.getX() + this.getY() + this.getZ();

		return (int) value;
	}

	/**
	 * Returns true if and only if the object specified represents a 3d
	 * vector that is geometrically equal to the vector represented by
	 * this {@link Vector}.
	 */
	public boolean equals(Object obj) {
		if (obj instanceof Vector == false)
			return false;

		Vector vector = (Vector) obj;

		if (vector.getX() == this.getX() && vector.getY() == this.getY() && vector.getZ() == this.getZ())
			return true;
		else
			return false;
	}

	private void setMem(double[] source) {
		setMem(0, source, 0, 3);
	}

	private void setMem(double[] source, int offset) {
		setMem(0, source, offset, 3);
	}

	private void setMem(int offset, double[] source, int srcOffset, int length) {
		Pointer src = Pointer.to(source).withByteOffset(srcOffset*Sizeof.cl_double);
		CL.clEnqueueWriteBuffer(Hardware.getLocalHardware().getQueue(), mem, CL.CL_TRUE,
								offset * Sizeof.cl_double, length * Sizeof.cl_double,
								src, 0, null, null);
	}

	private void setMem(int offset, Vector src, int srcOffset, int length) {
		CL.clEnqueueCopyBuffer(Hardware.getLocalHardware().getQueue(), src.mem, this.mem,
							srcOffset * Sizeof.cl_double,
							offset * Sizeof.cl_double,length * Sizeof.cl_double,
							0,null,null);
	}

	/**
	 * @see java.lang.Object#clone()
	 */
	@Override
	public Object clone() {
		try {
			Vector v = (Vector) super.clone();
			v.x = this.x;
			v.y = this.y;
			v.z = this.z;
			return v;
		} catch (CloneNotSupportedException e) {
			throw new RuntimeException(e);
		}
	}

	/** @return A String representation of this Vector object. */
	@Override
	public String toString() {
		StringBuffer value = new StringBuffer();

		value.append("[");
		value.append(Defaults.displayFormat.format(this.x));
		value.append(", ");
		value.append(Defaults.displayFormat.format(this.y));
		value.append(", ");
		value.append(Defaults.displayFormat.format(this.z));
		value.append("]");


		return value.toString();
	}

	// TODO  This should be an instance method on the dest vector, not a static method

	/**
	 * Makes an arbitrary vector perpendicular to <B>src</B> and
	 * inserts it into <B>dest</B>. Returns false if the source vector
	 * was equal to (0, 0, 0).
	 */
	public static boolean makePerpendicular(Vector src,
											Vector dest) {
		if ((src.getX() == 0.0) && (src.getY() == 0.0) && (src.getZ() == 0.0)) {
			return false;
		}

		if (src.getX() != 0.0) {
			if (src.getY() != 0.0) {
				dest.setX(-src.getY());
				dest.setY(src.getX());
				dest.setZ(0.0);
			} else {
				dest.setX(-src.getZ());
				dest.setY(0.0);
				dest.setZ(src.getX());
			}
		} else {
			dest.setPosition(1.0f, 0.0f, 0.0f);
		}

		return true;
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
			System.err.println("NormalCalc.computeFacetedNormals: numIndices wasn't " +
					"divisible by 3, so it can't possibly " +
					"represent a set of triangles");
			return null;
		}

		Vector[] outputNormals = new Vector[indices.length / 3];
		int[] outputNormalIndices = new int[indices.length];

		Vector d1 = new Vector();
		Vector d2 = new Vector();
		int curNormalIndex = 0;
		for (int i = 0; i < indices.length; i += 3) {
			int i0 = indices[i];
			int i1 = indices[i + 1];
			int i2 = indices[i + 2];
			if ((i0 < 0) || (i0 >= indices.length) ||
					(i1 < 0) || (i1 >= indices.length) ||
					(i2 < 0) || (i2 >= indices.length)) {
				System.err.println("NormalCalc.computeFacetedNormals: ERROR: " +
						"vertex index out of bounds or no end of triangle " +
						"index found");
				return null;
			}

			Vector v0 = vertices[i0];
			Vector v1 = vertices[i1];
			Vector v2 = vertices[i2];
			d1.subtract(v1, v0);
			d2.subtract(v2, v0);

			Vector n = new Vector();

			if (ccw) {
				n.cross(d1, d2);
			} else {
				n.cross(d2, d1);
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

	public static int maxAxis(Vector v) {
		int maxIndex = -1;
		double maxVal = -1e30f;

		if (v.getX() > maxVal) {
			maxIndex = 0;
			maxVal = v.getX();
		}
		if (v.getY() > maxVal) {
			maxIndex = 1;
			maxVal = v.getY();
		}
		if (v.getZ() > maxVal) {
			maxIndex = 2;
			maxVal = v.getZ();
		}

		return maxIndex;
	}

	public static int maxAxis4(Vec4f v) {
		int maxIndex = -1;
		float maxVal = -1e30f;
		if (v.x() > maxVal) {
			maxIndex = 0;
			maxVal = v.x();
		}

		if (v.y() > maxVal) {
			maxIndex = 1;
			maxVal = v.y();
		}

		if (v.z() > maxVal) {
			maxIndex = 2;
			maxVal = v.z();
		}

		if (v.w() > maxVal) {
			maxIndex = 3;
			maxVal = v.w();
		}

		return maxIndex;
	}

	public static int closestAxis4(Vec4f vec) {
		Vec4f tmp = new Vec4f(vec);
		tmp.absolute();
		return maxAxis4(tmp);
	}

	public static double getCoord(Vector vec, int num) {
		switch (num) {
			case 0: return vec.getX();
			case 1: return vec.getY();
			case 2: return vec.getZ();
			default: throw new InternalError();
		}
	}

	public static void setCoord(Vector vec, int num, double value) {
		switch (num) {
			case 0: vec.setX(value); break;
			case 1: vec.setY(value); break;
			case 2: vec.setZ(value); break;
			default: throw new InternalError();
		}
	}

	public static void mulCoord(Vector vec, int num, float value) {
		switch (num) {
			case 0: vec.setX(vec.getX() * value); break;
			case 1: vec.setY(vec.getY() * value); break;
			case 2: vec.setZ(vec.getZ() * value); break;
			default: throw new InternalError();
		}
	}

	public static void setInterpolate3(Vector dest, Vector v0, Vector v1, double rt) {
		double s = 1f - rt;

		dest.setX(s * v0.getX() + rt * v1.getX());
		dest.setY(s * v0.getY() + rt * v1.getY());
		dest.setZ(s * v0.getZ() + rt * v1.getZ());
		// don't do the unused w component
		//		m_co[3] = s * v0[3] + rt * v1[3];
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
}
