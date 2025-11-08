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
 * A three-dimensional matrix supporting both double and integer values.
 *
 * <p>
 * {@link Matrix3D} provides a simple 3D array wrapper with dimensions (x, y, z)
 * where each dimension can be independently sized. The matrix can store either
 * double or integer values, determined at construction time.
 * </p>
 *
 * @deprecated This class is deprecated and should be replaced with {@link Tensor},
 *             which provides a more flexible and feature-rich multi-dimensional structure.
 * @author  Michael Murray
 * @see Tensor
 */
@Deprecated
public class Matrix3D {
	protected double matrix[][][];
	protected int intMatrix[][][];
	protected int x, y, z;
	protected boolean integer;
	
	/**
	 * Constructs a 3D matrix with the specified dimensions.
	 * Each entry will be stored as a double.
	 *
	 * @param x  width (X dimension)
	 * @param y  height (Y dimension)
	 * @param z  depth (Z dimension)
	 */
	public Matrix3D(int x, int y, int z) {
		this(x, y, z, false);
	}

	/**
	 * Constructs a 3D matrix with the specified dimensions.
	 * Each entry will be stored as an int if the integer flag is true,
	 * otherwise as a double.
	 *
	 * @param x        width (X dimension)
	 * @param y        height (Y dimension)
	 * @param z        depth (Z dimension)
	 * @param integer  true if entries should be stored as integers, false for doubles
	 */
	public Matrix3D(int x, int y, int z, boolean integer) {
		this.integer = integer;
		this.x = x;
		this.y = y;
		this.z = z;
		
		if (this.integer) {
			this.intMatrix = new int[x][y][z];
		} else {
			this.matrix = new double[x][y][z];
		}
	}

	/**
	 * Sets the value at the specified 3D coordinates (double matrix only).
	 *
	 * @param x      the X coordinate
	 * @param y      the Y coordinate
	 * @param z      the Z coordinate
	 * @param value  the value to set
	 */
	public void set(int x, int y, int z, double value) {
		this.matrix[x][y][z] = value;
	}

	/**
	 * Sets the integer value at the specified 3D coordinates (integer matrix only).
	 *
	 * @param x      the X coordinate
	 * @param y      the Y coordinate
	 * @param z      the Z coordinate
	 * @param value  the integer value to set
	 */
	public void setInt(int x, int y, int z, int value) {
		this.intMatrix[x][y][z] = value;
	}

	/**
	 * Gets the value at the specified 3D coordinates.
	 * Returns the value as a double regardless of storage type.
	 *
	 * @param x  the X coordinate
	 * @param y  the Y coordinate
	 * @param z  the Z coordinate
	 * @return the value at the specified coordinates
	 */
	public double get(int x, int y, int z) {
		if (this.integer)
			return this.intMatrix[x][y][z];
		else
			return this.matrix[x][y][z];
	}

	/**
	 * Gets the integer value at the specified 3D coordinates (integer matrix only).
	 *
	 * @param x  the X coordinate
	 * @param y  the Y coordinate
	 * @param z  the Z coordinate
	 * @return the integer value at the specified coordinates
	 */
	public int getInt(int x, int y, int z) { return this.intMatrix[x][y][z]; }

	/**
	 * Returns a string representation of this matrix.
	 *
	 * @return a multi-line string showing all matrix values
	 */
	public String toString() { return this.toString(false); }

	/**
	 * Returns a string representation of this matrix.
	 *
	 * @param noNeg  if true, negative values are shown as 0 and positive values are incremented by 1
	 * @return a multi-line string showing all matrix values
	 */
	public String toString(boolean noNeg) {
		StringBuffer buf = new StringBuffer();
		
		for (int i = 0; i < this.x; i++) {
			for (int j = 0; j < this.y; j++) {
				for (int k = 0; k < this.z; k++) {
					if (this.integer) {
						if (noNeg && this.getInt(i, j, k) < 0)
							buf.append("0");
						else if (noNeg)
							buf.append(this.getInt(i, j, k) + 1);
						else
							buf.append(this.getInt(i, j, k));
					} else {
						buf.append(this.get(i, j, k));
					}
					
					buf.append(" ");
				}
				
				buf.append("\n");
			}
			
			buf.append("******\n");
		}
		
		buf.append("\n");
		return buf.toString();
	}
}
