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
 * @author  Michael Murray
 */
public class AveragedVectorMap2D96Bit implements AveragedVectorMap2D {
	private static final double shortmax = Short.MAX_VALUE;
	
	private double[] vector;
	private int w, h;
	private int[][] fcount, bcount;
	private int[] fxBuf, fyBuf, fzBuf;
	private int[] bxBuf, byBuf, bzBuf;

	public AveragedVectorMap2D96Bit() { }
	
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

	public double[] getVector() { return vector; }
	public void setVector(double[] vector) { this.vector = vector; }
	public int getW() { return w; }
	public void setW(int w) { this.w = w; }
	public int getH() { return h; }
	public void setH(int h) { this.h = h; }
	public int[][] getFcount() { return fcount; }
	public void setFcount(int[][] fcount) { this.fcount = fcount; }
	public int[][] getBcount() { return bcount; }
	public void setBcount(int[][] bcount) { this.bcount = bcount; }
	public int[] getFxBuf() { return fxBuf; }
	public void setFxBuf(int[] fxBuf) { this.fxBuf = fxBuf; }
	public int[] getFyBuf() { return fyBuf; }
	public void setFyBuf(int[] fyBuf) { this.fyBuf = fyBuf; }
	public int[] getFzBuf() { return fzBuf; }
	public void setFzBuf(int[] fzBuf) { this.fzBuf = fzBuf; }
	public int[] getBxBuf() { return bxBuf; }
	public void setBxBuf(int[] bxBuf) { this.bxBuf = bxBuf; }
	public int[] getByBuf() { return byBuf; }
	public void setByBuf(int[] byBuf) { this.byBuf = byBuf; }
	public int[] getBzBuf() { return bzBuf; }
	public void setBzBuf(int[] bzBuf) { this.bzBuf = bzBuf; }

	public void setVector(double x, double y, double z) {
		this.vector = new double[] {x, y, z};
		this.fxBuf = null;
		this.fyBuf = null;
		this.fzBuf = null;
		this.bxBuf = null;
		this.byBuf = null;
		this.bzBuf = null;
	}

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
