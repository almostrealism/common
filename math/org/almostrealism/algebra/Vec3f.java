/*
 * Copyright 2018 Michael Murray
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

// TODO  Replace uses with Vector

/**
 * 3-element single-precision vector
 */
@Deprecated
public class Vec3f {
	public static final Vec3f X_AXIS = new Vec3f(1, 0, 0);
	public static final Vec3f Y_AXIS = new Vec3f(0, 1, 0);
	public static final Vec3f Z_AXIS = new Vec3f(0, 0, 1);
	public static final Vec3f NEG_X_AXIS = new Vec3f(-1, 0, 0);
	public static final Vec3f NEG_Y_AXIS = new Vec3f(0, -1, 0);
	public static final Vec3f NEG_Z_AXIS = new Vec3f(0, 0, -1);

	private float x;
	private float y;
	private float z;

	public Vec3f() {
	}

	public Vec3f(Vec3f arg) {
		set(arg);
	}

	public Vec3f(float x, float y, float z) {
		set(x, y, z);
	}

	public Vec3f copy() {
		return new Vec3f(this);
	}

	/**
	 * Convert to double-precision
	 */
	public Vec3d toDouble() {
		return new Vec3d(x, y, z);
	}

	public void set(Vec3f arg) {
		set(arg.x, arg.y, arg.z);
	}

	public void set(float x, float y, float z) {
		this.x = x;
		this.y = y;
		this.z = z;
	}

	/**
	 * Sets the ith component, 0 <= i < 3
	 */
	public void set(int i, float val) {
		switch (i) {
			case 0:
				x = val;
				break;
			case 1:
				y = val;
				break;
			case 2:
				z = val;
				break;
			default:
				throw new IndexOutOfBoundsException();
		}
	}

	/**
	 * Gets the ith component, 0 <= i < 3
	 */
	public float get(int i) {
		switch (i) {
			case 0:
				return x;
			case 1:
				return y;
			case 2:
				return z;
			default:
				throw new IndexOutOfBoundsException();
		}
	}

	public float x() {
		return x;
	}

	public float y() {
		return y;
	}

	public float z() {
		return z;
	}

	public void setX(float x) {
		this.x = x;
	}

	public void setY(float y) {
		this.y = y;
	}

	public void setZ(float z) {
		this.z = z;
	}

	public float dot(Vec3f arg) {
		return x * arg.x + y * arg.y + z * arg.z;
	}

	public float length() {
		return (float) Math.sqrt(lengthSquared());
	}

	public float lengthSquared() {
		return this.dot(this);
	}

	public void normalize() {
		float len = length();
		if (len == 0.0f) return;
		scale(1.0f / len);
	}

	/**
	 * Returns this * val; creates new vector
	 */
	public Vec3f times(float val) {
		Vec3f tmp = new Vec3f(this);
		tmp.scale(val);
		return tmp;
	}

	/**
	 * this = this * val
	 */
	public void scale(float val) {
		x *= val;
		y *= val;
		z *= val;
	}

	/**
	 * Returns this + arg; creates new vector
	 */
	public Vec3f plus(Vec3f arg) {
		Vec3f tmp = new Vec3f();
		tmp.add(this, arg);
		return tmp;
	}

	/**
	 * this = this + b
	 */
	public void add(Vec3f b) {
		add(this, b);
	}

	/**
	 * this = a + b
	 */
	public void add(Vec3f a, Vec3f b) {
		x = a.x + b.x;
		y = a.y + b.y;
		z = a.z + b.z;
	}

	/**
	 * Returns this + s * arg; creates new vector
	 */
	public Vec3f addScaled(float s, Vec3f arg) {
		Vec3f tmp = new Vec3f();
		tmp.addScaled(this, s, arg);
		return tmp;
	}

	/**
	 * this = a + s * b
	 */
	public void addScaled(Vec3f a, float s, Vec3f b) {
		x = a.x + s * b.x;
		y = a.y + s * b.y;
		z = a.z + s * b.z;
	}

	public void scaleAdd(float s, Vec3f a, Vec3f b) {
		addScaled(a, s, b);
	}

	public final void scale(float s, Vec3f t) {
		this.x = s * t.x;
		this.y = s * t.y;
		this.z = s * t.z;
	}

	/**
	 * Returns this - arg; creates new vector
	 */
	public Vec3f minus(Vec3f arg) {
		Vec3f tmp = new Vec3f();
		tmp.sub(this, arg);
		return tmp;
	}

	/**
	 * this = this - b
	 */
	public void sub(Vec3f b) {
		sub(this, b);
	}

	/**
	 * this = a - b
	 */
	public void sub(Vec3f a, Vec3f b) {
		x = a.x - b.x;
		y = a.y - b.y;
		z = a.z - b.z;
	}

	/**
	 * Sets the value of this Vec3f to the negation of Vec3f v.
	 *
	 * @param v  The source Vec3f
	 */
	public void negate(Vec3f v) {
		this.x = -v.x;
		this.y = -v.y;
		this.z = -v.z;
	}


	/**
	 * Negates the value of this Vec3f in place.
	 */
	public void negate() {
		this.x = -this.x;
		this.y = -this.y;
		this.z = -this.z;
	}

	/**
	 * Returns this cross arg; creates new vector
	 */
	public Vec3f cross(Vec3f arg) {
		Vec3f tmp = new Vec3f();
		tmp.cross(this, arg);
		return tmp;
	}

	/**
	 * this = a cross b. NOTE: "this" must be a different vector than
	 * both a and b.
	 */
	public void cross(Vec3f a, Vec3f b) {
		x = a.y * b.z - a.z * b.y;
		y = a.z * b.x - a.x * b.z;
		z = a.x * b.y - a.y * b.x;
	}

	public Vecf toVecf() {
		Vecf out = new Vecf(3);
		for (int i = 0; i < 3; i++) {
			out.set(i, get(i));
		}
		return out;
	}

	public String toString() {
		return "(" + x + ", " + y + ", " + z + ")";
	}
}
