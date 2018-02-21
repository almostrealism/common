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

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;

import org.almostrealism.algebra.Vector;
import org.almostrealism.graph.Mesh;
import org.almostrealism.graph.Triangle;
import org.almostrealism.io.SpatialData;
import org.almostrealism.space.AbstractSurface;
import org.almostrealism.space.Gradient;
import org.almostrealism.space.Scene;

public class Matrix3DSolutionOutput {
	private int x, y, z, off;
	private boolean pieces[][][][];
	private AbstractSurface models[];
	
	private int piece = -1;
	private Matrix3D data;
	private boolean exitOnFirst = false;
	private PrintStream out;
	
	public Matrix3DSolutionOutput(int x, int y, int z, int off) throws FileNotFoundException {
		this.x = x;
		this.y = y;
		this.z = z;
		this.off = off;
		
		this.pieces = new boolean[off][x][y][z];
		this.models = new AbstractSurface[off];
		
		this.init();
		
		this.out = new PrintStream(new FileOutputStream("solutions.txt"));
	}
	
	public void init() {
		this.data = new Matrix3D(x, y, z, true);
		for (int i = 0; i < this.x; i++) {
			for (int j = 0; j < this.y; j++) {
				for (int k = 0; k < this.z; k++) {
					this.data.setInt(i, j, k, -2);
				}
			}
		}
	}
	
	public void nextColumn(int column) {
		int col = column - off;
		
		if (col < 0) {
			piece = column;
			return;
		}
		
		int px = (col / (this.z * this.y));
		int py = (col / this.z) % y;
		int pz = col % this.z;
		this.data.setInt(px, py, pz, -1);
	}
	
	public void nextRow() {
		for (int i = 0; i < this.x; i++) {
			for (int j = 0; j < this.y; j++) {
				for (int k = 0; k < this.z; k++) {
					if (this.data.getInt(i, j, k) == -1)
						this.data.setInt(i, j, k, this.piece);
				}
			}
		}
		
		if (this.checkComplete()) {
			this.print();
			this.init();
			if (this.exitOnFirst) System.exit(1);
		}
	}
	
	public boolean checkComplete() {
		for (int i = 0; i < this.x; i++) {
			for (int j = 0; j < this.y; j++) {
				for (int k = 0; k < this.z; k++) {
					if (this.data.getInt(i, j, k) < 0)
						return false;
				}
			}
		}
		
		return true;
	}
	
	public void loadPieceFromGTS(int piece, String file) throws IOException {
		this.loadPieceFromGTS(piece, new FileInputStream(file));
	}
	
	public void loadPieceFromGTS(int p, InputStream in) throws IOException {
		Scene scene = SpatialData.decodeScene(in, SpatialData.GTSEncoding, false, null);
		Gradient s[] = scene.getSurfaces();
		
		boolean piece[][][] = new boolean[this.x][this.y][this.z];
		this.models[p] = null;
		
		i: for (int i = 0; i < s.length; i++) {
			if (s[i] instanceof Mesh) {
				Triangle t[] = ((Mesh)s[i]).getTriangles();
				for (int j = 0; j < t.length; j++) piece = addTriangle(piece, t[i]);
			} else if (s[i] instanceof Triangle) {
				piece = addTriangle(piece, (Triangle) s[i]);
			} else if (s[i] instanceof AbstractSurface){
				piece = addVector(piece, ((AbstractSurface)s[i]).getLocation());
			} else {
				continue i;
			}
			
			if (this.models[p] == null) this.models[p] = (AbstractSurface) s[i];
		}
	}
	
	public static boolean[][][] addTriangle(boolean piece[][][], Triangle t) {
		Vector v[] = t.getVertices();
		piece = addVector(piece, v[0]);
		piece = addVector(piece, v[1]);
		piece = addVector(piece, v[2]);
		
		return piece;
	}
	
	public static boolean[][][] addVector(boolean piece[][][], Vector v) {
		int x = (int) v.getX();
		int y = (int) v.getY();
		int z = (int) v.getZ();
		
		piece[x][y][z] = true;
		return piece;
	}
	
	public void print() { this.out.println(this.toString()); }
	public String toString() { return this.data.toString(true); }
}
