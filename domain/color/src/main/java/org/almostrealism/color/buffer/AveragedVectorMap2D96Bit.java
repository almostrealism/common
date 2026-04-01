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

package org.almostrealism.color.buffer;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;

/**
 * A 96-bit per-texel implementation of {@link AveragedVectorMap2D} that stores accumulated
 * vector data as scaled integers in three separate component buffers.
 *
 * <p>Vector components are quantised to the range of {@link Short#MAX_VALUE} when accumulated.
 * The average is recovered on retrieval by dividing the accumulated sum by the sample count
 * and {@link Short#MAX_VALUE}. Separate front and back buffers are maintained.</p>
 *
 * <p>Note: the accumulation logic for the buffers is not yet fully implemented (marked TODO).</p>
 *
 * @see AveragedVectorMap2D
 * @author Michael Murray
 */
public class AveragedVectorMap2D96Bit implements AveragedVectorMap2D {
	/** The maximum value of a {@link Short}, used for fixed-point scaling. */
	private static final double shortmax = Short.MAX_VALUE;

	/** An optional fixed vector returned for all UV positions, bypassing the per-texel buffers. */
	private double[] vector;

	/** The width of the UV grid in texels. */
	private int w;

	/** The height of the UV grid in texels. */
	private int h;

	/** Per-texel sample counts for the front surface. */
	private int[][] fcount;

	/** Per-texel sample counts for the back surface. */
	private int[][] bcount;

	/** Accumulated X component buffer for the front surface (quantised to short range). */
	private int[] fxBuf;

	/** Accumulated Y component buffer for the front surface (quantised to short range). */
	private int[] fyBuf;

	/** Accumulated Z component buffer for the front surface (quantised to short range). */
	private int[] fzBuf;

	/** Accumulated X component buffer for the back surface (quantised to short range). */
	private int[] bxBuf;

	/** Accumulated Y component buffer for the back surface (quantised to short range). */
	private int[] byBuf;

	/** Accumulated Z component buffer for the back surface (quantised to short range). */
	private int[] bzBuf;

	/**
	 * Constructs an uninitialised {@link AveragedVectorMap2D96Bit} with no allocated buffers.
	 * Fields must be set externally before the map is used.
	 */
	public AveragedVectorMap2D96Bit() { }

	/**
	 * Constructs an {@link AveragedVectorMap2D96Bit} with the specified grid dimensions,
	 * allocating front and back sample count arrays and component buffers of size {@code w * h}.
	 *
	 * @param w the grid width in texels
	 * @param h the grid height in texels
	 */
	public AveragedVectorMap2D96Bit(int w, int h) {
		this.fcount = new int[w][h];
		this.bcount = new int[w][h];
		this.w = w;
		this.h = h;
		int tot = w * h;
		this.fxBuf = new int[tot];
		this.fyBuf = new int[tot];
		this.fzBuf = new int[tot];
		this.bxBuf = new int[tot];
		this.byBuf = new int[tot];
		this.bzBuf = new int[tot];
	}

	/** Returns the fixed override vector, or {@code null} if per-texel buffering is active. */
	public double[] getVector() { return vector; }

	/** Sets a fixed override vector returned for all UV positions, disabling per-texel buffers. */
	public void setVector(double[] vector) { this.vector = vector; }

	/** Returns the grid width in texels. */
	public int getW() { return w; }

	/** Sets the grid width in texels. */
	public void setW(int w) { this.w = w; }

	/** Returns the grid height in texels. */
	public int getH() { return h; }

	/** Sets the grid height in texels. */
	public void setH(int h) { this.h = h; }

	/** Returns the per-texel sample count array for the front surface. */
	public int[][] getFcount() { return fcount; }

	/** Sets the per-texel sample count array for the front surface. */
	public void setFcount(int[][] fcount) { this.fcount = fcount; }

	/** Returns the per-texel sample count array for the back surface. */
	public int[][] getBcount() { return bcount; }

	/** Sets the per-texel sample count array for the back surface. */
	public void setBcount(int[][] bcount) { this.bcount = bcount; }

	/** Returns the accumulated X component buffer for the front surface. */
	public int[] getFxBuf() { return fxBuf; }

	/** Sets the accumulated X component buffer for the front surface. */
	public void setFxBuf(int[] fxBuf) { this.fxBuf = fxBuf; }

	/** Returns the accumulated Y component buffer for the front surface. */
	public int[] getFyBuf() { return fyBuf; }

	/** Sets the accumulated Y component buffer for the front surface. */
	public void setFyBuf(int[] fyBuf) { this.fyBuf = fyBuf; }

	/** Returns the accumulated Z component buffer for the front surface. */
	public int[] getFzBuf() { return fzBuf; }

	/** Sets the accumulated Z component buffer for the front surface. */
	public void setFzBuf(int[] fzBuf) { this.fzBuf = fzBuf; }

	/** Returns the accumulated X component buffer for the back surface. */
	public int[] getBxBuf() { return bxBuf; }

	/** Sets the accumulated X component buffer for the back surface. */
	public void setBxBuf(int[] bxBuf) { this.bxBuf = bxBuf; }

	/** Returns the accumulated Y component buffer for the back surface. */
	public int[] getByBuf() { return byBuf; }

	/** Sets the accumulated Y component buffer for the back surface. */
	public void setByBuf(int[] byBuf) { this.byBuf = byBuf; }

	/** Returns the accumulated Z component buffer for the back surface. */
	public int[] getBzBuf() { return bzBuf; }

	/** Sets the accumulated Z component buffer for the back surface. */
	public void setBzBuf(int[] bzBuf) { this.bzBuf = bzBuf; }

	/**
	 * Sets a fixed override vector from individual components, clearing all per-texel buffers.
	 *
	 * @param x the X component of the fixed vector
	 * @param y the Y component of the fixed vector
	 * @param z the Z component of the fixed vector
	 */
	public void setVector(double x, double y, double z) {
		this.vector = new double[] {x, y, z};
		this.fxBuf = null;
		this.fyBuf = null;
		this.fzBuf = null;
		this.bxBuf = null;
		this.byBuf = null;
		this.bzBuf = null;
	}

	/**
	 * Accumulates a vector sample at the texel corresponding to UV coordinates.
	 *
	 * <p>UV values outside [0, 1) are rejected with a console message. The actual
	 * component accumulation into the integer buffers is not yet implemented (marked TODO).</p>
	 *
	 * @param u     the horizontal texture coordinate in [0, 1)
	 * @param v     the vertical texture coordinate in [0, 1)
	 * @param e     a producer yielding the 3-component vector to accumulate (currently unused)
	 * @param front {@code true} to increment the front sample count, {@code false} for the back
	 */
	@Override
	public void addVector(double u, double v, Producer<PackedCollection> e, boolean front) {
		if (u >= 1.0 || v >= 1.0 || u < 0.0 || v < 0.0) {
			System.out.println("AveragedVectorMap2D96Bit: Invalid UV " + u + ", " + v);
			return;
		}
		
		int px = (int) (u * w);
		int py = (int) (v * h);
		int t = px + py * w;
		
		int[][] count = this.fcount;
		if (!front) count = this.bcount;
		
		count[px][py]++;
//		if (count[px][py] >= Short.MAX_VALUE)
//			System.out.print("AveragedVectorMap2D96Bit: Overflow.");
		
		if (this.vector != null) {}

//		TODO
//		if (front) {
//			this.fxBuf[t] += x * Short.MAX_VALUE;
//			this.fyBuf[t] += y * Short.MAX_VALUE;
//			this.fzBuf[t] += z * Short.MAX_VALUE;
//		} else {
//			this.fxBuf[t] += x * Short.MAX_VALUE;
//			this.fyBuf[t] += y * Short.MAX_VALUE;
//			this.fzBuf[t] += z * Short.MAX_VALUE;
//		}
	}
	
	/**
	 * Returns the averaged vector at the texel corresponding to UV coordinates.
	 *
	 * <p>If a fixed override vector has been set via {@link #setVector(double, double, double)},
	 * it is returned directly. Otherwise the accumulated component sums are divided by the sample
	 * count to compute the average. UV values outside [0, 1) return a zero vector.</p>
	 *
	 * @param u     the horizontal texture coordinate in [0, 1)
	 * @param v     the vertical texture coordinate in [0, 1)
	 * @param front {@code true} to query the front surface, {@code false} for the back
	 * @return a double array of length 3 containing the averaged vector components
	 */
	public double[] getVector(double u, double v, boolean front) {
		if (this.vector != null) return this.vector;
		
		if (u >= 1.0 || v >= 1.0 || u < 0.0 || v < 0.0) {
			System.out.println("AveragedVectorMap2D96Bit: Invalid UV " + u + ", " + v);
			return new double[3];
		}
		
		int px = (int) (u * w);
		int py = (int) (v * h);
		int t = px + py * w;
		
		if (front) {
			double[] xyz = {(this.fxBuf[t] / shortmax) / this.fcount[px][py],
							(this.fyBuf[t] / shortmax) / this.fcount[px][py],
							(this.fzBuf[t] / shortmax) / this.fcount[px][py]};
			return xyz;
		} else {

			double[] xyz = {(this.bxBuf[t] / shortmax) / this.bcount[px][py],
							(this.byBuf[t] / shortmax) / this.bcount[px][py],
							(this.bzBuf[t] / shortmax) / this.bcount[px][py]};
			return xyz;
		}
	}
	
	/**
	 * Returns the number of vector samples accumulated at the specified UV texel.
	 *
	 * @param u     the horizontal texture coordinate in [0, 1)
	 * @param v     the vertical texture coordinate in [0, 1)
	 * @param front {@code true} to query the front sample count, {@code false} for the back
	 * @return the sample count at the texel, or {@code 0} for out-of-range UV
	 */
	public int getSampleCount(double u, double v, boolean front) {
		if (u >= 1.0 || v >= 1.0 || u < 0.0 || v < 0.0) {
			System.out.println("AveragedVectorMap2D96Bit: Invalid UV " + u + ", " + v);
			return 0;
		}
		
		int px = (int) (u * w);
		int py = (int) (v * h);
		
		if (front)
			return this.fcount[px][py];
		else
			return this.bcount[px][py];
	}
}
