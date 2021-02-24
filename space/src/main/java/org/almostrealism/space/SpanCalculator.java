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

package org.almostrealism.space;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

public class SpanCalculator {
	public static boolean verbose = true;
	
	public static class RowList extends ArrayList {
		public boolean add(Object o) {
			if (o instanceof boolean[] == false)
				throw new IllegalArgumentException("RowList: " + o + " is not a row.");
			
			if (this.contains(o)) {
				return false;
			} else {
				// SpanCalculator.print((boolean[]) o);
				return super.add(o);
			}
		}
		
		public void add(int index, Object o) { this.add(o); }

		public boolean addAll(Collection c) {
			Iterator itr = c.iterator();
			boolean b = false;
			while (itr.hasNext()) if (this.add(itr.next())) b = true;
			return b;
		}

		public boolean addAll(int index, Collection c) { return this.addAll(c); }
		
		public boolean contains(Object o) {
			if (o instanceof boolean[] == false) return false;
			
			boolean row[] = (boolean[]) o;
			Iterator itr = this.iterator();
			
			w: while (itr.hasNext()) {
				boolean b[] = (boolean[]) itr.next();
				for (int i = 0; i < b.length; i++) if (b[i] != row[i]) continue w;
				return true;
			}
			
			return false;
		}
	}
	
	private static Matrix3DSolutionOutput output;
	
	private List rows;
	private int x, y, z;
	
	public static void main(String args[]) throws IOException {
		BufferedReader in = new BufferedReader(new FileReader(args[0]));

		String line = in.readLine();
		while (line.startsWith("#")) line = in.readLine();

		String dim[] = line.split(" ");
		int x = Integer.parseInt(dim[0]);
		int y = Integer.parseInt(dim[1]);
		int z = Integer.parseInt(dim[2]);

		List rows = new RowList();
		
		int count = 0;
		
		w: while (true) {
			boolean piece[][][] = new boolean[x][y][z];
			
			String head = in.readLine();
			while (head != null && head.startsWith("#")) head = in.readLine();
			
			if (head == null) break w;
			
			String h[] = head.split(" ");
			boolean bh[] = new boolean[h.length];
			for (int i = 0; i < h.length; i++) {
				if (h[i].equals("0"))
					bh[i] = false;
				else
					bh[i] = true;
			}
			
			if (output == null)
				output = new Matrix3DSolutionOutput(x, y, z, bh.length);
			
			for (int i = 0; i < x; i++) {
				for (int j = 0; j < y; j++) {
					line = in.readLine();
					while (line != null && line.startsWith("#")) line = in.readLine();
					if (line == null) break w;
					
					String s[] = line.split(" ");

					for (int k = 0; k < z; k++) {
						if (s[k].equals("0"))
							piece[i][j][k] = false;
						else
							piece[i][j][k] = true;
					}
				}
			}
			
			count++;
			int tot = 0, utot = 0;
			
			for (int n = 0; n < 4; n++) {
				boolean npiece[][][] = piece;
				
				for (int m = 0; m < 4; m++) {
					boolean mpiece[][][] = npiece;
					
					for (int l = 0; l < 4; l++) {
						SpanCalculator span = new SpanCalculator(mpiece, x, y, z);
						boolean r[][] = span.getRows();
						tot += r.length;
						
						for (int i = 0; i < r.length; i++) {
							boolean ro[] = new boolean[r[i].length + bh.length];
							System.arraycopy(bh, 0, ro, 0, bh.length);
							System.arraycopy(r[i], 0, ro, bh.length, r[i].length);
							if (rows.add(ro)) utot++;
						}
						
						mpiece = SpanCalculator.rotateZ(mpiece);
					}
					
					npiece = SpanCalculator.rotateY(npiece);
				}
				
				piece = SpanCalculator.rotateX(piece);
			}
				
			System.out.println("Piece " + count + ": " + tot +
								" rows, " + utot + " unique.");
		}
		
		PrintStream out = new PrintStream(new FileOutputStream("space.txt"));
		Iterator itr = rows.iterator();
		
		while (itr.hasNext()) {
			boolean r[] = (boolean[]) itr.next();
			
			for (int i = 0; i < r.length; i++) {
				if (i > 0) out.print(" ");
				
				if (r[i])
					out.print("1");
				else
					out.print("0");
			}
			
			out.println();
		}
		
		System.out.println("SpanCalculator: Wrote " + rows.size() + " rows to space.txt");
	}

	public SpanCalculator(boolean piece[][][], int x, int y, int z) {
		boolean row[] = new boolean[x * y * z];
		
		int len = 0;
		
		for (int i = 0; i < piece.length; i++) {
			for (int j = 0; j < piece[i].length; j++) {
				for (int k = 0; k < piece[i][j].length; k++) {
					row[k + j * y + i * y * x] = piece[i][j][k];
					if (piece[i][j][k]) len++;
				}
			}
		}
		
		this.init(row, x, y, z);
	}
	
	public static boolean[][][] swapRows(boolean piece[][][]) {
		boolean swap[][][] = new boolean[piece.length][piece[0].length][piece[0][0].length];
		
		for (int i = 0; i < piece.length; i++) {
			for (int j = 0; j < piece.length; j++) {
				for (int k = 0; k < piece.length; k++) {
					swap[i][swap[i].length - j - 1][k] = piece[i][j][k];
				}
			}
		}
		
		return swap;
	}
	
	public static boolean[][][] swapCols(boolean piece[][][]) {
		boolean swap[][][] = new boolean[piece.length]
		                                [piece[0].length]
		                                [piece[0][0].length];
		
		for (int i = 0; i < piece.length; i++) {
			for (int j = 0; j < piece.length; j++) {
				for (int k = 0; k < piece.length; k++) {
					swap[i][j][swap[i][j].length - k - 1] = piece[i][j][k];
				}
			}
		}
		
		return swap;
	}
	
	public static boolean[][][] rotateX(boolean piece[][][]) {
		int l = piece.length;
		boolean rotated[][][] = new boolean[l][l][l];
		
		for (int i = 0; i < l; i++) {
			for (int j = 0; j < l; j++) {
				for (int k = 0; k < l; k++) {
					rotated[i][k][l - j - 1] = piece[i][j][k];
				}
			}
		}
		
		return rotated;
	}
	
	public static boolean[][][] rotateY(boolean piece[][][]) {
		int l = piece.length;
		boolean rotated[][][] = new boolean[l][l][l];
		
		for (int i = 0; i < l; i++) {
			for (int j = 0; j < l; j++) {
				for (int k = 0; k < l; k++) {
					rotated[k][j][l - i - 1] = piece[i][j][k];
				}
			}
		}
		
		return rotated;
	}
	
	public static boolean[][][] rotateZ(boolean piece[][][]) {
		int l = piece.length;
		boolean rotated[][][] = new boolean[l][l][l];
		
		for (int i = 0; i < l; i++) {
			for (int j = 0; j < l; j++) {
				for (int k = 0; k < l; k++) {
					rotated[j][l - i - 1][k] = piece[i][j][k];
				}
			}
		}
		
		return rotated;
	}
	
	public static void print(boolean piece[][][]) {
		if (!SpanCalculator.verbose) return;
		
		for (int i = 0; i < piece.length; i++) {
			System.out.println("# X = " + i);
			
			for (int j = 0; j < piece[i].length; j++) {
				for (int k = 0; k < piece[i][j].length; k++) {
					if (piece[i][j][k])
						System.out.print("1 ");
					else
						System.out.print("0 ");
				}
				
				System.out.println();
			}
		}
	}
	
	public static void print(boolean row[]) {
		for (int i = 0; i < row.length; i++)
			if (row[i]) SpanCalculator.output.nextColumn(i);
		
		SpanCalculator.output.nextRow();
		SpanCalculator.output.print();
		SpanCalculator.output.init();
	}
	
	protected void init(boolean row[], int x, int y, int z) {
		this.x = x;
		this.y = y;
		this.z = z;

		int first = Integer.MAX_VALUE;
		int last = 0;
		int len = 0;
		
		for (int i = 0; i < row.length; i++) {
			if (row[i]) len++;
			if (row[i] && i < first) first = i;
			if (row[i] && i > last) last = i;
		}

		this.rows = new ArrayList();
		
		if (first == Integer.MAX_VALUE) {
			System.out.println("NO PIECE?");
			return;
		}
		
		this.spanZ(row, first, last, true);
		this.spanZ(row, first, last, false);
	}

	public boolean[][] getRows() {
		return (boolean[][]) this.rows.toArray(new boolean[0][0]);
	}
	
	public static boolean modTest(boolean r[], boolean nr[], int d) {
		for (int i = 0; i < r.length; i = i + d) {
			int t1 = 0, t2 = 0;
			for (int j = 0; j < d; j++) if (r[i + j]) t1++;
			for (int j = 0; j < d; j++) if (nr[i + j]) t2++;
			if (t1 != t2) return true;
		}
		
		return false;
	}
	
	protected void spanZ(boolean row[], int first, int last, boolean plus) {
		this.rows.add(row);
		this.spanY(row, first, last, plus);
		this.spanY(row, first, last, !plus);
		
		if (plus && last < row.length - 1) {
			boolean translated[] = new boolean[row.length];
	
			for (int i = 1; i < translated.length; i++)
				translated[i] = row[i - 1];
			
			if (modTest(row, translated, this.z)) return;
			
			this.rows.add(translated);
			this.spanY(translated, first + 1, last + 1, plus);
			this.spanY(translated, first + 1, last + 1, !plus);
			this.spanZ(translated, first + 1, last + 1, plus);
		}
		
		if (!plus && first > 0) {
			boolean translated[] = new boolean[row.length];
			
			for (int i = 0; i < translated.length - 1; i++)
				translated[i] = row[i + 1];
			
			if (modTest(row, translated, this.z)) return;
			
			this.rows.add(translated);
			this.spanY(translated, first - 1, last - 1, plus);
			this.spanY(translated, first - 1, last - 1, !plus);
			this.spanZ(translated, first - 1, last - 1, plus);
		}
	}

	protected void spanY(boolean row[], int first, int last, boolean plus) {
		this.spanX(row, first, last, plus);
		this.spanX(row, first, last, !plus);
		
		if (plus && last < row.length - this.z) {
			boolean translated[] = new boolean[row.length];
			
			for (int i = this.z; i < translated.length; i++)
				translated[i] = row[i - this.z];
			
			if (modTest(row, translated, this.z * this.y)) return;
			
			this.rows.add(translated);
			this.spanX(translated, first + this.z, last + this.z, plus);
			this.spanX(translated, first + this.z, last + this.z, !plus);
			this.spanY (translated, first + this.z, last + this.z, plus);
		}
		
		if (!plus && first >= this.z) {
			boolean translated[] = new boolean[row.length];
			
			for (int i = 0; i < translated.length - this.z; i++)
				translated[i] = row[i + this.z];
			
			if (modTest(row, translated, this.z * this.y)) return;
			
			this.rows.add(translated);
			this.spanX(translated, first - this.z, last - this.z, plus);
			this.spanX(translated, first - this.z, last - this.z, !plus);
			this.spanY (translated, first - this.z, last - this.z, plus);
		}
	}

	protected void spanX(boolean row[], int first, int last, boolean plus) {
		int d = this.z * this.y;

		if (plus && last < row.length - d) {
			boolean translated[] = new boolean[row.length];
			
			for (int i = d; i < translated.length; i++)
				translated[i] = row[i - d];
			
			this.rows.add(translated);
			this.spanX(translated, first + d, last + d, plus);
		}
		
		if (!plus && first >= d) {
			boolean translated[] = new boolean[row.length];
			
			for (int i = 0; i < translated.length - d; i++)
				translated[i] = row[i + d];
	
			this.rows.add(translated);
			this.spanX(translated, first - d, last - d, plus);
		}
	}
} 