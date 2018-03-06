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
 * 3-element double-precision vector
 */
@Deprecated
public class Vec3d implements Triple {
	private double x;
	private double y;
	private double z;

	public Vec3d() {
	}

	public Vec3d(Vec3d arg) {
		set(arg);
	}

	public Vec3d(double x, double y, double z) {
		set(x, y, z);
	}

	public Vec3d copy() {
		return new Vec3d(this);
	}

	public void set(Vec3d arg) {
		set(arg.x, arg.y, arg.z);
	}

	public void set(double x, double y, double z) {
		this.x = x;
		this.y = y;
		this.z = z;
	}

	/**
	 * Sets the ith component, 0 <= i < 3
	 */
	public void set(int i, double val) {
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
	public double get(int i) {
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

	public void setA(double a) { this.x = a; }
	public void setB(double b) { this.y = b; }
	public void setC(double c) { this.z = c; }

	public double getA() { return x; }
	public double getB() { return y; }
	public double getC() { return z; }

	public double x() {
		return x;
	}
	public double y() {
		return y;
	}
	public double z() {
		return z;
	}

	public void setX(double x) {
		this.x = x;
	}
	public void setY(double y) {
		this.y = y;
	}
	public void setZ(double z) {
		this.z = z;
	}

	public double dot(Vec3d arg) {
		return x * arg.x + y * arg.y + z * arg.z;
	}

	public double length() {
		return Math.sqrt(lengthSquared());
	}

	public double lengthSquared() {
		return this.dot(this);
	}

	public void normalize() {
		double len = length();
		if (len == 0.0) return;
		scale(1.0f / len);
	}

	/**
	 * Returns this * val; creates new vector
	 */
	public Vec3d times(double val) {
		Vec3d tmp = new Vec3d(this);
		tmp.scale(val);
		return tmp;
	}

	/**
	 * this = this * val
	 */
	public void scale(double val) {
		x *= val;
		y *= val;
		z *= val;
	}

	/**
	 * Returns this + arg; creates new vector
	 */
	public Vec3d plus(Vec3d arg) {
		Vec3d tmp = new Vec3d();
		tmp.add(this, arg);
		return tmp;
	}

	/**
	 * this = this + b
	 */
	public void add(Vec3d b) {
		add(this, b);
	}

	/**
	 * this = a + b
	 */
	public void add(Vec3d a, Vec3d b) {
		x = a.x + b.x;
		y = a.y + b.y;
		z = a.z + b.z;
	}

	/**
	 * Returns this + s * arg; creates new vector
	 */
	public Vec3d addScaled(double s, Vec3d arg) {
		Vec3d tmp = new Vec3d();
		tmp.addScaled(this, s, arg);
		return tmp;
	}

	/**
	 * this = a + s * b
	 */
	public void addScaled(Vec3d a, double s, Vec3d b) {
		x = a.x + s * b.x;
		y = a.y + s * b.y;
		z = a.z + s * b.z;
	}

	/**
	 * Returns this - arg; creates new vector
	 */
	public Vec3d minus(Vec3d arg) {
		Vec3d tmp = new Vec3d();
		tmp.sub(this, arg);
		return tmp;
	}

	/**
	 * this = this - b
	 */
	public void sub(Vec3d b) {
		sub(this, b);
	}

	/**
	 * this = a - b
	 */
	public void sub(Vec3d a, Vec3d b) {
		x = a.x - b.x;
		y = a.y - b.y;
		z = a.z - b.z;
	}

	/**
	 * Returns this cross arg; creates new vector
	 */
	public Vec3d cross(Vec3d arg) {
		Vec3d tmp = new Vec3d();
		tmp.cross(this, arg);
		return tmp;
	}

	/**
	 * this = a cross b. NOTE: "this" must be a different vector than
	 * both a and b.
	 */
	public void cross(Vec3d a, Vec3d b) {
		x = a.y * b.z - a.z * b.y;
		y = a.z * b.x - a.x * b.z;
		z = a.x * b.y - a.y * b.x;
	}

	public String toString() {
		return "(" + x + ", " + y + ", " + z + ")";
	}
}
