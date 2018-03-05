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
 * Arbitrary-size single-precision matrix class. Currently very
 * simple and only supports a few needed operations.
 */
// TODO  Use Tensor instead
@Deprecated
public class Matf {
	private float[] data;
	private int nCol; // number of columns
	private int nRow; // number of columns

	public Matf(int nRow, int nCol) {
		data = new float[nRow * nCol];
		this.nCol = nCol;
		this.nRow = nRow;
	}

	public Matf(Matf arg) {
		nRow = arg.nRow;
		nCol = arg.nCol;
		data = new float[nRow * nCol];
		System.arraycopy(arg.data, 0, data, 0, data.length);
	}

	public int nRow() {
		return nRow;
	}

	public int nCol() {
		return nCol;
	}

	/**
	 * Gets the (i,j)th element of this matrix, where i is the row
	 * index and j is the column index
	 */
	public float get(int i, int j) {
		return data[nCol * i + j];
	}

	/**
	 * Sets the (i,j)th element of this matrix, where i is the row
	 * index and j is the column index
	 */
	public void set(int i, int j, float val) {
		data[nCol * i + j] = val;
	}

	/**
	 * Returns transpose of this matrix; creates new matrix
	 */
	public Matf transpose() {
		Matf tmp = new Matf(nCol, nRow);
		for (int i = 0; i < nRow; i++) {
			for (int j = 0; j < nCol; j++) {
				tmp.set(j, i, get(i, j));
			}
		}
		return tmp;
	}

	/**
	 * Returns this * b; creates new matrix
	 */
	public Matf mul(Matf b) throws DimensionMismatchException {
		if (nCol() != b.nRow())
			throw new DimensionMismatchException();
		Matf tmp = new Matf(nRow(), b.nCol());
		for (int i = 0; i < nRow(); i++) {
			for (int j = 0; j < b.nCol(); j++) {
				float val = 0;
				for (int t = 0; t < nCol(); t++) {
					val += get(i, t) * b.get(t, j);
				}
				tmp.set(i, j, val);
			}
		}
		return tmp;
	}

	/**
	 * Returns this * v, assuming v is a column vector.
	 */
	public Vecf mul(Vecf v) throws DimensionMismatchException {
		if (nCol() != v.length()) {
			throw new DimensionMismatchException();
		}

		Vecf out = new Vecf(nRow());
		for (int i = 0; i < nRow(); i++) {
			float tmp = 0;
			for (int j = 0; j < nCol(); j++) {
				tmp += get(i, j) * v.get(j);
			}
			out.set(i, tmp);
		}
		return out;
	}

	/**
	 * If this is a 2x2 matrix, returns it as a Mat2f.
	 */
	public Mat2f toMat2f() throws DimensionMismatchException {
		if (nRow() != 2 || nCol() != 2) {
			throw new DimensionMismatchException();
		}
		Mat2f tmp = new Mat2f();
		for (int i = 0; i < 2; i++) {
			for (int j = 0; j < 2; j++) {
				tmp.set(i, j, get(i, j));
			}
		}
		return tmp;
	}

	/** If this is a 3x3 matrix, returns it as a Mat3f. */
	public Mat3f toMat3f() throws DimensionMismatchException {
		if (nRow() != 3 || nCol() != 3) {
			throw new DimensionMismatchException();
		}

		Mat3f tmp = new Mat3f();
		for (int i = 0; i < 3; i++) {
			for (int j = 0; j < 3; j++) {
				tmp.set(i, j, get(i, j));
			}
		}

		return tmp;
	}

	/**
	 * If this is a 4x4 matrix, returns it as a Mat4f.
	 */
	public Mat4f toMat4f() throws DimensionMismatchException {
		if (nRow() != 4 || nCol() != 4) {
			throw new DimensionMismatchException();
		}

		Mat4f tmp = new Mat4f();
		for (int i = 0; i < 4; i++) {
			for (int j = 0; j < 4; j++) {
				tmp.set(i, j, get(i, j));
			}
		}
		return tmp;
	}
}
