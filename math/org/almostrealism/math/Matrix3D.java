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

package org.almostrealism.math;

// Uses of this can be replaced with Tensor
public class Matrix3D {
	protected double matrix[][][];
	protected int intMatrix[][][];
	protected int x, y, z;
	protected boolean integer;
	
	/**
	 * Contructs a 3D matrix with the specified dimensions.
	 * Each entry will be stored as a double.
	 * 
	 * @param x  Width.
	 * @param y  Height.
	 * @param z  Depth.
	 */
	public Matrix3D(int x, int y, int z) {
		this(x, y, z, false);
	}
	
	/**
	 * Contructs a 3D matrix with the specified dimensions.
	 * Each entry will be stored as an int if the integer
	 * flag is true. Otherwise each entry will be stored as
	 * a double.
	 * 
	 * @param x  Width.
	 * @param y  Height.
	 * @param z  Depth.
	 * @param integer  True if entries are integers, false otherwise.
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
	
	public void set(int x, int y, int z, double value) {
		this.matrix[x][y][z] = value;
	}
	
	public void setInt(int x, int y, int z, int value) {
		this.intMatrix[x][y][z] = value;
	}
	
	public double get(int x, int y, int z) {
		if (this.integer)
			return this.intMatrix[x][y][z];
		else
			return this.matrix[x][y][z];
	}
	
	public int getInt(int x, int y, int z) { return this.intMatrix[x][y][z]; }
	
	public String toString() { return this.toString(false); }
	
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
