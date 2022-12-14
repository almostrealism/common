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

/**
 * 3x3 matrix class useful for simple linear algebra. Representation
 * is (as Mat4f) in row major order and assumes multiplication by
 * column vectors on the right.
 *
 * TODO  Replace uses with {@link TransformMatrix}.
 */

// TODO  Replace with Tensor
@Deprecated
public class Mat3f {
	private float[] data; // TODO  Change to double

	/**
	 * Creates new matrix initialized to the zero matrix
	 */
	public Mat3f() {
		data = new float[9];
	}

	/**
	 * Initialize to the identity matrix.
	 */
	public void makeIdent() {
		for (int i = 0; i < 3; i++) {
			for (int j = 0; j < 3; j++) {
				if (i == j) {
					set(i, j, 1.0f);
				} else {
					set(i, j, 0.0f);
				}
			}
		}
	}

	/**
	 * Gets the (i,j)th element of this matrix, where i is the row
	 * index and j is the column index
	 */
	public float get(int i, int j) {
		return data[3 * i + j];
	}

	/**
	 * Copies the matrix values in the specified row into the vector parameter.
	 *
	 * @param row  The matrix row
	 * @param v  The vector into which the matrix row values will be copied
	 */
	public void getRow(int row, Vector v) {
		if (row == 0) {
			v.setX(get(0, 0));
			v.setY(get(0, 1));
			v.setZ(get(0, 2));
		} else if (row == 1) {
			v.setX(get(1, 0));
			v.setY(get(1, 1));
			v.setZ(get(1, 2));
		} else if (row == 2) {
			v.setX(get(2, 0));
			v.setY(get(2, 1));
			v.setZ(get(2, 2));
		} else {
			throw new ArrayIndexOutOfBoundsException(row);
		}
	}

	/**
	 * Copies the matrix values in the specified column into the vector parameter.
	 *
	 * @param column  The matrix column
	 * @param v  The vector into which the matrix row values will be copied
	 */
	public void getColumn(int column, Vector v) {
		if (column == 0) {
			v.setX(get(0, 0));
			v.setY(get(1, 0));
			v.setZ(get(2, 0));
		} else if (column == 1) {
			v.setX(get(0, 1));
			v.setY(get(1, 1));
			v.setZ(get(2, 1));
		} else if (column == 2) {
			v.setX(get(0, 2));
			v.setY(get(1, 2));
			v.setZ(get(2, 2));
		} else {
			throw new ArrayIndexOutOfBoundsException(column);
		}
	}

	/**
	 * Sets the (i,j)th element of this matrix, where i is the row
	 * index and j is the column index
	 */
	public void set(int i, int j, double val) {
		data[3 * i + j] = (float) val;
	}

	public void set(Mat3f m) {
		for (int i = 0; i < m.data.length; i++) {
			this.data[i] = m.data[i];
		}
	}

	/** Sets this Mat3f to identity. */
	public void setIdentity() {
		this.set(0, 0, 1.0f);
		this.set(0, 1, 0.0f);
		this.set(0, 2, 0.0f);

		this.set(1, 0, 0.0f);
		this.set(1, 1, 1.0f);
		this.set(1, 2, 0.0f);

		this.set(2, 0, 0.0f);
		this.set(2, 1, 0.0f);
		this.set(2, 2, 1.0f);
	}

	/**
	 * Set column i (i=[0..2]) to vector v.
	 */
	public void setCol(int i, Vector v) {
		set(0, i, v.getX());
		set(1, i, v.getY());
		set(2, i, v.getZ());
	}

	/**
	 * Set row i (i=[0..2]) to vector v.
	 */
	public void setRow(int i, Vector v) {
		set(i, 0, v.getX());
		set(i, 1, v.getY());
		set(i, 2, v.getZ());
	}

	public void setRow(int i, double x, double y, double z) {
		set(i, 0, (float) x);
		set(i, 1, (float) y);
		set(i, 2, (float) z);
	}

	/**
	 * Transpose this matrix in place.
	 */
	public void transpose() {
		float t;
		t = get(0, 1);
		set(0, 1, get(1, 0));
		set(1, 0, t);

		t = get(0, 2);
		set(0, 2, get(2, 0));
		set(2, 0, t);

		t = get(1, 2);
		set(1, 2, get(2, 1));
		set(2, 1, t);
	}

	/**
	 * Sets the value of this matrix to the transpose of the argument matrix.
	 *
	 * @param m1 the matrix to be transposed
	 */
	public void transpose(Mat3f m1) {
		if (this != m1) {
			this.set(0, 0, m1.get(0, 0));
			this.set(0, 1, m1.get(1, 0));
			this.set(0, 2, m1.get(2, 0));

			this.set(1, 0, m1.get(0, 1));
			this.set(1, 1, m1.get(1, 1));
			this.set(1, 2, m1.get(2, 1));

			this.set(2, 0, m1.get(0, 2));
			this.set(2, 1, m1.get(1, 2));
			this.set(2, 2, m1.get(2, 2));
		} else {
			this.transpose();
		}
	}

	/**
	 * Return the determinant. Computed across the zeroth row.
	 */
	public float determinant() {
		return (get(0, 0) * (get(1, 1) * get(2, 2) - get(2, 1) * get(1, 2)) +
				get(0, 1) * (get(2, 0) * get(1, 2) - get(1, 0) * get(2, 2)) +
				get(0, 2) * (get(1, 0) * get(2, 1) - get(2, 0) * get(1, 1)));
	}

	/**
	 * Full matrix inversion in place. If matrix is singular, returns
	 * false and matrix contents are untouched. If you know the matrix
	 * is orthonormal, you can call transpose() instead.
	 */
	public boolean invert() {
		float det = determinant();
		if (det == 0.0f)
			return false;

		// Form cofactor matrix
		Mat3f cf = new Mat3f();
		cf.set(0, 0, get(1, 1) * get(2, 2) - get(2, 1) * get(1, 2));
		cf.set(0, 1, get(2, 0) * get(1, 2) - get(1, 0) * get(2, 2));
		cf.set(0, 2, get(1, 0) * get(2, 1) - get(2, 0) * get(1, 1));
		cf.set(1, 0, get(2, 1) * get(0, 2) - get(0, 1) * get(2, 2));
		cf.set(1, 1, get(0, 0) * get(2, 2) - get(2, 0) * get(0, 2));
		cf.set(1, 2, get(2, 0) * get(0, 1) - get(0, 0) * get(2, 1));
		cf.set(2, 0, get(0, 1) * get(1, 2) - get(1, 1) * get(0, 2));
		cf.set(2, 1, get(1, 0) * get(0, 2) - get(0, 0) * get(1, 2));
		cf.set(2, 2, get(0, 0) * get(1, 1) - get(1, 0) * get(0, 1));

		// Now copy back transposed
		for (int i = 0; i < 3; i++)
			for (int j = 0; j < 3; j++)
				set(i, j, cf.get(j, i) / det);
		return true;
	}

	/**
	 * Multiply a 3D vector by this matrix. NOTE: src and dest must be
	 * different vectors.
	 */
	public void xformVec(Vector src, Vector dest) {
		dest.setX(get(0, 0) * src.getX() + get(0, 1) * src.getY() + get(0, 2) * src.getZ());
		dest.setY(get(1, 0) * src.getX() + get(1, 1) * src.getY() + get(1, 2) * src.getZ());
		dest.setZ(get(2, 0) * src.getX() + get(2, 1) * src.getY() + get(2, 2) * src.getZ());
	}

	/**
	 * Returns this * b; creates new matrix
	 */
	public Mat3f mul(Mat3f b) {
		Mat3f tmp = new Mat3f();
		tmp.mul(this, b);
		return tmp;
	}

	/**
	 * this = a * b
	 */
	public void mul(Mat3f a, Mat3f b) {
		for (int rc = 0; rc < 3; rc++)
			for (int cc = 0; cc < 3; cc++) {
				float tmp = 0.0f;
				for (int i = 0; i < 3; i++)
					tmp += a.get(rc, i) * b.get(i, cc);
				set(rc, cc, tmp);
			}
	}

	// TODO  Performance can be greatly improved here
	public void transform(Vector v) {
		throw new RuntimeException("transform is not implemented");
	}

	public String toString() {
		String endl = System.getProperty("line.separator");
		return "(" +
				get(0, 0) + ", " + get(0, 1) + ", " + get(0, 2) + endl +
				get(1, 0) + ", " + get(1, 1) + ", " + get(1, 2) + endl +
				get(2, 0) + ", " + get(2, 1) + ", " + get(2, 2) + ")";
	}

	public static void scale(Mat3f dest, Mat3f mat, Vector s) {
		dest.set(0, 0, mat.get(0, 0) * s.getX());
		dest.set(0, 1, mat.get(0, 1) * s.getY());
		dest.set(0, 2, mat.get(0, 2) * s.getZ());
		dest.set(1, 0, mat.get(1, 0) * s.getX());
		dest.set(1, 1, mat.get(1, 1) * s.getY());
		dest.set(1, 2, mat.get(1, 2) * s.getZ());
		dest.set(2, 0, mat.get(2, 0) * s.getX());
		dest.set(2, 1, mat.get(2, 1) * s.getY());
		dest.set(2, 2, mat.get(2, 2) * s.getZ());
	}

	public static void absolute(Mat3f mat) {
		mat.set(0, 0, Math.abs(mat.get(0, 0)));
		mat.set(0, 1, Math.abs(mat.get(0, 1)));
		mat.set(0, 2, Math.abs(mat.get(0, 2)));
		mat.set(1, 0, Math.abs(mat.get(1, 0)));
		mat.set(1, 1, Math.abs(mat.get(1, 1)));
		mat.set(1, 2, Math.abs(mat.get(1, 2)));
		mat.set(2, 0, Math.abs(mat.get(2, 0)));
		mat.set(2, 1, Math.abs(mat.get(2, 1)));
		mat.set(2, 2, Math.abs(mat.get(2, 2)));
	}

	public static void setFromOpenGLSubMatrix(Mat3f mat, float[] m) {
		mat.set(0, 0, m[0]);
		mat.set(0, 1, m[4]);
		mat.set(0, 2, m[8]);
		mat.set(1, 0, m[1]);
		mat.set(1, 1, m[5]);
		mat.set(1, 2, m[9]);
		mat.set(2, 0, m[2]);
		mat.set(2, 1, m[6]);
		mat.set(2, 2, m[10]);
	}

	public static void getOpenGLSubMatrix(Mat3f mat, float[] m) {
		m[0] = mat.get(0, 0);
		m[1] = mat.get(1, 0);
		m[2] = mat.get(2, 0);
		m[3] = 0f;
		m[4] = mat.get(0, 1);
		m[5] = mat.get(1, 1);
		m[6] = mat.get(2, 1);
		m[7] = 0f;
		m[8] = mat.get(0, 2);
		m[9] = mat.get(1, 2);
		m[10] = mat.get(2, 2);
		m[11] = 0f;
	}

	/**
	 * setEulerZYX
	 *
	 * @param eulerX a const reference to a btVector3 of euler angles
	 * These angles are used to produce a rotation matrix. The euler
	 * angles are applied in ZYX order. I.e a vector is first rotated
	 * about X then Y and then Z
	 */
	public static void setEulerZYX(Mat3f mat, float eulerX, float eulerY, float eulerZ) {
		float ci = (float) Math.cos(eulerX);
		float cj = (float) Math.cos(eulerY);
		float ch = (float) Math.cos(eulerZ);
		float si = (float) Math.sin(eulerX);
		float sj = (float) Math.sin(eulerY);
		float sh = (float) Math.sin(eulerZ);
		float cc = ci * ch;
		float cs = ci * sh;
		float sc = si * ch;
		float ss = si * sh;

		mat.setRow(0, cj * ch, sj * sc - cs, sj * cc + ss);
		mat.setRow(1, cj * sh, sj * ss + cc, sj * cs - sc);
		mat.setRow(2, -sj, cj * si, cj * ci);
	}

	private static double tdotx(Mat3f mat, Vector vec) {
		return mat.get(0, 0) * vec.getX() + mat.get(1, 0) * vec.getY() + mat.get(2, 0) * vec.getZ();
	}

	private static double tdoty(Mat3f mat, Vector vec) {
		return mat.get(0, 1) * vec.getX() + mat.get(1, 1) * vec.getY() + mat.get(2, 1) * vec.getZ();
	}

	private static double tdotz(Mat3f mat, Vector vec) {
		return mat.get(0, 2) * vec.getX() + mat.get(1, 2) * vec.getY() + mat.get(2, 2) * vec.getZ();
	}

	public static void transposeTransform(Vector dest, Vector vec, Mat3f mat) {
		double x = tdotx(mat, vec);
		double y = tdoty(mat, vec);
		double z = tdotz(mat, vec);
		dest.setX(x);
		dest.setY(y);
		dest.setZ(z);
	}

	private static float cofac(Mat3f mat, int r1, int c1, int r2, int c2) {
		return mat.get(r1, c1) * mat.get(r2, c2) - mat.get(r1, c2) * mat.get(r2, c1);
	}

	public static void invert(Mat3f mat) {
		float co_x = cofac(mat, 1, 1, 2, 2);
		float co_y = cofac(mat, 1, 2, 2, 0);
		float co_z = cofac(mat, 1, 0, 2, 1);

		float det = mat.get(0, 0) * co_x + mat.get(0, 1) * co_y + mat.get(0, 2) * co_z;
		assert (det != 0f);

		float s = 1f / det;
		float m00 = co_x * s;
		float m01 = cofac(mat, 0, 2, 2, 1) * s;
		float m02 = cofac(mat, 0, 1, 1, 2) * s;
		float m10 = co_y * s;
		float m11 = cofac(mat, 0, 0, 2, 2) * s;
		float m12 = cofac(mat, 0, 2, 1, 0) * s;
		float m20 = co_z * s;
		float m21 = cofac(mat, 0, 1, 2, 0) * s;
		float m22 = cofac(mat, 0, 0, 1, 1) * s;

		mat.set(0, 0, m00);
		mat.set(0, 1, m01);
		mat.set(0, 2, m02);
		mat.set(1, 0, m10);
		mat.set(1, 1, m11);
		mat.set(1, 2, m12);
		mat.set(2, 0, m20);
		mat.set(2, 1, m21);
		mat.set(2, 2, m22);
	}
}
