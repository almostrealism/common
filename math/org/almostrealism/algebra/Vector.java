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

import org.almostrealism.geometry.Positioned;
import org.almostrealism.util.Defaults;

/**
 * A Vector object represents a 3d vector. It stores three coordinates, x, y, z.
 */
public class Vector implements Positioned, Triple, Cloneable {
  public static final int CARTESIAN_COORDINATES = 0;
  public static final int SPHERICAL_COORDINATES = 1;

  private double x, y, z;

	/**
	 * Constructs a Vector object with coordinates at the origin.
	 */
	public Vector() {}
	
	/**
	 * Constructs a new Vector object using the specified coordinates.
	 * 
	 * @param x  Cartesian X coordinate or spherical distance from origin.
	 * @param y  Cartesian Y coordinate or angle from positive Z axis (rads).
	 * @param z  Cartesian Z coordinate or angle from positive XY plane (rads).
	 * @param coordSys  Vector.CARTESIAN_COORDINATES or Vector.SPHERICAL_COORDINATES.
	 */
	public Vector(double x, double y, double z, int coordSys) {
		if (coordSys == Vector.CARTESIAN_COORDINATES) {
			this.x = x;
			this.y = y;
			this.z = z;
		} else if (coordSys == Vector.SPHERICAL_COORDINATES) {
			this.x = x * Math.sin(y) * Math.cos(z);
			this.y = x * Math.sin(y) * Math.sin(z);
			this.z = x * Math.cos(y);
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
	
	public Vector(double v[]) { this(v[0], v[1], v[2]); }
	
	public Vector(float v[]) { this(v[0], v[1], v[2]); }
	
	/**
	 * @return  A random vector uniformly distributed on the unit sphere as a Vector object.
	 */
	public static Vector uniformSphericalRandom() {
		return new Vector(1.0, 2 * Math.PI * Math.random(), 2 * Math.PI * Math.random());
	}

	public double[] getData() { return new double[] {this.x, this.y, this.z}; }
	
	/**
	 * Sets the X coordinate of this Vector object.
	 */
	public void setX(double x) { this.x = x; }
	
	/**
	 * Sets the Y coordinate of this Vector object.
	 */
	public void setY(double y) { this.y = y; }
	
	/**
	 * Sets the Z coordinate of this Vector object.
	 */
	public void setZ(double z) { this.z = z; }
	
	/**
	 * Returns the X coordinate of this Vector object.
	 */
	public double getX() { return this.x; }
	
	/**
	 * Returns the Y coordinate of this Vector object.
	 */
	public double getY() { return this.y; }
	
	/**
	 * Returns the Z coordinate of this Vector object.
	 */
	public double getZ() { return this.z; }
	
	@Override
	public double getA() { return getX(); }

	@Override
	public double getB() { return getY(); }

	@Override
	public double getC() { return getZ(); }

	@Override
	public void setA(double a) { setX(a); }

	@Override
	public void setB(double b) { setY(b); }

	@Override
	public void setC(double c) { setZ(c); }

	@Override
	public void setPosition(float x, float y, float z) {
		this.x = x;
		this.y = y;
		this.z = z;
	}

	@Override
	public float[] getPosition() {
		return new float[]{(float) x, (float) y, (float) z};
	}

	/**
	 * Sets the coordinates of this {@link Vector} to the coordinates of the
	 * specified {@link Vector}.
	 *
	 * @param v  The Vector to set coordinates from.
	 * @return  This Vector.
	 */
	public Vector setTo(Vector v) {
		this.x = v.x;
		this.y = v.y;
		this.z = v.z;
		return this;
	}

	/** Returns the opposite of the vector represented by this {@link Vector}. */
	public Vector minus() {
		Vector newVector = new Vector(-this.getX(), -this.getY(), -this.getZ());
		return newVector;
	}
	
	/**
	 * Returns the sum of the vector represented by this Vector object and that of the specified Vector object.
	 */
	public Vector add(Vector vector) {
		Vector sum = new Vector(this.getX() + vector.getX(), this.getY() + vector.getY(), this.getZ() + vector.getZ());
		
		return sum;
	}
	
	/**
	 * Adds the vector represented by the specified Vector object to this Vector object.
	 * 
	 * @param vector  The Vector object to add.
	 */
	public void addTo(Vector vector) {
		this.x = this.x + vector.x;
		this.y = this.y + vector.y;
		this.z = this.z + vector.z;
	}

	/**
	 * this = a + s * b
	 */
	@Deprecated
	public Vector addScaled(Vector a, double s, Vector b) {
		x = a.x + s * b.x;
		y = a.y + s * b.y;
		z = a.z + s * b.z;
		return this;
	}

	/**
	 * this = a + s * b
	 */
	@Deprecated
	public Vector scaleAdd(double s, Vector a, Vector b) {
		return addScaled(a, s, b);
	}

	/**
	 * this = a + b
	 */
	@Deprecated
	public void add(Vector a, Vector b) {
		x = a.getX() + b.getX();
		y = a.getY() + b.getY();
		z = a.getZ() + b.getZ();
	}

	/**
	 * Returns the difference of the vector represented by this Vector object and that of the specified Vector object.
	 * The specified vector is subtracted from this one.
	 */
	public Vector subtract(Vector vector) {
		Vector difference = new Vector(this.getX() - vector.getX(), this.getY() - vector.getY(), this.getZ() - vector.getZ());
		
		return difference;
	}
	
	/**
	 * Subtracts the vector represented by the specified Vector object from this Vector object.
	 * 
	 * @param vector  The Vector object to be subtracted.
	 */
	public void subtractFrom(Vector vector) {
		this.x = this.x - vector.x;
		this.y = this.y - vector.y;
		this.z = this.z - vector.z;
	}

	/**
	 * this = a - b
	 */
	public void subtract(Vector a, Vector b) {
		this.x = a.getX() - b.getX();
		this.y = a.getY() - b.getY();
		this.z = a.getZ() - b.getZ();
	}

	/**
	 * Sets the value of this {@link Vector} to the negation of {@link Vector} v.
	 *
	 * @param v  The source Vector
	 */
	@Deprecated
	public void negate(Vector v) {
		this.x = -v.x;
		this.y = -v.y;
		this.z = -v.z;
	}

	/**
	 * Returns the product of the vector represented by this Vector object and the specified value.
	 */
	public Vector multiply(double value) {
		Vector product = new Vector(this.x * value, this.y * value, this.z * value);
		
		return product;
	}
	
	/**
	 * Multiplies the vector represented by this Vector object by the specified double value.
	 * 
	 * @param value  The factor to multiply by.
	 */
	public void multiplyBy(double value) {
		this.x = this.x * value;
		this.y = this.y * value;
		this.z = this.z * value;
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
	 * @param value  The value to divide by.
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

	public float[] toFloat() { return new float[] { (float) getX(), (float) getY(), (float) getZ() }; }
	
	/**
	 * Returns the length of the vector represented by this Vector object as a double value.
	 */
	public double length() { return Math.sqrt(this.lengthSq()); }
	
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
		
		Vector vector = (Vector)obj;
		
		if (vector.getX() == this.getX() && vector.getY() == this.getY() && vector.getZ() == this.getZ())
			return true;
		else
			return false;
	}
	
	/** @see java.lang.Object#clone() */
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
	
	/**
	 * @return  A String representation of this Vector object.
	 */
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
}
