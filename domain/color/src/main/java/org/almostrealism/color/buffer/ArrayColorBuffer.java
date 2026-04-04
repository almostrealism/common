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

package org.almostrealism.color.buffer;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.RGB;
import org.almostrealism.color.computations.AverageColor;

/**
 * A {@link ColorBuffer} backed by 2D {@link RGB} arrays for the front and back surfaces.
 *
 * <p>Colors are stored in a grid indexed by UV coordinates. When retrieving a color,
 * bilinear interpolation is approximated using distance-weighted averaging over the
 * nine neighbouring cells (3×3 neighbourhood). Colors added to the buffer are scaled
 * by the configured scale factor {@code m}.</p>
 *
 * @see ColorBuffer
 * @author Michael Murray
 */
public class ArrayColorBuffer implements ColorBuffer {
	/** Current pixel position; may be used externally to track the active sample position. */
	public double[] position;

	/** Tracks whether this is the first retrieval, used to trigger optional diagnostic output. */
	boolean first;

	/** The color depth in bits for stored colors. */
	public int colorDepth = 48;

	/** The front-surface color grid, indexed as {@code front[x][y]}. */
	private RGB[][] front;

	/** The back-surface color grid, indexed as {@code back[x][y]}. */
	private RGB[][] back;

	/** The scale factor applied to each color sample when it is added to the buffer. */
	private double m = 1.0;

	/** An optional additional scale factor applied during retrieval when not in direct mode. */
	private final double k = 1.0;

	/** When {@code true}, the V coordinate is flipped (1 - v) during direct-mode retrieval. */
	private final boolean invertV = false;

	/** When {@code true}, uses direct cell lookup without distance-weighted interpolation. */
	private final boolean direct = false;

	/**
	 * Initialises the front and back color grids to the specified dimensions and sets the scale factor.
	 *
	 * @param w the grid width (number of columns)
	 * @param h the grid height (number of rows)
	 * @param m the scale factor applied to colors when added to the buffer
	 */
	public void setColorBufferSize(int w, int h, double m) {
		this.front = new RGB[w][h];
		this.back = new RGB[w][h];
		this.m = m;
	}
	
	/**
	 * Returns the dimensions of the color buffer as an integer array {@code [width, height]}.
	 *
	 * @return a 2-element array with the width and height of the buffer, or {@code [0, 0]} if unset
	 */
	public int[] getColorBufferDimensions() {
		if (this.front == null)
			return new int[2];
		else
			return new int[] {this.front.length, this.front[0].length};
	}
	
	/** {@inheritDoc} */
	public void setScale(double m) { this.m = m; }

	/** {@inheritDoc} */
	public double getScale() { return this.m; }

	/**
	 * Resets all cells in both the front and back buffers to {@code null},
	 * effectively clearing all accumulated color samples.
	 */
	public void clear() {
		this.front = new RGB[this.front.length][this.front[0].length];
		this.back = new RGB[this.back.length][this.back[0].length];
	}
	
	/**
	 * Returns the interpolated color at the given UV coordinates from
	 * the front or back buffer, using distance-weighted averaging of
	 * neighboring samples.
	 */
	public RGB getColorAt(double u, double v, boolean front) {
		if (front && this.front == null) return null;
		if (!front && this.back == null) return null;
		if (direct && this.k == 0.0) return null;
		
		if (direct && this.invertV) v = 1.0 - v;
		
		RGB[][] rgb = null;
		
		if (front)
			rgb = this.front;
		else
			rgb = this.back;
		
		int x = (int) (u * rgb.length);
		if (x >= rgb.length) x = rgb.length - 1;
		int y = (int) (v * rgb[x].length);
		if (y >= rgb[x].length) y = rgb[x].length - 1;
		
		double l1 = (1.0 / rgb.length);
		double l2 = (1.0 / rgb[x].length);
		
		double pu = u - (x + 0.5) * l1;
		double pv = v - (y + 0.5) * l2;
		
		AverageColor c = new AverageColor();
		c.setInvert(true);
		double a, b, b2;
		
		b = l2 - pv;
		b2 = b * b;
		
		if (x > 0 && y > 0) {
			a = l1 + pu;
			c.addColor(a * a + b2, rgb[x - 1][y - 1]);
		}
		
		if (y > 0) {
			a = pu;
			c.addColor(a * a + b2, rgb[x][y - 1]);
		}
		
		if (x < rgb.length - 1 && y > 0) {
			a = l1 - pu;
			c.addColor(a * a + b2, rgb[x + 1][y - 1]);
		}
		
		b = pv;
		b2 = b * b;
		
		if (x > 0) {
			a = l1 + pu;
			c.addColor(a * a + b2, rgb[x - 1][y]);
		}
		
		a = pu;
		c.addColor(a * a + b2, rgb[x][y]);
		
		if (x < rgb.length - 1) {
			a = l1 - pu;
			c.addColor(a * a + b2, rgb[x + 1][y]);
		}
		
		b = l2 + pv;
		b2 = b * b;
		
		if (x > 0 && y < rgb[x].length - 1) {
			a = l1 + u;
			c.addColor(a * a + b2, rgb[x - 1][y + 1]);
		}
		
		if (y < rgb.length - 1) {
			a = u;
			c.addColor(a * a + b2, rgb[x][y + 1]);
		}
		
		if (x < rgb.length - 1 && y < rgb[x].length - 1) {
			a = l1 - u;
			c.addColor(a * a + b2, rgb[x + 1][y + 1]);
		}
		
		if (this.first) {

//			TODO  Need to store images
			/*
			try {
				String f = this.absorber + "-front.ppm";
				System.out.println("AbsorberHashSet: Writing Surface Color Map to " + f);
				ImageCanvas.encodeImageFile(this.front, new File(f),
											ImageCanvas.PPMEncoding);
				f = this.absorber + "-back.ppm";
				System.out.println("AbsorberHashSet: Writing Surface Color Map to " + f);
				ImageCanvas.encodeImageFile(this.back, new File(f),
											ImageCanvas.PPMEncoding);
			} catch (IOException e) {
				System.out.println("AbsorberHashSet: " + e.getMessage());
									
			}
			*/
			
			this.first = false;
		}
		
		if (direct && this.k != 1.0) {
			PackedCollection pc = c.get().evaluate();
			RGB result = pc instanceof RGB ? (RGB) pc : new RGB(pc.toDouble(0), pc.toDouble(1), pc.toDouble(2));
			return result.multiply(this.k);
		} else {
			PackedCollection pc = c.get().evaluate();
			return pc instanceof RGB ? (RGB) pc : new RGB(pc.toDouble(0), pc.toDouble(1), pc.toDouble(2));
		}
	}
	
	/**
	 * Adds a color sample scaled by {@link #m} at the cell corresponding to the given UV coordinates.
	 *
	 * <p>The color is added (accumulated) to any existing color at that cell.
	 * UV values outside [0, 1) are rejected with a console message.</p>
	 *
	 * @param u     the horizontal texture coordinate in [0, 1)
	 * @param v     the vertical texture coordinate in [0, 1)
	 * @param front {@code true} to add to the front buffer, {@code false} for the back buffer
	 * @param c     the color to accumulate
	 */
	public void addColor(double u, double v, boolean front, RGB c) {
		if (front && this.front == null) return;
		if (!front && this.back == null) return;
		
		RGB[][] rgb = null;
		
		if (front)
			rgb = this.front;
		else
			rgb = this.back;
		
		if (u >= 1.0 || v >= 1.0 || u < 0.0 || v < 0.0) {
			System.out.println(
					"AbsorberHashSet: Surface coords from absorber (" + u + ", " + v + ")");
			return;
		}
		
		int x = (int) (u * rgb.length);
		int y = (int) (v * rgb[x].length);
		
		c.multiplyBy(this.m);
		
		if (rgb[x][y] == null)
			rgb[x][y] = c;
		else
			rgb[x][y].addTo(c);
	}
}